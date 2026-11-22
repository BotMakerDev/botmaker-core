// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.plugin;

import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.slot.ValueContext;
import com.botmaker.plugin.api.source.ManagedClasses;
import com.botmaker.plugin.api.source.ManagedOpenSet;
import com.botmaker.plugin.api.source.ManagedParam;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.api.source.ParameterWrite;
import com.botmaker.plugin.api.source.PluginValues;
import com.botmaker.plugin.api.source.RunParameter;
import com.botmaker.plugin.api.value.Ref;
import com.botmaker.java.index.nav.Refactor;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.project.managed.BotMethods;
import com.botmaker.core.project.managed.JavaManagedValues;
import com.botmaker.core.project.managed.ManagedHolders;
import com.botmaker.core.project.managed.NestedClasses;
import com.botmaker.core.project.managed.ManagedConstants;
import com.botmaker.core.project.managed.ManagedIds;
import com.botmaker.core.project.managed.ManagedMethod;
import com.botmaker.core.project.managed.ManagedSets;
import com.botmaker.core.project.params.JavaParameters;
import com.botmaker.java.index.BotIndex;
import com.botmaker.core.project.vcs.Checkpoints;
import com.botmaker.core.services.BotSources;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Studio's own side of {@link PluginValues} — a plugin's values, read out of the bot's Java and written back
 * to it.
 *
 * <p><b>Nothing here is new capability.</b> The reading and the rewrite are
 * {@link JavaManagedValues}, the walk under them is {@code BotSources} with the open buffer preferred over
 * the disk, and the snapshot is {@link Checkpoints}. What this class adds is the shape: a plugin edits its
 * own value through {@link ValueContext}, the same interface a slot on the canvas and a row in the
 * Parameters window are edited through, so no second way to edit a value exists.
 *
 * <p><b>Every write leaves through {@link #send}</b> and is made by a {@link WritePerformer}: {@link HolderWrites} for
 * a holder the bot lacks, {@link SetWrites} for an open set, {@link MethodWrites} for a method, over {@link BotWrites}.
 *
 * <p><b>An open set</b> ({@code Pictures}) is {@link ManagedSets}' — planned there by binding and compiled as
 * the whole bot, written here after one Project History snapshot. That replaced {@code HostSources}, the
 * token find-and-replace that was the last text rewrite a plugin could ask for (2026-09-28).
 *
 * <p><b>Installed per project, static, one at a time</b>, exactly as {@link HostRuns} is and for the same
 * reason: {@link HostServices} is built ad hoc from a
 * {@code ProjectConfig} at call sites with no {@link ProjectState} in scope, and Studio holds one open
 * project. Between projects a plugin asking gets {@link PluginValues#NONE}, so a window left open over a
 * project the user has since left reads nothing rather than writing into the next one.
 *
 * <p><b>Every id in the project is listed, not only the asking plugin's.</b> The services object is one per
 * project rather than one per plugin, and inventing a per-plugin view would mean deciding here which ids
 * belong to whom — an answer the plugin already has, since it declared them
 * ({@code StudioPlugin.managedValues}). A plugin asks for its own id and gets its own value; nothing is
 * hidden because nothing here is secret.
 */
public final class HostPluginValues implements PluginValues {

    /** The live project's values, or null between projects. */
    private static volatile HostPluginValues current;

    private final ProjectConfig config;
    private final ProjectState state;
    private final StudioServices services;
    /** Where each write goes instead of the bot; null when this instance writes the bot itself. */
    private final Forward forward;
    private final HolderWrites holders;
    private final WritePerformer performer;
    /** Where each set of classes' holder was last found, by the set's id; checked on its file at each use. */
    private final Map<String, WriteSteps.HolderAt> holdersSeen = new ConcurrentHashMap<>();

    /** What a write here tells the host: the undo step it made, and the open file's new text to redraw. */
    public interface Edits extends WriteSteps.Recorded {
        void redraw(String content);
    }

    /**
     * Where a write goes when this instance does not write the bot (plan phase 10c): a client's copy of the plugins
     * sends it to the core, and the core's own copy hands it to the project's one thread. Empty when written, else why
     * not. What a write needs of the plugin's own objects (checking a value, writing it as Java) happens before it goes.
     */
    @FunctionalInterface
    public interface Forward {
        Optional<String> write(PluginWrite write);
    }

    private HostPluginValues(ProjectConfig config, ProjectState state, StudioServices services, Edits edits,
                             Forward forward) {
        this.config = config;
        this.state = state;
        this.services = services;
        this.forward = forward;
        BotWrites writes = new BotWrites(config, state, edits);
        this.holders = new HolderWrites(config, state, writes);
        this.performer = new WritePerformer(config, state, writes, holders);
    }

    /** Values that write the bot themselves, not installed: the one the core performs each {@link PluginWrite} on. */
    public static HostPluginValues writing(ProjectConfig config, ProjectState state, StudioServices services,
                                           Edits edits) {
        return new HostPluginValues(config, state, services, edits, null);
    }

    /**
     * Makes this project's values the ones a plugin reaches, each write going to {@code forward}; reads stay on
     * {@code state}, which mirrors what the writes leave on disk.
     */
    public static synchronized void installForwarding(ProjectConfig config, ProjectState state,
                                                      StudioServices services, Forward forward) {
        if (config == null || state == null || forward == null) {
            clear();
            return;
        }
        current = new HostPluginValues(config, state, services, null, forward);
    }

    /**
     * Makes this project's values the ones a plugin reaches, replacing whatever was installed before.
     *
     * <p>{@code edits} may be null — it is only how the history hears a write and the editor is told to redraw a
     * file rewritten underneath it, and a headless caller has neither.
     */
    public static synchronized void install(ProjectConfig config, ProjectState state,
                                            StudioServices services, Edits edits) {
        if (config == null || state == null) {
            clear();
            return;
        }
        current = new HostPluginValues(config, state, services, edits, null);
    }

    /** No project: a plugin asking now gets {@link PluginValues#NONE}. */
    public static synchronized void clear() {
        current = null;
    }

    /**
     * Writes {@code plan}, a rename of a plugin's open-set member the author asked for ({@code ManagedRenames}), as
     * the host writes one a plugin asks for: past the lock on the plugin's file, one step of the history. Empty when
     * written, else why not.
     */
    public static Optional<String> applyRename(Refactor.Planned plan) {
        HostPluginValues live = current;
        if (live == null) return Optional.of(NO_SOURCE_TREE);
        return live.send(PluginWrite.ApplyPlan.of(plan));
    }

    /**
     * Every write a plugin asks this instance for, the one way out (2026-11-21): sent where this instance sends its
     * writes, or made here by {@link #perform}. Empty when written, else why not.
     */
    private Optional<String> send(PluginWrite write) {
        List<PluginWrite> queue = QUEUE.get();
        if (queue != null) {
            queue.add(write);
            return Optional.empty();
        }
        return forward != null ? forward.write(write) : perform(write);
    }

    /** The writes of the together open on this thread, queued rather than made; null when none is. */
    private static final ThreadLocal<List<PluginWrite>> QUEUE = new ThreadLocal<>();

    /**
     * Runs {@code writes} and answers every write they asked of the installed values on this thread, queued in order and
     * not made: each answered empty, as taken (2026-11-21, {@code History.together}). Inside a run already queuing they
     * join it, and this answers empty. When {@code writes} throws, nothing queued is kept and the exception is the
     * caller's.
     */
    public static List<PluginWrite> queued(Runnable writes) {
        if (writes == null) return List.of();
        if (QUEUE.get() != null) {
            writes.run();
            return List.of();
        }
        List<PluginWrite> queue = new ArrayList<>();
        QUEUE.set(queue);
        try {
            writes.run();
        } finally {
            QUEUE.remove();
        }
        return List.copyOf(queue);
    }

    /**
     * {@code writes} made as one step named {@code label}, or part of the step open on this thread, or none of them: at
     * the first refused, every file they touched is put back as it was, a file they created deleted, and that refusal
     * is the answer. A write that throws is taken back the same way, and the exception is the caller's. On the
     * project's one thread, as {@link #perform} is.
     */
    public Optional<String> performAll(String label, List<PluginWrite> writes) {
        return performer.performAll(label, writes);
    }

    /**
     * {@code writes} as the installed values' together (2026-11-21): queued, then made whole by values that write the
     * bot, or sent one by one, up to the first refused, by values that forward. Empty when every write was made.
     */
    public static Optional<String> together(String label, Runnable writes) {
        List<PluginWrite> queued = queued(writes);
        if (queued.isEmpty()) return Optional.empty();
        HostPluginValues live = current;
        if (live == null) return Optional.of(NO_SOURCE_TREE);
        if (live.forward == null) return live.performAll(label, queued);
        for (PluginWrite write : queued) {
            Optional<String> refused = live.forward.write(write);
            if (refused.isPresent()) return refused;
        }
        return Optional.empty();
    }

    /**
     * {@code write}, which another copy of the plugins asked for, made on the bot by this instance, as the contract's
     * operation it stands for; empty when written, else why not. On the project's one thread. The only code that
     * writes the bot for a plugin: this instance's own writes reach it through {@link #send}.
     */
    public Optional<String> perform(PluginWrite write) {
        return performer.perform(write);
    }

    /** A value a plugin's window changed, sent as every write is; a refusal said. */
    private void changed(PluginWrite write) {
        send(write).ifPresent(HostRuns::status);
    }

    private static MethodWrite methodWrite(Optional<String> refused, String className, String method) {
        return refused.isPresent() ? new MethodWrite(null, refused.get()) : new MethodWrite(className + "::" + method,
                null);
    }

    /** The live values, or {@link PluginValues#NONE} when no project is open. Never null. */
    public static PluginValues live() {
        HostPluginValues live = current;
        return live == null ? PluginValues.NONE : live;
    }

    @Override
    public List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (ManagedMethod value : JavaManagedValues.scan(config, state)) ids.add(value.id());
        return List.copyOf(ids);
    }

    /**
     * The value behind {@code id}, or empty when there is none a plugin may edit.
     *
     * <p>A method that is read-only — hand-edited, or typed with something nothing registers — answers
     * empty, which is the contract's three sentences collapsed into the one answer a plugin can act on. The
     * value itself is untouched and the user has lost nothing; what to tell them about it is the host's, and
     * {@code ManagedMethod.note} is the sentence.
     */
    public Optional<ValueContext> open(String id) {
        Optional<ManagedMethod> found = JavaManagedValues.find(config, state, id);
        if (found.isEmpty() || !found.get().editable()) return Optional.empty();
        ManagedMethod value = found.get();
        return Optional.of(HostValueContext.of(value.form(), value.expression(), services,
                expression -> changed(new PluginWrite.SetValue(id, expression)), this::constants));
    }

    /**
     * Writes the holder of {@code id} when the project has none ({@link ManagedHolders}), never over a file
     * that exists; when the bot has the holder but not the value, adds the value to it (2026-10-12: a
     * {@code Sdk.java} an older SDK wrote had no {@code run()}, and the flow editor had nowhere to save). The
     * declaring plugin is found among the bound ones by the value id it declared.
     */
    public Optional<String> create(String id) {
        if (id == null || id.isBlank()) return Optional.of("No value was named.");
        // Already there: nothing to send.
        if (JavaManagedValues.find(config, state, id).isPresent()) return Optional.empty();
        return send(new PluginWrite.Create(id));
    }

    /**
     * Writes every holder a bound plugin declares and the open project lacks, and answers the files written
     * (project-relative). Called on every bind (2026-09-26): a plugin's managed file exists the
     * moment the plugin is in the project, rather than when the user finds the button that asks for it.
     *
     * <p>The rules {@link #create} keeps, plus two. A holder is <b>left alone</b> when any value it holds is
     * already declared anywhere in the bot, or when any source file already carries its class name — a user
     * who moved {@code Sdk.java} to another package has one, and a second would not compile. And a project
     * open read-only (an installed bot) is never written. Never over a file, never into {@code main}.
     *
     * <p>One edit inside a file that exists (2026-10-11): a plugin's parameter ({@code ManagedValue.param}) is
     * kept as declared in the holder the bot has — written when missing, marked when a field of its name predates
     * the declaration, its {@code @Param} members refreshed — after a safety snapshot.
     */
    public static List<String> createMissing() {
        HostPluginValues live = current;
        return live == null ? List.of() : live.missing();
    }

    /** {@link #createMissing} on this project's values; nothing when they do not write the bot themselves. */
    public List<String> missing() {
        if (forward != null || state.isReaderMode()) return List.of();
        return holders.writeMissing(this);
    }

    // ── the contract's operations, by declaration: each the spelled one below ────────────────────────────

    /**
     * A plugin's parameter is a marked method of its group in {@code params/} (2026-11-04), opened and written as
     * any method value is: what it returns is this computer's value, and its starting copy is never touched.
     */
    @Override
    public Optional<ValueContext> open(ManagedValue<?> value) {
        // A value of this PC is never the bot's: StudioServices.hostValues() opens it.
        return value == null || value.onHost() ? Optional.empty() : open(value.id());
    }

    /** The bot's managed constants as they stand now, which a value of one of them is read and written as. */
    private List<ManagedConstants.Constant> constants() {
        return ManagedConstants.scan(config, state);
    }

    private BotIndex index() {
        return BotSources.index(config, state);
    }

    @Override
    public Optional<String> create(ManagedValue<?> value) {
        if (value != null && value.onHost()) return Optional.of(HostValues.notTheBots(value));
        // A parameter the bot already declares is there to open, or written by hand and left alone; only a missing
        // one is written. create(String) finds methods only.
        if (value instanceof ManagedParam<?>) {
            Optional<ManagedMethod> found = JavaManagedValues.find(config, state, value.id());
            if (found.isPresent() && !found.get().editable()) {
                return Optional.of(ManagedIds.spelled(value.id(), PluginHost.managedValues())
                        + " is written by hand, so BotMaker leaves it alone.");
            }
        }
        return create(value == null ? null : value.id());
    }

    @Override
    public List<Member> members(ManagedValue<?> set) {
        if (set == null) return List.of();
        return members(set.id()).stream().flatMap(spelled -> Member.read(spelled).stream()).toList();
    }

    @Override
    public Optional<ValueContext> open(ManagedValue<?> set, Member member) {
        if (set == null || member == null) return Optional.empty();
        if (set instanceof ManagedClasses<?> classes && !member.grouped()) return openSetup(classes, member.name());
        return open(set.id(), member.toString());
    }

    /** What the setup of the class whose constant is {@code constant} returns, as a value of the setup's type. */
    private Optional<ValueContext> openSetup(ManagedClasses<?> set, String constant) {
        String setup = set.setup().getName();
        // A class found without a setup of one return is found all the same: the whole bot would say no setup too.
        return seenSetup(set.id(), constant, setup)
                .or(() -> NestedClasses.named(index(), set.id(), constant, PluginHost.grammar(), setup)
                        .map(nested -> {
                            holdersSeen.put(set.id(), new WriteSteps.HolderAt(
                                    nested.file().toAbsolutePath().normalize(), nested.holder()));
                            return nested;
                        }))
                .filter(nested -> nested.setup() != null)
                .map(nested -> HostValueContext.of(set.setup().getGenericReturnType(), nested.setup(), services,
                        expression -> changed(new PluginWrite.SetSetup(set.id(), constant, expression)),
                        this::constants));
    }

    /**
     * {@code constant}'s class read from the one file its set's holder was last found in, while that holder is still
     * there, marked and a set of classes; else empty. Each write changes the bot, so the whole-bot index was built
     * again for every read of a run of writes: half of the Screens map's ⋯ ▸ <i>Tidy</i> (2026-11-21).
     */
    private Optional<NestedClasses.Nested> seenSetup(String id, String constant, String setup) {
        WriteSteps.HolderAt at = holdersSeen.get(id);
        if (at == null) return Optional.empty();
        return BotSources.sourceOf(config, state, at.file())
                .flatMap(source -> NestedClasses.namedIn(at.file(), source, id, at.holder(), constant,
                        PluginHost.grammar(), setup, PluginHost.managedValues()));
    }

    @Override
    public Optional<String> addClass(ManagedValue<?> declared, String member, Object setup) {
        if (!(declared instanceof ManagedClasses<?> set)) {
            return Optional.of("No plugin in this project declares that set as a set of classes.");
        }
        if (setup == null) return Optional.of("No setup was given for " + member + ".");
        Class<?> type = set.setup().getReturnType();
        if (!type.isInstance(setup)) {
            return Optional.of(member + "'s setup is " + DeclaredValues.article(setup.getClass()) + ", not "
                    + DeclaredValues.article(type) + ".");
        }
        ManagedConstants.Lookup names = new ManagedConstants.Lookup(constants(), PluginHost.grammar());
        Optional<JavaValue> written = PluginHost.grammar().write(setup.getClass(), setup, names);
        if (written.isEmpty()) {
            return Optional.of("No plugin in this project declares " + type.getSimpleName() + ", so " + member
                    + " cannot be written.");
        }
        return send(new PluginWrite.AddClass(set.id(), member, written.get()));
    }

    @Override
    public <E> Optional<String> add(ManagedValue<E> set, Member member, E value) {
        if (set == null || member == null) return Optional.of("No constant was named.");
        return add(set.id(), member.toString(), value);
    }

    @Override
    public List<Use> uses(ManagedValue<?> set, Member member) {
        return set == null || member == null ? List.of() : uses(set.id(), member.toString());
    }

    @Override
    public Optional<String> rename(ManagedValue<?> set, Member member, String newName) {
        if (set == null || member == null) return Optional.of("No constant was named.");
        return rename(set.id(), member.toString(), newName);
    }

    @Override
    public Optional<String> repoint(ManagedValue<?> set, Member member, Member replacement, String note) {
        if (set == null || member == null || replacement == null) return Optional.of("No constant was named.");
        return repoint(set.id(), member.toString(), replacement.toString(), note);
    }

    @Override
    public Optional<String> remove(ManagedValue<?> set, Member member) {
        if (set == null || member == null) return Optional.of("No constant was named.");
        return remove(set.id(), member.toString());
    }

    @Override
    public Optional<String> moveBefore(ManagedValue<?> set, Member member, Member before) {
        if (set == null || member == null || before == null) return Optional.of("No constant was named.");
        return moveBefore(set.id(), member.toString(), before.toString());
    }

    @Override
    public List<String> groups(ManagedValue<?> set) {
        if (set == null) return List.of();
        if (set instanceof ManagedClasses<?>) {
            return NestedClasses.all(index(), set.id(), PluginHost.grammar(), null).stream()
                    .map(NestedClasses.Nested::constant).toList();
        }
        return groups(set.id());
    }

    @Override
    public Optional<String> addGroup(ManagedValue<?> set, String group, List<String> constants) {
        return set == null ? Optional.of("No set was named.") : addGroup(set.id(), group, constants);
    }

    @Override
    public Optional<String> renameGroup(ManagedValue<?> set, String group, String newName) {
        if (set == null) return Optional.of("No set was named.");
        return send(new PluginWrite.RenameGroup(set.id(), group, newName));
    }

    @Override
    public Optional<String> removeGroup(ManagedValue<?> set, String group) {
        if (set == null) return Optional.of("No set was named.");
        return send(new PluginWrite.RemoveGroup(set.id(), group));
    }

    @Override
    public List<String> methods(ManagedValue<?> set, String group) {
        return set == null || group == null ? List.of() : methods(set.id(), group);
    }

    @Override
    public List<String> methods(MethodShape shape) {
        return switch (shape) {
            case VOID -> methods((String) null, null);
            case BOOLEAN -> BotMethods.returning(index(), BotMethods.BOOLEAN);
            case null -> List.of();
        };
    }

    @Override
    public MethodWrite createMethod(ManagedValue<?> set, String group, String className, String method) {
        if (set == null || group == null) return new MethodWrite(null, "No enum was named.");
        return createMethod(set.id(), group, className, method);
    }

    @Override
    public List<String> classes() {
        return BotMethods.classes(index(), config.mainPackageDir().resolve("plugins"));
    }

    @Override
    public MethodWrite createMethod(MethodShape shape, String className, String method) {
        return switch (shape) {
            case VOID -> createMethod((String) null, null, className, method);
            case BOOLEAN -> methodWrite(send(new PluginWrite.CreateBooleanMethod(className, method)), className,
                    method);
            case null -> new MethodWrite(null, "No kind of method was named.");
        };
    }

    // ── a call written into the bot's method ──────────────────────────────────────────────────────────────

    @Override
    public Optional<String> appendCall(String method, Ref call, Argument... arguments) {
        if (call == null) return Optional.of("No call was named.");
        Executable resolved;
        try {
            resolved = Ref.resolve(call);
        } catch (RuntimeException notAReference) {
            return Optional.of("The call is not a method reference BotMaker can read.");
        }
        if (!(resolved instanceof Method target) || !Modifier.isStatic(target.getModifiers())
                || !Modifier.isPublic(target.getModifiers())) {
            return Optional.of(resolved.getName() + " is no public static method, so nothing was written.");
        }
        Argument[] given = arguments == null ? new Argument[0] : arguments;
        Type[] parameters = target.getGenericParameterTypes();
        if (given.length != parameters.length) {
            return Optional.of(target.getName() + " takes " + parameters.length + " argument(s), not "
                    + given.length + ".");
        }
        ManagedConstants.Lookup names = new ManagedConstants.Lookup(constants(), PluginHost.grammar());
        List<JavaValue> written = new ArrayList<>();
        for (int i = 0; i < given.length; i++) {
            Argument argument = given[i];
            Optional<JavaValue> value;
            if (argument.member() != null) {
                value = names.member(argument.set().id(), argument.member().toString())
                        .map(ManagedConstants.Lookup::reference);
                if (value.isEmpty()) return Optional.of("The bot declares no " + argument.member() + " to name.");
            } else {
                value = ConstantValues.write(PluginHost.grammar(), this::constants, parameters[i], argument.value());
                if (value.isEmpty()) return Optional.of(ConstantValues.refusal(PluginHost.grammar(), parameters[i],
                        argument.value()));
            }
            written.add(value.get());
        }
        PluginWrite.AppendCall append = new PluginWrite.AppendCall(method,
                target.getDeclaringClass().getCanonicalName(), target.getName(), written);
        return send(append);
    }

    // ── an open set: ManagedSets planned, written by SetWrites ────────────────────────────────────────────

    public List<String> members(String id) {
        return ManagedSets.members(index(), id, PluginHost.grammar()).stream()
                .map(ManagedSets.Member::memberName).toList();
    }

    // ── a set of nested enums ─────────────────────────────────────────────────────────────────────────────

    public List<String> groups(String id) {
        return ManagedSets.groups(index(), id).stream().map(ManagedSets.Group::name).toList();
    }

    public Optional<String> addGroup(String id, String group, List<String> constants) {
        return send(new PluginWrite.AddGroup(id, group, constants));
    }

    // ── the bot's methods ─────────────────────────────────────────────────────────────────────────────────

    public List<String> methods(String id, String group) {
        BotIndex index = index();
        if (id == null && group == null) return BotMethods.returning(index, null);
        return ManagedSets.group(index, id, group)
                .map(found -> BotMethods.returning(index, MethodWrites.canonical(index, found)))
                .orElse(List.of());
    }

    public MethodWrite createMethod(String id, String group, String className, String method) {
        return methodWrite(send(new PluginWrite.CreateMethod(id, group, className, method)), className, method);
    }

    /**
     * The constant's initialiser as a value. Every managed constant but itself is offered to the context: its
     * value, equal to what it held, would be written as itself, {@code X = Pictures.X}, which holds nothing
     * (2026-11-02, a picture recut at its own place).
     */
    public Optional<ValueContext> open(String id, String member) {
        return ManagedSets.member(index(), id, member, PluginHost.grammar())
                .filter(found -> found.initializer() != null)
                // The bot's constants too (2026-10-12): a flow names its activities as Activities.Collect.class,
                // which nothing but them spells, so without them every flow with an activity was refused.
                .map(found -> HostValueContext.of(found.form(), found.initializer(), services,
                        expression -> changed(new PluginWrite.SetMember(id, member, expression)),
                        () -> constants().stream().filter(c -> !isItself(c, found)).toList()));
    }

    /** Whether {@code constant} is {@code member}: its field, of its class or of its nested enum. */
    static boolean isItself(ManagedConstants.Constant constant, ManagedSets.Member member) {
        return member.name().equals(constant.field()) && constant.simpleOwner().equals(
                member.group() == null ? member.className() : member.className() + "." + member.group());
    }

    public Optional<String> add(String id, String member, Object value) {
        if (value == null) return Optional.of("No value was given for " + member + ".");
        Optional<ManagedOpenSet<?>> set = DeclaredValues.openSet(PluginHost.managedValues(), id);
        Optional<String> stranger = set.flatMap(declared -> DeclaredValues.notAnElement(declared, member, value));
        if (stranger.isPresent()) return stranger;
        // An enum's constant is its name: the value was only checked, and nothing of it is written.
        if (set.filter(declared -> declared instanceof ManagedOpenSet.OneEnum<?>
                || declared instanceof ManagedOpenSet.EnumGroups<?>).isPresent()) {
            return send(new PluginWrite.AddConstant(id, member));
        }
        Class<?> type = value instanceof Enum<?> constant ? constant.getDeclaringClass() : value.getClass();
        // Each part another constant holds is written as that constant: a screen's Way.of(HOME, …) names HOME, which
        // nothing but the bot's own names can write (2026-10-14).
        ManagedConstants.Lookup names = new ManagedConstants.Lookup(constants(), PluginHost.grammar());
        Optional<JavaValue> initializer = PluginHost.grammar().write(type, value, names);
        if (initializer.isEmpty()) {
            return Optional.of("No plugin in this project declares " + type.getSimpleName() + ", so "
                    + member + " cannot be written.");
        }
        // A link the writer could not write is left out of the call; a new record must read back whole, or it is
        // not added half-written (2026-10-15, a screen's way in lost).
        if (value instanceof Record && !PluginHost.grammar().read(type, initializer.get().written(), names)
                .map(value::equals).orElse(false)) {
            return Optional.of(member + " cannot be written whole: a part of it is of a type nothing writes, or "
                    + "names a constant the bot does not have. Nothing was added.");
        }
        return send(new PluginWrite.Add(id, member, type.getName(), initializer.get()));
    }

    public List<Use> uses(String id, String member) {
        BotIndex index = index();
        return ManagedSets.member(index, id, member, PluginHost.grammar())
                .map(found -> ManagedSets.uses(index, found).stream()
                        .map(use -> new Use(use.file(), use.line(), use.text())).toList())
                .orElse(List.of());
    }

    public Optional<String> rename(String id, String member, String newName) {
        return send(new PluginWrite.Rename(id, member, newName));
    }

    /** The mark is written only where the bot compiles {@code @Refactor}; elsewhere the repoint goes unmarked. */
    public Optional<String> repoint(String id, String member, String replacement, String note) {
        return send(new PluginWrite.Repoint(id, member, replacement, note));
    }

    public Optional<String> remove(String id, String member) {
        return send(new PluginWrite.Remove(id, member));
    }

    public Optional<String> moveBefore(String id, String member, String before) {
        return send(new PluginWrite.MoveBefore(id, member, before));
    }

    // ── a during-a-run parameter ─────────────────────────────────────────────────────────────────────────

    @Override
    public List<RunParameter> runParameters() {
        return JavaParameters.duringRun(config, state, PluginHost.grammar());
    }

    @Override
    public Optional<String> addRunParameter(ParameterWrite write) {
        PluginWrite.AddRunParameter add;
        try {
            add = PluginWrite.AddRunParameter.of(write);
        } catch (IllegalArgumentException e) {
            // A mark member of a kind no write carries: refused, never thrown at the plugin.
            return Optional.of(e.getMessage());
        }
        return send(add);
    }

    @Override
    public Optional<String> forgetRunParameter(String group, String name) {
        return send(new PluginWrite.ForgetRunParameter(group, name));
    }
}
