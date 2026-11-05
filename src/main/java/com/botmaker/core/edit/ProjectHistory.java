package com.botmaker.core.edit;

import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.events.FileEdit;
import com.botmaker.core.project.ProjectFile;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.state.HistoryManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The project's one undo history: every edit of the open file and every file a plugin's value writes rewrote, in the
 * order they happened, so a {@link HistoryStep} takes back the last of them whoever made it. Studio's
 * {@code EditorHistory} until 2026-11-05 (plan phase 6e).
 *
 * <p>Synchronized because two threads feed it: the canvas's edits arrive on the thread that owns the state, and a plugin
 * tool the assistant calls writes on the endpoint's. A restore waits on no other thread, so holding the lock across one
 * cannot deadlock.
 */
final class ProjectHistory {

    /** What a step puts back in the open file, which the core then announces as any change of it. */
    interface OpenFile {
        void restored(String text);
    }

    private final ProjectState state;
    private final Consumer<CoreEvent> events;
    private final Consumer<String> said;
    private final OpenFile openFile;
    private final HistoryManager history;

    ProjectHistory(ProjectState state, Consumer<CoreEvent> events, Consumer<String> said, OpenFile openFile) {
        this.state = state;
        this.events = events;
        this.said = said;
        this.openFile = openFile;
        this.history = new HistoryManager(this::restoreFiles);
    }

    /**
     * One write of the open file as one step, with every other file the same edit wrote: a signature migration writes
     * the declaration here and its calls elsewhere, and it is one change to the user, so it is one undo.
     */
    synchronized void recordWrite(String label, String previous, String now, List<FileEdit> alsoChanged) {
        Map<Path, String> before = new LinkedHashMap<>();
        Map<Path, String> after = new LinkedHashMap<>();
        Path active = activePath();
        if (active != null && previous != null && !previous.isEmpty()) {
            before.put(active, previous);
            after.put(active, now);
        }
        put(alsoChanged, before, after);
        history.record(label, before, after);
        announce();
    }

    /** Files already written, buffer and disk, by something other than an edit: recorded, never applied. */
    synchronized void recordRewrite(String label, List<FileEdit> edits) {
        Map<Path, String> before = new LinkedHashMap<>();
        Map<Path, String> after = new LinkedHashMap<>();
        put(edits, before, after);
        history.record(label, before, after);
        announce();
    }

    /** {@code step} taken, or nothing when there is no step that way. */
    synchronized boolean step(HistoryStep step) {
        boolean can = step == HistoryStep.UNDO ? history.canUndo() : history.canRedo();
        if (!can) return false;
        if (step == HistoryStep.UNDO) history.undo();
        else history.redo();
        announce();
        return true;
    }

    private static void put(List<FileEdit> edits, Map<Path, String> before, Map<Path, String> after) {
        for (FileEdit edit : edits) {
            before.put(normal(edit.path()), edit.previousContent());
            after.put(normal(edit.path()), edit.newContent());
        }
    }

    /** One key per file, whichever spelling of its path a write was announced with. */
    private static Path normal(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /**
     * Puts one step's files back: the ones nobody is looking at straight to disk, where the change put them, with their
     * cached tree dropped so no suggestion keeps reading the version just taken back; the open one as a change of it,
     * which records nothing.
     */
    private void restoreFiles(Map<Path, String> files) {
        Path active = activePath();
        for (Map.Entry<Path, String> each : files.entrySet()) {
            if (each.getKey().equals(active)) continue;
            writeBack(each.getKey(), each.getValue());
        }
        String activeText = active == null ? null : files.get(active);
        if (activeText != null) openFile.restored(activeText);
    }

    private void writeBack(Path path, String text) {
        // Matched by the normal path, as BotSources finds a buffer: the state keys a file as it was opened.
        for (ProjectFile file : state.getAllFiles()) {
            if (file.getPath() != null && normal(file.getPath()).equals(path)) {
                file.setContent(text);
                file.setAst(null);
            }
        }
        try {
            Files.writeString(path, text);
        } catch (IOException e) {
            said.accept("Couldn't put " + path.getFileName() + " back: " + e.getMessage());
        }
    }

    private Path activePath() {
        ProjectFile file = state.getActiveFile();
        return file == null || file.getPath() == null ? null : normal(file.getPath());
    }

    private void announce() {
        events.accept(new CoreEvent.HistoryChanged(history.canUndo(), history.canRedo(), history.undoLabel(),
                history.redoLabel()));
    }
}
