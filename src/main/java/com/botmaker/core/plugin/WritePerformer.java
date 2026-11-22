// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.plugin;

import com.botmaker.core.project.FileJournal;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.project.managed.BotMethods;
import com.botmaker.core.project.managed.JavaManagedValues;
import com.botmaker.core.project.managed.ManagedMethod;
import com.botmaker.core.project.managed.ManagedSets;
import com.botmaker.core.project.managed.NestedClasses;
import com.botmaker.core.project.params.JavaParameterEdits;
import com.botmaker.core.project.params.JavaParameters;
import com.botmaker.plugin.api.source.ManagedClasses;
import com.botmaker.plugin.api.source.ParameterWrite;

import java.util.List;
import java.util.Optional;

/** Makes each {@link PluginWrite} on the bot, one at a time or a together's as one step. */
final class WritePerformer {

    private final ProjectConfig config;
    private final ProjectState state;
    private final BotWrites writes;
    private final HolderWrites holders;
    private final SetWrites sets;
    private final MethodWrites methods;

    WritePerformer(ProjectConfig config, ProjectState state, BotWrites writes, HolderWrites holders) {
        this.config = config;
        this.state = state;
        this.writes = writes;
        this.holders = holders;
        this.sets = new SetWrites(config, state, writes);
        this.methods = new MethodWrites(config, writes);
    }

    /** {@link HostPluginValues#performAll}. */
    Optional<String> performAll(String label, List<PluginWrite> queued) {
        if (queued == null || queued.isEmpty()) return Optional.empty();
        return writes.step(label, () -> {
            FileJournal journal = new FileJournal();
            Optional<String> refused;
            try {
                refused = journal.keeping(() -> {
                    for (PluginWrite write : queued) {
                        Optional<String> refusal = perform(write);
                        if (refusal.isPresent()) return refusal;
                    }
                    return Optional.<String>empty();
                });
            } catch (RuntimeException | Error failed) {
                takeBack(journal);
                throw failed;
            }
            if (refused.isEmpty()) return refused;
            List<String> notPutBack = takeBack(journal);
            return Optional.of(refused.get() + (notPutBack.isEmpty() ? ""
                    : " The writes before it could not all be taken back: " + String.join(", ", notPutBack)
                    + " still hold them."));
        });
    }

    /**
     * A together's files put back as {@code journal} heard them, the open step told so it records only what stayed, and
     * the open file redrawn when it was one of them; the files that could not be put back, by name.
     */
    private List<String> takeBack(FileJournal journal) {
        List<String> notPutBack = journal.restore(state);
        WriteSteps.Step step = WriteSteps.open();
        if (step != null) step.restored(journal.before());
        writes.redraw(journal.before().keySet());
        return notPutBack;
    }

