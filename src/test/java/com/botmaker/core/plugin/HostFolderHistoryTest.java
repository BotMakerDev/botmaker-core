package com.botmaker.core.plugin;

import com.botmaker.core.project.vcs.ProjectVcs;
import com.botmaker.java.grammar.ValueGrammar;
import com.botmaker.plugin.api.History;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.botmaker.core.plugin.HostValuesTest.GREETING;
import static com.botmaker.core.plugin.HostValuesTest.PLUGIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ↶ and ↷ over a plugin's values of this PC: its folder's commits. */
class HostFolderHistoryTest {

    @TempDir
    Path root;

    private HostValues values(String version) {
        List<Path> classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(Path::of).toList();
        return new HostValues(root, PLUGIN, version, List.of(GREETING), ValueGrammar.empty(), null, () -> classpath);
    }

    private static void write(HostValues values, String greeting) {
        values.open(GREETING).orElseThrow().write(greeting);
    }

    private static String greeting(HostValues values) throws Exception {
        String text = Files.readString(values.sourcesDir().resolve("Emulators.java"));
        int at = text.indexOf("return \"") + "return \"".length();
        return text.substring(at, text.indexOf('"', at));
    }

    @Test
    void undoAndRedoWalkThreeWrites() throws Exception {
        HostValues values = values("1.0.0");
        values.create(GREETING);
        History history = values.history();
        AtomicInteger told = new AtomicInteger();
        history.onChanged(told::incrementAndGet);
        for (String each : List.of("one", "two", "three")) write(values, each);
        assertEquals(3, told.get(), "each write is told");
        assertEquals("Set GREETING", history.undoLabel());
        assertFalse(history.canRedo());

        history.undo();
        assertEquals("two", greeting(values));
        history.undo();
        assertEquals("one", greeting(values));
        assertEquals("Set GREETING", history.redoLabel());
        history.redo();
        assertEquals("two", greeting(values));
        history.redo();
        assertEquals("three", greeting(values));
        assertFalse(history.canRedo());
        assertEquals(7, told.get(), "each undo and redo is told");

        List<String> titles = new ProjectVcs(values.folder()).history().stream()
                .map(ProjectVcs.CommitInfo::title).toList();
        assertEquals(List.of("Redo Set GREETING", "Redo Set GREETING", "Undo Set GREETING", "Undo Set GREETING"),
                titles.subList(0, 4), "nothing is rewritten: an undo is a commit too");
    }

    @Test
    void aWriteAfterAnUndoDropsTheRedoAndUndoSkipsWhatWasUndone() throws Exception {
        HostValues values = values("1.0.0");
        values.create(GREETING);
        History history = values.history();
        write(values, "one");
        write(values, "two");
        history.undo();
        write(values, "three");
        assertFalse(history.canRedo(), "a write since starts again from the newest commit");

        history.undo();
        assertEquals("one", greeting(values), "the undone write is not a step any more");
        history.undo();
        assertEquals("hello", greeting(values), "back to the value as it was added");
        history.undo();
        assertFalse(history.canUndo(), "the first commit is never undone");
    }

    @Test
    void aBatchIsOneStep() throws Exception {
        HostValues values = values("1.0.0");
        values.create(GREETING);
        History history = values.history();
        history.batch("Greet twice", () -> {
            write(values, "a");
            write(values, "b");
        });
        assertEquals("Greet twice", history.undoLabel());
        history.undo();
        assertEquals("hello", greeting(values));
        assertTrue(new ProjectVcs(values.folder()).status().isClean());
    }

    @Test
    void anUpgradeIsNeverUndone() throws Exception {
        values("1.0.0").create(GREETING);
        HostValues next = values("2.0.0");
        next.ids();
        write(next, "after");
        History history = next.history();
        history.undo();
        assertEquals("hello", greeting(next));
        assertFalse(history.canUndo(), "below the upgrade is the older version's");
        assertEquals("2.0.0", next.recorded());
    }

    @Test
    void anOlderCopyUndoesNothingAndANewerOneIsHeardByTheSameListener() throws Exception {
        HostValues first = values("1.0.0");
        first.create(GREETING);
        AtomicInteger told = new AtomicInteger();
        first.history().onChanged(told::incrementAndGet);

        HostValues newer = values("2.0.0");
        write(newer, "newer");
        assertTrue(told.get() > 0, "a newer copy's write is heard where the window listens");
        first.history().undo();
        assertEquals("newer", greeting(newer), "an older copy never changes what a newer one wrote");
    }

    @Test
    void aPluginWithNoValuesOfThisPcHasNoHistory() {
        assertEquals(History.NONE, HostFolders.historyOf(null));
    }
}
