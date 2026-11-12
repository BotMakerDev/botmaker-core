package com.botmaker.core.plugin;

import com.botmaker.core.project.managed.JavaManagedEdits;
import com.botmaker.core.project.managed.JavaManagedSource;
import com.botmaker.core.project.managed.ManagedHolders;
import com.botmaker.core.project.managed.ManagedIds;
import com.botmaker.core.project.managed.ManagedMethod;
import com.botmaker.core.project.params.BotRecords;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.grammar.ShortNames;
import com.botmaker.java.grammar.ValueGrammar;
import com.botmaker.java.parse.BotParser;
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
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One plugin's values of this PC ({@link com.botmaker.plugin.api.managed.HostHolder}), read and written as Java in its
 * host folder, {@code <config>/plugins/<group>/<artifact>/}: what {@link StudioServices#hostValues()} answers.
 *
 * <p><b>The bot's reading and writing, over another folder.</b> A value is read by {@link JavaManagedSource}, its
 * expression replaced by {@link JavaManagedEdits}, a missing holder planned by {@link ManagedHolders#planIn} — the
 * same pieces {@link HostPluginValues} puts a bot's values through. What differs is what the folder is: one plugin's,
 * every bot's, in no version, publish or {@code .bmk}, and on no run's classpath. So it has no Project History; each
 * write keeps the class it replaced in {@code .history/} instead (the last {@value #KEPT}).
 *
 * <p><b>Each write is compiled</b> with the folder's other classes against the plugin's classpath; one that would not
 * compile is put back and refused with javac's sentence. A host with no compiler, or no classpath for the plugin,
 * writes unchecked, and says so once. Every instance over one folder writes under one lock.
 *
 * <p><b>Only a newer plugin moves the folder on</b> (the user's call, 2026-11-12: the newest version on this PC serves
 * host data alone). {@code host.properties} records the version that last wrote; an older one reads and is refused
 * every write.
 *
 * <p>Method values only so far: an open set of this PC is refused with words.
 */
public final class HostValues implements PluginValues {

    /** The file naming the plugin version that last wrote the folder. */
    public static final String PROPERTIES = "host.properties";

    /** The folder each replaced class is kept in. */
    public static final String HISTORY = ".history";

    /** How many replaced copies of one class are kept. */
    static final int KEPT = 20;

    private static final Pattern NUMBERS = Pattern.compile("(\\d+)");

    /** One lock per folder: every bot's {@code HostValues} of one plugin writes the same files. */
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    /** Whether an unchecked write was said already, so it is said once. */
    private static final AtomicBoolean UNCHECKED = new AtomicBoolean();

    private final Path folder;
    private final PluginCoordinate plugin;
    private final String version;
    private final List<ManagedValue<?>> declared;
    private final ValueGrammar grammar;
    private final StudioServices services;
    private final Supplier<List<Path>> classpath;

    /**
     * @param root      the host's config root, under which {@code plugins/} lies
     * @param version   the version of {@code plugin} asking, compared with {@code host.properties}
     * @param declared  every value the plugin declares; its values of this PC are kept
     * @param classpath the plugin's jar and what it needs, which a write is compiled against
     */
    public HostValues(Path root, PluginCoordinate plugin, String version, List<ManagedValue<?>> declared,
                      ValueGrammar grammar, StudioServices services, Supplier<List<Path>> classpath) {
        this.folder = folder(root, plugin);
        this.plugin = plugin;
        this.version = version == null ? "" : version;
        this.declared = declared == null ? List.of()
                : declared.stream().filter(Objects::nonNull).filter(ManagedValue::onHost).toList();
        this.grammar = grammar == null ? ValueGrammar.empty() : grammar;
        this.services = services;
        this.classpath = classpath == null ? List::of : classpath;
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

    /** The folder this reads and writes. */
    public Path folder() {
        return folder;
    }

    // ── reading ────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public List<String> ids() {
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

    /** The folder's {@code .java} files, in name order; none when it does not exist. */
    private List<Path> sources() {
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> files = Files.list(folder)) {
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
        synchronized (lock()) {
            return created(value, method);
        }
    }

    /** {@link #create}'s write, under the folder's lock. */
    private Optional<String> created(ManagedValue<?> value, com.botmaker.plugin.api.source.ManagedMethod<?> method) {
        if (find(value.id()).isPresent()) return Optional.empty();
        Optional<String> older = olderThanFolder();
        if (older.isPresent()) return older;
        Path file = folder.resolve(value.holder() + ".java");
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
            ManagedHolders.Plan plan = ManagedHolders.planIn(folder, folder + File.separator, packageOf(plugin),
                    plugin, value, declared, grammar);
            if (plan instanceof ManagedHolders.Plan.Refused refused) return Optional.of(refused.reason());
            source = ((ManagedHolders.Plan.Write) plan).source();
        }
        return write(file, source);
    }

    /** {@code method}'s expression replaced by {@code expression}: empty when written, else why not. */
    private Optional<String> set(ManagedMethod method, JavaValue expression) {
        synchronized (lock()) {
            return setLocked(method, expression);
        }
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
        return write(method.file(), after);
    }

    /** The lock every write to this folder takes, whichever instance makes it. */
    private Object lock() {
        return LOCKS.computeIfAbsent(folder.toAbsolutePath().normalize(), key -> new Object());
    }

    /**
     * Writes {@code source} to {@code file}, then compiles the folder: when that fails, puts the old text back and
     * answers javac's sentence; else keeps the old text in {@code .history/}. Under {@link #lock()}.
     */
    private Optional<String> write(Path file, String source) {
        String previous;
        try {
            Files.createDirectories(folder);
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
        if (previous != null) {
            try {
                keep(file, previous);
            } catch (IOException | UncheckedIOException e) {
                warn("Could not keep the replaced " + file.getFileName() + " in " + HISTORY + ": " + e.getMessage());
            }
        }
        record();
        return Optional.empty();
    }

    /** Copies {@code previous} to {@code .history/<Class>.<n>.java}, keeping the last {@value #KEPT}. */
    private void keep(Path file, String previous) throws IOException {
        Path history = folder.resolve(HISTORY);
        Files.createDirectories(history);
        String name = file.getFileName().toString();
        String stem = name.substring(0, name.length() - ".java".length());
        Pattern copy = Pattern.compile(Pattern.quote(stem) + "\\.(\\d+)\\.java");
        List<Path> copies;
        try (Stream<Path> files = Files.list(history)) {
            copies = files.filter(each -> copy.matcher(each.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(each -> number(copy, each))).toList();
        }
        int next = copies.isEmpty() ? 1 : number(copy, copies.getLast()) + 1;
        Files.writeString(history.resolve(stem + "." + next + ".java"), previous);
        for (int i = 0; i <= copies.size() - KEPT; i++) Files.deleteIfExists(copies.get(i));
    }

    private static int number(Pattern copy, Path file) {
        Matcher matched = copy.matcher(file.getFileName().toString());
        return matched.matches() ? Integer.parseInt(matched.group(1)) : 0;
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

    // ── host.properties ───────────────────────────────────────────────────────────────────────────────────

    /** The version that last wrote the folder, or empty. */
    String recorded() {
        Path file = folder.resolve(PROPERTIES);
        if (!Files.isRegularFile(file)) return "";
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
        return properties.getProperty("version", "");
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
        if (version.isEmpty() || compare(recorded(), version) >= 0) return;
        Properties properties = new Properties();
        properties.setProperty("plugin", plugin.toString());
        properties.setProperty("version", version);
        try (Writer writer = Files.newBufferedWriter(folder.resolve(PROPERTIES))) {
            properties.store(writer, "The plugin version that last wrote this folder");
        } catch (IOException e) {
            warn("Could not record " + version + " in " + folder.resolve(PROPERTIES) + ": " + e.getMessage());
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
}
