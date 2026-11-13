package com.botmaker.core.plugin;

import com.botmaker.core.project.managed.JavaManagedEdits;
import com.botmaker.core.project.managed.JavaManagedSource;
import com.botmaker.core.project.managed.ManagedHolders;
import com.botmaker.core.project.managed.ManagedIds;
import com.botmaker.core.project.managed.ManagedMethod;
import com.botmaker.core.project.managed.ManagedRepairs;
import com.botmaker.core.project.params.BotRecords;
import com.botmaker.core.project.vcs.VersionOrigin;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.grammar.ShortNames;
import com.botmaker.java.grammar.ValueGrammar;
import com.botmaker.java.parse.BotParser;
import com.botmaker.plugin.api.History;
import com.botmaker.plugin.api.PluginState;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.slot.ValueContext;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.api.source.PluginValues;
import com.botmaker.plugin.host.PluginCoordinate;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One plugin's values of this PC ({@link com.botmaker.plugin.api.managed.HostHolder}), read and written as Java in its
 * host folder, {@code <config>/plugins/<group>/<artifact>/}: what {@link StudioServices#hostValues()} answers. The folder
 * is a project the plugin manages ({@link HostProject}): its classes in {@code src/main/java/<group>/<artifact>/}, its
 * version in {@code pom.xml}, its history git; an older folder is moved into that layout before its first write.
 *
 * <p><b>The bot's reading and writing, over another folder.</b> A value is read by {@link JavaManagedSource}, its
 * expression replaced by {@link JavaManagedEdits}, a missing holder planned by {@link ManagedHolders#planIn} — the
 * same pieces {@link HostPluginValues} puts a bot's values through. What differs is what the folder is: one plugin's,
 * every bot's, in no version, publish or {@code .bmk}, and on no run's classpath. Its history is its own git, not the
 * bot's: each accepted write is one commit there, labelled with the value it wrote, and a refused one commits nothing.
 *
 * <p><b>Each write is compiled</b> with the folder's other classes against the plugin's classpath; one that would not
 * compile is put back and refused with javac's sentence. A host with no compiler, or no classpath for the plugin,
 * writes unchecked, and says so once. Every instance over one folder writes under one lock.
 *
 * <p><b>Only a newer plugin moves the folder on</b> (the user's call, 2026-11-12: the newest version on this PC serves
 * host data alone). The pom records the version that last wrote; an older one reads and is refused
 * every write. A newer one, before its first read or write, repairs the folder as a bot's values are on that plugin's
 * upgrade ({@link HostUpgrade}) and records itself.
 *
 * <p>Method values only so far: an open set of this PC is refused with words.
 */
public final class HostValues implements PluginValues {

    private static final Pattern NUMBERS = Pattern.compile("(\\d+)");

    /** The file the other process's writer waits on. */
    static final String LOCK_FILE = ".lock";

    /** One lock per folder: every bot's {@code HostValues} of one plugin writes the same files. */
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    /** Whether an unchecked write was said already, so it is said once. */
    private static final AtomicBoolean UNCHECKED = new AtomicBoolean();

    private final Path folder;
    private final HostProject project;
    private final PluginCoordinate plugin;
    private final String version;
    private final List<ManagedValue<?>> declared;
    private final ValueGrammar grammar;
    private final StudioServices services;
    private final Supplier<List<Path>> classpath;
    private final HostFolderHistory history;

    /** Whether this instance has made the folder a project, or tried to ({@link HostProject#ready}). */
    private volatile boolean readied;

    /** Whether this version has checked the folder against the one recorded ({@link #upToDate}). */
    private volatile boolean checked;

    /**
     * Whether this version's repair of the folder failed: it then never records itself, so the next instance (the
     * next bind, the next start) repairs again rather than taking the folder as repaired.
     */
    private volatile boolean unrepaired;

    /**
     * @param root      the host's config root, under which {@code plugins/} lies
     * @param version   the version of {@code plugin} asking, compared with the one the folder's pom names
     * @param declared  every value the plugin declares; its values of this PC are kept
     * @param classpath the plugin's jar and what it needs, which a write is compiled against
     */
    public HostValues(Path root, PluginCoordinate plugin, String version, List<ManagedValue<?>> declared,
                      ValueGrammar grammar, StudioServices services, Supplier<List<Path>> classpath) {
        this.folder = folder(root, plugin);
        this.project = new HostProject(folder, plugin);
        this.plugin = plugin;
        this.version = version == null ? "" : version;
        this.declared = declared == null ? List.of()
                : declared.stream().filter(Objects::nonNull).filter(ManagedValue::onHost).toList();
        this.grammar = grammar == null ? ValueGrammar.empty() : grammar;
        this.services = services;
        this.classpath = classpath == null ? List::of : classpath;
        // A step is refused as a write is: by an older copy, and while this version's repair has failed.
        this.history = new HostFolderHistory(folder, project, this::locked, this::upToDate,
                () -> unrepaired ? Optional.of("This PC's values of " + plugin + " were not repaired for " + this.version
                        + ", so they are not undone either.") : olderThanFolder(), this::warn);
    }

    /** The undo of these values: ↶ and ↷ over the folder's commits, {@code StudioServices.hostHistory()}. */
    public History history() {
        return history;
    }

    /** {@code plugin}'s host folder under {@code root}: {@code plugins/<group>/<artifact>/}, each {@link PluginState#part}. */
    public static Path folder(Path root, PluginCoordinate plugin) {
        return root.resolve("plugins").resolve(PluginState.part(plugin.groupId()))
                .resolve(PluginState.part(plugin.artifactId()));
    }

    /** The package of {@code plugin}'s host classes, {@code <group>.<artifact>}, each {@link PluginState#part}. */
    public static String packageOf(PluginCoordinate plugin) {
        return PluginState.part(plugin.groupId()) + "." + PluginState.part(plugin.artifactId());
    }

    /** The refusal a bot's values give a value of this PC. */
    public static String notTheBots(ManagedValue<?> value) {
        return ManagedIds.spelled(value.id(), List.of(value)) + " is a value of this PC, not the bot's: "
                + "StudioServices.hostValues() keeps it.";
    }

    /** The folder this reads and writes, the project's root. */
    public Path folder() {
        return folder;
    }

    /** Where the holder classes are, {@code src/main/java/<group>/<artifact>/} under {@link #folder()}. */
    public Path sourcesDir() {
        return project.sources();
    }

    // ── reading ────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public List<String> ids() {
        upToDate();
        return values().stream().map(ManagedMethod::id).toList();
    }

    /** Every marked method in the folder's classes, file by file in name order. */
    List<ManagedMethod> values() {
        List<ManagedMethod> out = new ArrayList<>();
        for (Path file : sources()) {
            String source;
            try {
                source = Files.readString(file);
            } catch (IOException | UncheckedIOException unreadable) {
                continue;
            }
            out.addAll(JavaManagedSource.read(file, source, BotParser.SYNTAX.parse(file, source), grammar,
                    BotRecords.none(), declared));
        }
        return List.copyOf(out);
    }

    private Optional<ManagedMethod> find(String id) {
        return values().stream().filter(value -> value.id().equals(id)).findFirst();
    }

    /** The project's holder classes, in name order; none before it is made. */
    private List<Path> sources() {
        Path dir = project.classes();
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).filter(file -> file.toString().endsWith(".java"))
                    .sorted().toList();
        } catch (IOException | UncheckedIOException unreadable) {
            return List.of();
        }
    }

    /**
     * The value of this PC behind {@code value}, or empty: a bot's value, one not written yet (its holder deleted by
     * hand included: {@link #create} writes it again), or one written by hand.
     */
    @Override
    public Optional<ValueContext> open(ManagedValue<?> value) {
        if (value == null || !value.onHost()) return Optional.empty();
        upToDate();
        Optional<ManagedMethod> found = find(value.id());
        if (found.isEmpty() || !found.get().editable()) return Optional.empty();
        ManagedMethod method = found.get();
        return Optional.of(HostValueContext.of(method.form(), grammar, method.expression(), services,
                expression -> set(method, expression).ifPresent(this::warn)));
    }

    // ── writing ────────────────────────────────────────────────────────────────────────────────────────────

    /** Writes {@code value}'s holder when the folder lacks it, or its method when the holder lacks that. */
    @Override
    public Optional<String> create(ManagedValue<?> value) {
        if (value == null) return Optional.of("No value was named.");
        if (!value.onHost()) {
            return Optional.of(ManagedIds.spelled(value.id(), List.of(value)) + " is the bot's value, not this "
                    + "PC's: StudioServices.pluginValues() keeps it.");
        }
        if (!(value instanceof com.botmaker.plugin.api.source.ManagedMethod<?> method)) {
            return Optional.of(ManagedIds.spelled(value.id(), List.of(value)) + " is an open set; this PC keeps "
                    + "method values only so far.");
        }
        upToDate();
        return told(locked(() -> created(value, method)));
    }

    /** {@code written}, after telling the history's listeners of a write that passed: outside the lock, so one may undo. */
    private Optional<String> told(Optional<String> written) {
        if (written.isEmpty()) history.changed();
        return written;
    }

    /** {@link #create}'s write, under the folder's lock. */
    private Optional<String> created(ManagedValue<?> value, com.botmaker.plugin.api.source.ManagedMethod<?> method) {
        if (find(value.id()).isPresent()) return Optional.empty();
        Optional<String> older = olderThanFolder();
        if (older.isPresent()) return older;
        Path dir = project.classes();
        Path file = dir.resolve(value.holder() + ".java");
        String source;
        if (Files.exists(file)) {
            try {
                String before = Files.readString(file);
                source = ManagedHolders.withMethod(before, value.holder(), method, grammar);
                if (source.equals(before)) {
                    return Optional.of("BotMaker cannot write a starting value for "
                            + ManagedIds.spelled(value.id(), List.of(value)) + " into " + file.getFileName() + ".");
                }
            } catch (IOException | UncheckedIOException e) {
                return Optional.of("Could not read " + file + ": " + e.getMessage());
            }
        } else {
            ManagedHolders.Plan plan = ManagedHolders.planIn(dir, dir + File.separator, packageOf(plugin),
                    plugin, value, declared, grammar);
            if (plan instanceof ManagedHolders.Plan.Refused refused) return Optional.of(refused.reason());
            source = ((ManagedHolders.Plan.Write) plan).source();
        }
        return write(file, source, "Added " + named(value.id()));
    }

    /** {@code method}'s expression replaced by {@code expression}: empty when written, else why not. */
    private Optional<String> set(ManagedMethod method, JavaValue expression) {
        upToDate();
        return told(locked(() -> setLocked(method, expression)));
    }

    // ── upgrade ────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Repairs the folder once when this version is newer than the one recorded ({@link HostUpgrade}), then records
     * it. Called before any read or write, outside {@link #locked}, which it takes itself.
     */
    private void upToDate() {
        if (checked) return;
        // An older folder is moved before it is read, so the first read already sees the project.
        if (!readied && project.isLegacy()) locked(Optional::empty).ifPresent(this::warn);
        String recorded = recorded();
        if (recorded.isEmpty() || version.isEmpty() || compare(recorded, version) >= 0) {
            checked = true;
            return;
        }
        Optional<String> failed = locked(this::upgradeLocked);
        // Tried once per instance whatever happens: a repair that fails is said once, not on every read.
        checked = true;
        if (failed.isPresent()) {
            unrepaired = true;
            warn(failed.get());
        }
    }

    /** {@link #upToDate}'s repair, under the folder's lock; empty when done, else why not. */
    private Optional<String> upgradeLocked() {
        String from = recorded();
        // Another instance or process moved the folder on while this one waited for the lock.
        if (compare(from, version) >= 0) return Optional.empty();
        // An older folder whose move failed has no history to keep what the repair replaces: it waits for the move.
        if (project.isLegacy()) {
            return Optional.of("This PC's values of " + plugin.artifactId() + " are not in a project yet, so they were"
                    + " not repaired for " + version + "; they are read as written.");
        }
        Map<Path, String> sources = new LinkedHashMap<>();
        for (Path file : sources()) {
            try {
                sources.put(file, Files.readString(file));
            } catch (IOException | UncheckedIOException unreadable) {
                return Optional.of("Could not read " + file + ", so this PC's values of " + plugin.artifactId()
                        + " were not repaired for " + version + ": " + unreadable.getMessage());
            }
        }
        ManagedRepairs.Planned planned;
        try {
            planned = HostUpgrade.plan(project.classesRoot(), sources, declared, grammar, classpath.get(), plugin, from, version);
        } catch (RuntimeException e) {
            return Optional.of("This PC's values of " + plugin.artifactId() + " could not be repaired for " + version
                    + ", so they are left as written: " + e);
        }
        // What the repair replaces is committed first: every write is a commit already, but a class changed by hand
        // since is not. A commit that fails is said and the repair goes on, so the folder is never left unrepaired for
        // the want of a backup.
        project.commit(VersionOrigin.SAFETY, "Before the upgrade to " + version).ifPresent(this::warn);
        List<String> failed = new ArrayList<>();
        for (Map.Entry<Path, String> each : planned.rewrites().entrySet()) {
            try {
                Files.writeString(each.getKey(), each.getValue());
            } catch (IOException | UncheckedIOException e) {
                failed.add(each.getKey().getFileName() + ": " + e.getMessage());
            }
        }
        for (Path gone : planned.deleted()) {
            try {
                Files.deleteIfExists(gone);
            } catch (IOException e) {
                failed.add(gone.getFileName() + ": " + e.getMessage());
            }
        }
        String moving = "This PC's values of " + plugin.artifactId() + ", " + from + " to " + version + ": ";
        planned.repaired().forEach(each -> info(moving + each));
        planned.left().forEach(each -> warn(moving + each));
        if (!failed.isEmpty()) {
            return Optional.of("Could not repair this PC's values of " + plugin.artifactId() + " for " + version
                    + ": " + String.join("; ", failed));
        }
        // What the repair kept as written may not compile: said now, since every write is refused until it does.
        compiles().ifPresent(broken -> warn(moving + "the folder does not compile after the repair, so each write is"
                + " refused until " + folder + " is fixed by hand. " + broken));
        record();
        project.commit(VersionOrigin.UPDATE, "Upgraded to " + version).ifPresent(this::warn);
        return Optional.empty();
    }

    private Optional<String> setLocked(ManagedMethod method, JavaValue expression) {
        Optional<String> older = olderThanFolder();
        if (older.isPresent()) return older;
        String before;
        try {
            before = Files.readString(method.file());
        } catch (IOException | UncheckedIOException e) {
            return Optional.of("Could not read " + method.file() + ": " + e.getMessage());
        }
        String after = JavaManagedEdits.setValue(before, method.className(), method.methodName(),
                ShortNames.imported(expression, List.of()));
        if (after.equals(before)) {
            return Optional.of(method.qualified() + " changed since it was opened, so nothing was written.");
        }
        return write(method.file(), after, "Set " + named(method.id()));
    }

    /**
     * {@code work} under the folder's locks: one per folder in this process, whichever instance writes, and a file
     * lock on {@code .lock} for the other process (Studio's copy of a plugin beside the core's).
     */
    private Optional<String> locked(Supplier<Optional<String>> work) {
        Path real;
        try {
            // The real path, so two spellings of one folder (a short 8.3 name, a link) share one monitor.
            real = Files.createDirectories(folder).toRealPath();
        } catch (IOException e) {
            return Optional.of("Could not make " + folder + ": " + e.getMessage());
        }
        synchronized (LOCKS.computeIfAbsent(real, key -> new Object())) {
            try (FileChannel channel = FileChannel.open(real.resolve(LOCK_FILE),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                // Made a project first, under the lock, so two writers never move or seed the folder twice; once per
                // instance. History is not what a write needs: a project that cannot be made is said, and the write
                // goes where the classes are (HostProject.classes).
                if (!readied) {
                    readied = true;
                    project.ready(version).ifPresent(this::warn);
                }
                return work.get();
            } catch (IOException | OverlappingFileLockException e) {
                return Optional.of("Could not lock " + folder + ": " + e.getMessage());
            } catch (LinkageError e) {
                // The serving version's loader closed under it (a project bound an older one): say so, never throw.
                return Optional.of("This PC's values of " + plugin + " cannot be written now (" + e
                        + "); reopening the bot binds the plugin again.");
            }
        }
    }

    /**
     * Writes {@code source} to {@code file}, then compiles the folder: when that fails, puts the old text back and
     * answers javac's sentence; else records this version and commits the change as {@code label}. Under
     * {@link #locked}, so two writers' commits come in the order their writes did.
     */
    private Optional<String> write(Path file, String source, String label) {
        String previous;
        try {
            Files.createDirectories(file.getParent());
            previous = Files.exists(file) ? Files.readString(file) : null;
            Files.writeString(file, source);
        } catch (IOException | UncheckedIOException e) {
            return Optional.of("Could not write " + file + ": " + e.getMessage());
        }
        Optional<String> refused = compiles();
        if (refused.isPresent()) {
            try {
                if (previous == null) Files.deleteIfExists(file);
                else Files.writeString(file, previous);
            } catch (IOException e) {
                return Optional.of(refused.get() + " And " + file + " could not be put back: " + e.getMessage());
            }
            return refused;
        }
        record();
        // Written and checked: history is not what the write needs, so a commit that fails is said, never refused. A
        // batch's writes are committed once, at its end.
        if (!history.batching()) project.commit(VersionOrigin.SAVE, label).ifPresent(this::warn);
        return Optional.empty();
    }

    /** A value's id as a commit names it: its constant alone, {@code GREETING} for {@code …PcValue$Id.GREETING}. */
    static String named(String id) {
        return id.substring(Math.max(id.lastIndexOf('.'), id.lastIndexOf('$')) + 1);
    }

    /** Empty when the folder's classes compile against the plugin's classpath, else javac's first error. */
    private Optional<String> compiles() {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<Path> path = classpath.get();
        if (javac == null || path == null || path.isEmpty()) {
            // A value written through the grammar is well formed; what is lost is the check against the plugin's types.
            if (UNCHECKED.compareAndSet(false, true)) {
                warn((javac == null ? "No Java compiler in this runtime" : "No classpath for " + plugin)
                        + ": this PC's values are written unchecked.");
            }
            return Optional.empty();
        }
        Path out = null;
        try {
            out = Files.createTempDirectory("botmaker-host-values");
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, Locale.ROOT,
                    StandardCharsets.UTF_8)) {
                String joined = String.join(File.pathSeparator, path.stream().map(Path::toString).toList());
                List<String> options = List.of("-proc:none", "-d", out.toString(), "-classpath", joined);
                boolean ok = javac.getTask(Writer.nullWriter(), files, diagnostics, options, null,
                        files.getJavaFileObjectsFromPaths(sources())).call();
                if (ok) return Optional.empty();
                for (Diagnostic<? extends JavaFileObject> each : diagnostics.getDiagnostics()) {
                    if (each.getKind() != Diagnostic.Kind.ERROR) continue;
                    String where = each.getSource() == null ? "" : " (" + Path.of(each.getSource().toUri())
                            .getFileName() + ":" + each.getLineNumber() + ")";
                    return Optional.of("That value would not compile, so nothing was written: "
                            + each.getMessage(Locale.ROOT) + where);
                }
                return Optional.of("That value would not compile, so nothing was written.");
            }
        } catch (IOException | RuntimeException e) {
            return Optional.of("BotMaker could not check that value, so nothing was written: " + e.getMessage());
        } finally {
            deleteQuietly(out);
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(each -> each.toFile().delete());
        } catch (IOException | UncheckedIOException ignored) {
            // A temporary folder left behind costs nothing but space.
        }
    }

    // ── the version ───────────────────────────────────────────────────────────────────────────────────────

    /** The version that last wrote the folder, as its pom names the plugin, or empty. */
    String recorded() {
        return project.version();
    }

    /** Why this version may not write: a newer one wrote the folder last. */
    private Optional<String> olderThanFolder() {
        String recorded = recorded();
        if (compare(recorded, version) <= 0) return Optional.empty();
        return Optional.of(plugin.artifactId() + " " + recorded + " keeps this PC's values; this is " + version
                + ", which never writes over a newer version's.");
    }

    /** Records this version after a write, unless a newer one is recorded. */
    private void record() {
        if (unrepaired || version.isEmpty() || compare(recorded(), version) >= 0) return;
        try {
            project.record(version);
        } catch (IOException | RuntimeException e) {
            warn("Could not record " + version + " in " + folder.resolve(HostProject.POM) + ": " + e.getMessage());
        }
    }

    /**
     * {@code a} against {@code b}, number by number ({@code 1.10.0} after {@code 1.9.2}); a suffix such as
     * {@code -SNAPSHOT} is ignored, and an empty version is older than any.
     */
    static int compare(String a, String b) {
        List<Integer> left = numbers(a);
        List<Integer> right = numbers(b);
        for (int i = 0; i < Math.max(left.size(), right.size()); i++) {
            int x = i < left.size() ? left.get(i) : 0;
            int y = i < right.size() ? right.get(i) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        int known = Integer.compare(left.isEmpty() ? 0 : 1, right.isEmpty() ? 0 : 1);
        if (known != 0) return known;
        // A pre-release (1.2.0-SNAPSHOT) comes before its release, so a dev build never writes over 1.2.0's folder.
        return Boolean.compare(!suffixed(a), !suffixed(b));
    }

    private static boolean suffixed(String version) {
        return version != null && version.contains("-");
    }

    private static List<Integer> numbers(String version) {
        List<Integer> out = new ArrayList<>();
        if (version == null) return out;
        String head = version.split("[-+]", 2)[0];
        Matcher matched = NUMBERS.matcher(head);
        while (matched.find()) {
            try {
                out.add(Integer.parseInt(matched.group(1)));
            } catch (NumberFormatException tooLong) {
                out.add(Integer.MAX_VALUE);
            }
        }
        return out;
    }

    private void warn(String message) {
        if (services != null) services.log().warn(message);
        else System.err.println("[host values] " + message);
    }

    private void info(String message) {
        if (services != null) services.log().info(message);
        else System.err.println("[host values] " + message);
    }
}