    /** {@link HostPluginValues#perform}. */
    Optional<String> perform(PluginWrite write) {
        return switch (write) {
            case PluginWrite.SetValue set -> {
                Optional<ManagedMethod> found = JavaManagedValues.find(config, state, set.id());
                if (found.isEmpty() || !found.get().editable()) {
                    yield Optional.of(DeclaredValues.notEditable(set.id()));
                }
                writes.write(found.get(), writes.imported(set.value()));
                yield Optional.empty();
            }
            case PluginWrite.SetMember set -> {
                Optional<ManagedSets.Member> found = ManagedSets.member(writes.index(), set.id(), set.member(),
                        PluginHost.grammar()).filter(member -> member.initializer() != null);
                if (found.isEmpty()) yield Optional.of(DeclaredValues.missing(set.id(), set.member()));
                sets.setInitializer(found.get(), writes.imported(set.value()));
                yield Optional.empty();
            }
            case PluginWrite.SetSetup set -> DeclaredValues.classes(set.id())
                    .map(classes -> sets.setSetup(classes, set.constant(), set.value()))
                    .orElseGet(() -> Optional.of(DeclaredValues.notClasses(set.id())));
            case PluginWrite.Create create -> holders.create(create.id());
            case PluginWrite.AddClass add -> DeclaredValues.classes(add.id())
                    .map(classes -> writes.apply(NestedClasses.add(writes.index(), classes.id(), add.member(),
                            classes.type(), classes.setup(), writes.imported(add.setup()), PluginHost.grammar())))
                    .orElseGet(() -> Optional.of(DeclaredValues.notClasses(add.id())));
            case PluginWrite.AddConstant add ->
                    writes.apply(ManagedSets.add(writes.index(), add.id(), add.member(), null, null));
            case PluginWrite.Add add -> {
                Class<?> type;
                try {
                    type = Class.forName(add.type(), false, PluginHost.classLoader());
                } catch (ClassNotFoundException e) {
                    yield Optional.of("No plugin in this project has " + add.type() + ", so " + add.member()
                            + " cannot be written.");
                }
                yield writes.apply(ManagedSets.add(writes.index(), add.id(), add.member(), type,
                        writes.imported(add.value())));
            }
            case PluginWrite.Rename rename -> sets.rename(rename.id(), rename.member(), rename.newName());
            case PluginWrite.Repoint repoint -> sets.repoint(repoint.id(), repoint.member(), repoint.replacement(),
                    repoint.note());
            case PluginWrite.Remove remove -> sets.remove(remove.id(), remove.member());
            case PluginWrite.MoveBefore move -> sets.moveBefore(move.id(), move.member(), move.before());
            case PluginWrite.AddGroup add -> sets.addGroup(add.id(), add.group(), add.constants());
            case PluginWrite.RenameGroup rename -> DeclaredValues.declared(rename.id())
                    .map(set -> sets.renameGroup(set, rename.group(), rename.newName()))
                    .orElseGet(() -> sets.renameGroup(rename.id(), rename.group(), rename.newName()));
            case PluginWrite.RemoveGroup remove -> DeclaredValues.declared(remove.id())
                    .map(set -> sets.removeGroup(set, remove.group()))
                    .orElseGet(() -> sets.removeGroup(remove.id(), remove.group()));
            case PluginWrite.CreateMethod create -> Optional.ofNullable(
                    methods.create(create.id(), create.group(), create.className(), create.method()).refusal());
            case PluginWrite.CreateBooleanMethod create -> Optional.ofNullable(
                    methods.createBoolean(create.className(), create.method()).refusal());
            case PluginWrite.AppendCall call -> writes.apply(BotMethods.appendCall(writes.index(), call.method(),
                    call.owner(), call.name(), call.arguments().stream().map(writes::imported).toList()));
            case PluginWrite.AddRunParameter add -> {
                ParameterWrite parameter;
                try {
                    parameter = add.write(PluginHost.classLoader(), id -> DeclaredValues.classes(id).orElseThrow(() ->
                            new IllegalArgumentException(DeclaredValues.notClasses(id))));
                } catch (ClassNotFoundException e) {
                    yield Optional.of("No plugin in this project has " + e.getMessage() + ", so " + add.name()
                            + " cannot be written.");
                } catch (IllegalArgumentException e) {
                    yield Optional.of(e.getMessage());
                }
                yield writes.written(() -> JavaParameters.addDuringRun(config, state, parameter, this::classNamed,
                        PluginHost.grammar()));
            }
            case PluginWrite.ForgetRunParameter forget -> writes.written(() -> JavaParameters.forgetDuringRun(config,
                    state, forget.group(), forget.name(), PluginHost.grammar()));
            case PluginWrite.ApplyPlan plan -> writes.apply(plan.plan());
        };
    }

    /** The class of a constant of the plugin's set, as {@link JavaParameterEdits} writes it. */
    private Optional<JavaParameterEdits.ClassName> classNamed(ParameterWrite.ClassOf classOf) {
        ManagedClasses<?> set = classOf.classes();
        Optional<NestedClasses.Nested> nested = NestedClasses.named(writes.index(), set.id(),
                classOf.constant().name(), PluginHost.grammar(), set.setup().getName());
        if (nested.isEmpty()) return Optional.empty();
        String holder = nested.get().holder();
        return BotMethods.topLevelTypes(writes.index()).stream()
                .filter(type -> type.equals(holder) || type.endsWith("." + holder)).findFirst()
                .map(top -> new JavaParameterEdits.ClassName(top, holder + "." + nested.get().className()));
    }
}
