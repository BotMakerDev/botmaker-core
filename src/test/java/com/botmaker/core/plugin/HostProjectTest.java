package com.botmaker.core.plugin;

import com.botmaker.core.project.vcs.ProjectVcs;
import com.botmaker.java.grammar.ValueGrammar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import static com.botmaker.core.plugin.HostValuesTest.GREETING;
import static com.botmaker.core.plugin.HostValuesTest.PLUGIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A plugin's folder of this PC as a project: its layout, and the one-time move of an older folder. */
class HostProjectTest {

    @TempDir
    Path root;

    private HostValues values(String version) {
        List<Path> classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(Path::of).toList();
        return new HostValues(root, PLUGIN, version, List.of(GREETING), ValueGrammar.empty(), null, () -> classpath);
    }

    @Test
    void aNewFolderIsSeededAsAProject() throws Exception {
        HostValues values = values("1.0.0");
        assertEquals(Optional.empty(), values.create(GREETING));

        HostProject project = new HostProject(values.folder(), PLUGIN);
        assertTrue(project.isProject());
        assertEquals("1.0.0", project.version());
        assertTrue(Files.isRegularFile(values.sourcesDir().resolve("Emulators.java")));
        assertTrue(Files.readString(values.folder().resolve(HostProject.POM)).contains("<artifactId>my-plugin</artifactId>"));
        String ignored = Files.readString(values.folder().resolve(".gitignore"));
        assertTrue(ignored.contains("/state/") && ignored.contains("/.lock"), ignored);
        assertEquals(List.of("Added GREETING", "Initial commit"),
                project.vcs().history().stream().map(ProjectVcs.CommitInfo::title).toList(),
                "the project starts with one commit, then the write's");
    }

    @Test
    void anOlderFolderIsMovedItsValuesUnchanged() throws Exception {
        Path folder = olderFolder("1.0.0");
        String before = Files.readString(folder.resolve("Emulators.java"));

        HostValues values = values("1.0.0");
        assertTrue(values.ids().contains(GREETING.id()), "read where the move put it: " + values.ids());

        assertEquals(before, Files.readString(values.sourcesDir().resolve("Emulators.java")));
        assertFalse(Files.exists(folder.resolve("Emulators.java")), "the class at the root is gone");
        assertFalse(Files.exists(folder.resolve(HostProject.LEGACY_HISTORY)));
        assertFalse(Files.exists(folder.resolve(HostProject.LEGACY_PROPERTIES)));
        assertEquals("1.0.0", values.recorded(), "the pom is written from host.properties");

        List<ProjectVcs.CommitInfo> history = new ProjectVcs(folder).history();
        assertEquals(1, history.size());
        assertEquals(HostProject.MOVED, history.getFirst().title());
        assertTrue(new ProjectVcs(folder).status().isClean(), "what the move deleted was never versioned");
    }

    @Test
    void aFailedMoveLeavesTheFolderAsItWas() throws Exception {
        Path folder = olderFolder("1.0.0");
        // A file where src/ must go: the move cannot make its layout.
        Files.writeString(folder.resolve("src"), "in the way");
        Map<String, String> before = contents(folder);

        Optional<String> failed = new HostProject(folder, PLUGIN).ready("1.0.0");

        assertTrue(failed.orElseThrow().contains("left as it was"), failed.toString());
        assertEquals(before, contents(folder));
        assertTrue(values("1.0.0").ids().contains(GREETING.id()), "an unmoved folder's values are still read");
    }

    @Test
    void aMoveCutShortIsDoneOverItsCopies() throws Exception {
        Path folder = olderFolder("1.0.0");
        HostProject project = new HostProject(folder, PLUGIN);
        // What a move killed after its copy leaves: the class in src/ and nothing else.
        Files.createDirectories(project.sources());
        Files.copy(folder.resolve("Emulators.java"), project.sources().resolve("Emulators.java"));

        assertEquals(Optional.empty(), project.ready("1.0.0"));
        assertTrue(project.isProject());
        assertFalse(Files.exists(folder.resolve("Emulators.java")));
    }

    /** An older host's folder: the class at its root, a copy in {@code .history/}, {@code host.properties}. */
    private Path olderFolder(String version) throws Exception {
        HostValues values = values(version);
        values.create(GREETING);
        Path folder = values.folder();
        Files.move(values.sourcesDir().resolve("Emulators.java"), folder.resolve("Emulators.java"));
        for (String made : List.of("src", ".git", HostProject.POM, ".gitignore")) {
            HostProject.deleteTree(folder.resolve(made));
        }
        Files.createDirectories(folder.resolve(HostProject.LEGACY_HISTORY));
        Files.writeString(folder.resolve(HostProject.LEGACY_HISTORY).resolve("Emulators.1.java"), "// older");
        Files.writeString(folder.resolve(HostProject.LEGACY_PROPERTIES), "version=" + version + "\n");
        assertTrue(new HostProject(folder, PLUGIN).isLegacy());
        return folder;
    }

    /** Every file under {@code folder} but the lock, by relative path, with its text. */
    private static Map<String, String> contents(Path folder) throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path each : files.filter(Files::isRegularFile).toList()) {
                String name = folder.relativize(each).toString().replace('\\', '/');
                if (!name.equals(HostValues.LOCK_FILE)) out.put(name, Files.readString(each));
            }
        }
        return out;
    }
}
