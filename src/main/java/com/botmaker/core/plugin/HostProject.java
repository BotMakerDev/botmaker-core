package com.botmaker.core.plugin;

import com.botmaker.core.project.vcs.ProjectVcs;
import com.botmaker.core.project.vcs.VersionOrigin;
import com.botmaker.plugin.api.PluginState;
import com.botmaker.plugin.host.PluginCoordinate;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * One plugin's folder of this PC as a project the plugin manages ({@code docs/superpowers/specs/
 * 2026-11-13-host-projects-design.md}): a {@code pom.xml} naming the plugin at the version serving it, one class per
 * host holder in {@code src/main/java/<group>/<artifact>/}, and a git repository. Only the host reads or writes it,
 * and Studio never opens it as a project.
 *
 * <p>The pom records the version; nothing resolves it. A write is compiled against the plugin's admitted classpath
 * ({@link HostFolders}), so no Maven runs.
 *
 * <p><b>An older folder is moved once</b> ({@link #ready}): its classes are copied into {@code src/}, the pom written
 * from {@code host.properties}, and the whole committed as {@value #MOVED}; only then are the classes at its root,
 * {@code .history/} and {@code host.properties} deleted. A move that fails takes back what it made, so the folder is
 * left as it was. Every call here is made under the folder's lock ({@link HostValues}).
 */
final class HostProject {

    /** The pom naming the plugin at the version serving the folder. */
    static final String POM = "pom.xml";

    /** The first commit of a moved folder. */
    static final String MOVED = "Moved from the folder of this PC";

    /** The older folder's version file, read once by the move. */
    static final String LEGACY_PROPERTIES = "host.properties";

    /** The older folder's copies of replaced classes, deleted by the move. */
    static final String LEGACY_HISTORY = ".history";

    /**
     * The repository's own attributes, versioned by nothing, so no restore takes them away: the folder is the host's,
     * and a restore puts back the bytes it wrote whatever this PC's {@code core.autocrlf} says.
     */
    private static final String ATTRIBUTES = ".git/info/attributes";

    /**
     * What git keeps out: the writers' lock, the plugin's window state ({@code state/}, JSON a window keeps beside its
     * values), and what an older folder leaves behind until the move deletes it.
     */
    private static final List<String> IGNORED = List.of(
            "/" + HostValues.LOCK_FILE, "/state/", "/" + LEGACY_HISTORY + "/", "/" + LEGACY_PROPERTIES, "/*.java",
            "target/");

    private final Path folder;
    private final PluginCoordinate plugin;

    HostProject(Path folder, PluginCoordinate plugin) {
        this.folder = folder;
        this.plugin = plugin;
    }

    /** The folder, the project's root. */
    Path folder() {
        return folder;
    }

    /** Where the holder classes are: {@code src/main/java/<group>/<artifact>/}, each {@link PluginState#part}. */
    Path sources() {
        return sourceRoot().resolve(PluginState.part(plugin.groupId())).resolve(PluginState.part(plugin.artifactId()));
    }

    /**
     * Where the holder classes are read and written now: {@link #sources}, or the folder itself while an older folder
     * is not moved yet (its move failed), so its values stay seen where they are, and written there unless a newer
     * version needed to repair them, which it cannot do before the move (read-only, {@code HostValues.readOnly}).
     */
    Path classes() {
        return isLegacy() ? folder : sources();
    }

    /** The source root {@link #classes} lies under: {@code src/main/java}, or the older folder itself. */
    Path classesRoot() {
        return isLegacy() ? folder : sourceRoot();
    }

    /** The source root, {@code src/main/java}, which the package {@link HostValues#packageOf} lies under. */
    Path sourceRoot() {
        return folder.resolve("src").resolve("main").resolve("java");
    }

    /** The project's history. */
    ProjectVcs vcs() {
        return new ProjectVcs(folder);
    }

    /**
     * Commits what changed in the folder as one version labelled {@code label}: empty when committed or nothing
     * changed, else why not. A folder that is not a project (its move failed) commits nothing.
     */
    Optional<String> commit(VersionOrigin origin, String label) {
        if (!isProject()) return Optional.empty();
        try {
            vcs().checkpoint(origin, label);
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.of("Could not record \"" + label + "\" in the history of this PC's values of "
                    + plugin.artifactId() + ": " + e.getMessage());
        }
    }

    /** Whether the folder is a project already: a pom and a repository. */
    boolean isProject() {
        return Files.isRegularFile(folder.resolve(POM)) && vcs().isRepo();
    }

    /** Whether the folder holds what an older host left: classes at its root, {@code .history/}, {@code host.properties}. */
    boolean isLegacy() {
        if (Files.isRegularFile(folder.resolve(POM))) return false;
        return Files.exists(folder.resolve(LEGACY_PROPERTIES)) || Files.isDirectory(folder.resolve(LEGACY_HISTORY))
                || !rootClasses().isEmpty();
    }

    // ── the version ───────────────────────────────────────────────────────────────────────────────────────

    /** The version the pom names the plugin at, or, before the move, the one {@code host.properties} records; else empty. */
    String version() {
        Path pom = folder.resolve(POM);
        if (Files.isRegularFile(pom)) {
            try (Reader reader = Files.newBufferedReader(pom)) {
                Model model = new MavenXpp3Reader().read(reader);
                return model.getDependencies().stream().filter(this::isPlugin).map(Dependency::getVersion)
                        .filter(Objects::nonNull).findFirst().orElse("");
            } catch (IOException | RuntimeException | XmlPullParserException e) {
                return "";
            }
        }
        return legacyVersion();
    }

    /** Writes the pom naming the plugin at {@code version}. */
    void record(String version) throws IOException {
        Model model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId(plugin.groupId());
        model.setArtifactId(plugin.artifactId() + "-this-pc");
        model.setVersion("1");
        model.setName("This PC's values of " + plugin.artifactId());
        model.setDescription("Written and read by BotMaker only; the plugin's windows change it.");
        Dependency dependency = new Dependency();
        dependency.setGroupId(plugin.groupId());
        dependency.setArtifactId(plugin.artifactId());
        dependency.setVersion(version);
        model.addDependency(dependency);
        Files.createDirectories(folder);
        try (Writer writer = Files.newBufferedWriter(folder.resolve(POM))) {
            new MavenXpp3Writer().write(writer, model);
        }
    }

    private boolean isPlugin(Dependency dependency) {
        return plugin.groupId().equals(dependency.getGroupId()) && plugin.artifactId().equals(dependency.getArtifactId());
    }

    private String legacyVersion() {
        Path file = folder.resolve(LEGACY_PROPERTIES);
        if (!Files.isRegularFile(file)) return "";
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
        return properties.getProperty("version", "");
    }

    // ── the layout ────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Makes the folder a project: an older one moved ({@link #move}), a new one seeded at {@code version}, one left
     * half made finished. Empty when it is one, else why not, the folder then as it was.
     */
    Optional<String> ready(String version) {
        if (isProject()) return keepBytes();
        if (isLegacy()) return move(version);
        Path git = folder.resolve(".git");
        boolean hadGit = Files.exists(git);
        try {
            Files.createDirectories(sources());
            if (!Files.isRegularFile(folder.resolve(POM))) record(version);
            writeIgnored();
            vcs().init();
            return keepBytes();
        } catch (IOException | UncheckedIOException e) {
            // A repository made without its first commit would pass for a project next time: taken back.
            if (!hadGit) deleteTree(git);
            return Optional.of("Could not make this PC's values of " + plugin.artifactId() + " a project in " + folder
                    + ": " + e.getMessage());
        }
    }

    /**
     * The one-time move of an older folder: classes copied into {@link #sources}, the pom written from
     * {@code host.properties} ({@code fallback} when it records none), the whole committed as {@value #MOVED}, then
     * the older files deleted. Before the commit, a failure deletes what the move made.
     */
    private Optional<String> move(String fallback) {
        List<Path> made = new ArrayList<>();
        try {
            Path src = folder.resolve("src");
            if (!Files.exists(src)) made.add(src);
            Files.createDirectories(sources());
            for (Path each : rootClasses()) {
                Path copy = sources().resolve(each.getFileName());
                made.add(copy);
                // Over a copy a move cut short left: the class at the root is still the one written last.
                Files.copy(each, copy, StandardCopyOption.REPLACE_EXISTING);
            }
            String version = legacyVersion();
            made.add(folder.resolve(POM));
            record(version.isEmpty() ? fallback : version);
            Path ignore = folder.resolve(".gitignore");
            if (!Files.exists(ignore)) made.add(ignore);
            writeIgnored();
            made.add(folder.resolve(".git"));
            vcs().init(VersionOrigin.CREATE, MOVED);
        } catch (IOException | RuntimeException e) {
            made.reversed().forEach(HostProject::deleteTree);
            return Optional.of("Could not move this PC's values of " + plugin.artifactId() + " into a project, so "
                    + folder + " is left as it was: " + e.getMessage());
        }
        Optional<String> bytes = keepBytes();
        List<String> left = new ArrayList<>();
        for (Path each : rootClasses()) deleteOrSay(each, left);
        deleteOrSay(folder.resolve(LEGACY_HISTORY), left);
        deleteOrSay(folder.resolve(LEGACY_PROPERTIES), left);
        // Moved and committed: what could not be deleted is ignored by git and read by nothing, so it is only said.
        return left.isEmpty() ? bytes : Optional.of("This PC's values of " + plugin.artifactId()
                + " were moved into a project, but these older files are left in " + folder + ": "
                + String.join(", ", left));
    }

    /** The folder's {@code .java} files at its root, an older host's holder classes, in name order. */
    private List<Path> rootClasses() {
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(Files::isRegularFile).filter(file -> file.toString().endsWith(".java")).sorted()
                    .toList();
        } catch (IOException | UncheckedIOException unreadable) {
            return List.of();
        }
    }

    private void writeIgnored() throws IOException {
        Path ignore = folder.resolve(".gitignore");
        if (!Files.exists(ignore)) Files.writeString(ignore, String.join("\n", IGNORED) + "\n");
    }

    /** Writes {@link #ATTRIBUTES} into the repository when it lacks them: empty, else why not. */
    private Optional<String> keepBytes() {
        Path attributes = folder.resolve(ATTRIBUTES);
        try {
            if (Files.exists(attributes)) return Optional.empty();
            Files.createDirectories(attributes.getParent());
            Files.writeString(attributes, "* -text\n");
            return Optional.empty();
        } catch (IOException | UncheckedIOException e) {
            return Optional.of("Could not write " + attributes + ", so an undo may change line endings: " + e.getMessage());
        }
    }

    private static void deleteOrSay(Path path, List<String> left) {
        if (!Files.exists(path)) return;
        deleteTree(path);
        if (Files.exists(path)) left.add(path.getFileName().toString());
    }

    /** Deletes {@code path} and what is under it, read-only files included (git's packs on Windows); quietly. */
    static void deleteTree(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (Stream<Path> files = Files.walk(path)) {
            files.sorted(Comparator.reverseOrder()).forEach(each -> {
                each.toFile().setWritable(true);
                each.toFile().delete();
            });
        } catch (IOException | UncheckedIOException ignored) {
            // What is left is said by the caller, which looks again.
        }
    }
}
