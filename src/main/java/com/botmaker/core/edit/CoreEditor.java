package com.botmaker.core.edit;

import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.events.FileEdit;
import com.botmaker.core.parser.CodeEditor;
import com.botmaker.core.parser.EditorOutput;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectFile;
import com.botmaker.core.project.ProjectState;
import com.botmaker.java.edit.palette.BlockType;
import com.botmaker.java.index.LibraryIndex;
import com.botmaker.java.types.ProjectAnalyzer;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The one way an edit reaches the open bot, by {@code BlockId} in the tree and never through anything a client drew. A
 * request runs as an {@link EditSession}: every edit lands as one undo step, or none does and the answer says why. What
 * landed goes out through {@link EditorOutput}, as {@code CodeEditor}'s own writes do, and as {@link CoreEvent.MethodsChanged},
 * which is what a client redraws from; each write is one step of the project's history ({@link #step}).
 *
 * <p>Confined to the thread that owns {@link ProjectState}. The canvas's edits run here as they are made; an assistant's
 * session runs on its own thread and only its {@link #commit} comes here.
 */
public final class CoreEditor {

    private final ProjectConfig config;
    private final ProjectState state;
    private final EditorOutput output;
    private final ProjectAnalyzer analyzer;
    private final Consumer<CoreEvent> events;
    private final CanvasEditor canvas;
    private final ProjectHistory history;
    /** What the running canvas edit did, confined with the rest to the owning thread. */
    private List<ChangedMethod> changed;
    private String said;

    public CoreEditor(ProjectConfig config, ProjectState state, EditorOutput output, ProjectAnalyzer analyzer,
                      Consumer<CoreEvent> events) {
        this.config = config;
        this.state = state;
        this.output = output;
        this.analyzer = analyzer;
        this.events = events;
        this.canvas = new CanvasEditor(state, new CodeEditor(config, state, new Announcing(), analyzer));
        this.history = new ProjectHistory(state, events, output::status, this::restored);
    }

    /**
     * Undoes or redoes the project's last step, whoever made it and in whichever file; false when there is none that
     * way. What it puts back in the open file is announced as {@link CoreEvent.MethodsChanged}, and records nothing.
     */
    public boolean step(HistoryStep step) {
        return history.step(step);
    }

    /**
     * Files something other than an edit already wrote, buffer and disk (a plugin's value writes), as one step named
     * {@code label}, so a step takes them back like an edit.
     */
    public void recordRewrite(String label, List<FileEdit> edits) {
        history.recordRewrite(label, edits);
    }

    /**
     * A write of the open file the core made outside an edit (an open-time migration): announced and recorded as one
     * step, as an edit's own write is.
     */
    public void rewritten(EditorOutput.Write write) {
        output.written(write);
        announce(write);
    }

    /** {@code request}'s edits as one step, or none of them. */
    public EditResult apply(EditRequest request) {
        Optional<Path> file = resolve(request.file());
        if (file.isEmpty()) return refused(request.file(), request.file() + " is not the file open here.");
        if (request.edits().stream().anyMatch(CanvasEdit.class::isInstance)) {
            if (request.guard() == EditRequest.Guard.EVERY_EDIT) {
                return refused(request.file(), "That edit is one only the user makes, by hand.");
            }
            if (request.edits().size() > 1) return refused(request.file(), "An edit made by hand goes alone.");
            return byHand((CanvasEdit) request.edits().getFirst());
        }
        boolean writesValues = request.edits().stream().anyMatch(Edit.SetSlot.class::isInstance);
        EditSession session = session(file.get(), request.guard(), writesValues);
        for (int i = 0; i < request.edits().size(); i++) {
            if (session.apply(request.edits().get(i)) instanceof Outcome.Refused no) {
                String which = request.edits().size() == 1 ? ""
                        : "Edit " + (i + 1) + " of " + request.edits().size() + " was refused, so none was kept: ";
                return refused(request.file(), which, session, no);
            }
        }
        return land(session, null);
    }

    /** One edit the user made by hand on the open file, judged by the editor's own rules. */
    public EditResult userEdit(Edit edit) {
        Optional<String> open = openFile();
        if (open.isEmpty()) return new EditResult.Refused("No file is open.");
        return edit instanceof CanvasEdit gesture ? byHand(gesture)
                : apply(new EditRequest(open.get(), List.of(edit), EditRequest.Guard.EDITOR_RULES));
    }

    /**
     * A gesture on the canvas, on the live tree: the editor's locks and guards judge it and its words go to the user. It
     * lands as the editor wrote it, however many files that took, and a gesture that changes nothing is no refusal.
     */
    private EditResult byHand(CanvasEdit edit) {
        String file = openFile().orElse("");
        if (edit instanceof CanvasEdit.InsertBlock insert) return insert(insert.bodyId(), insert.block(), insert.index());
        changed = null;
        said = null;
        boolean done;
        try {
            done = canvas.run(edit);
        } catch (CanvasEditor.NotInTree | IllegalArgumentException no) {
            return refused(file, no.getMessage());
        }
        if (changed != null) return new EditResult.Accepted(changed);
        if (said != null) return refused(file, said);
        return done ? new EditResult.Accepted(List.of()) : refused(file, "Nothing was changed.");
    }

    /** The editor's output, plus what it means for the protocol: each write says which methods it reshaped. */
    private final class Announcing implements EditorOutput {
        @Override
        public void status(String message) {
            said = message;
            output.status(message);
        }

        @Override
        public void written(Write write) {
            output.written(write);
            changed = announce(write).changed();
        }

        @Override
        public void blockAdded(BlockType type) {
            output.blockAdded(type);
        }
    }

    /**
     * A block the user picked from a menu, inserted in the open file. A menu's block can carry more than a palette id
     * says (the method it calls, the variable it assigns).
     */
    private EditResult insert(String bodyId, BlockType type, int index) {
        Optional<String> open = openFile();
        ProjectFile active = state.getActiveFile();
        if (open.isEmpty() || active == null) return new EditResult.Refused("No file is open.");
        EditSession session = session(active.getPath(), EditRequest.Guard.EDITOR_RULES, false);
        if (session.insert(bodyId, type, index) instanceof Outcome.Refused no) {
            return refused(open.get(), "", session, no);
        }
        return land(session, null);
    }

    /**
     * Lands a client's own session, as one step named {@code label}; refused when the session's file is not the open one,
     * nothing was kept, or {@code liveSource}, the text of the file now, is no longer what the session started from.
     */
    public EditResult commit(EditSession session, String liveSource, String label) {
        ProjectFile active = state.getActiveFile();
        String file = openFile().orElse("");
        if (active == null || !active.getPath().equals(session.file())) {
            return refused(file, "The session's file is not the file open here.");
        }
        if (session.acceptedEdits() == 0 || session.source().equals(session.original())) {
            return refused(file, "No edit was kept, so there is nothing to apply.");
        }
        if (session.finished(liveSource, label).isEmpty()) {
            return refused(file, "The file changed in Studio meanwhile. Read the tree again.");
        }
        return land(session, label, liveSource);
    }

    /** The open file, by its path below the source root as a request names it. */
    public Optional<String> openFile() {
        ProjectFile active = state.getActiveFile();
        Path root = state.getSourcePath();
        if (active == null) return Optional.empty();
        Path file = active.getPath();
        Path named = root != null && file.startsWith(root) ? root.relativize(file) : file;
        return Optional.of(named.toString().replace('\\', '/'));
    }

    private Optional<Path> resolve(String file) {
        ProjectFile active = state.getActiveFile();
        return active != null && openFile().filter(file::equals).isPresent()
                ? Optional.of(active.getPath()) : Optional.empty();
    }

    /** A session over the live file: its first step reads the live tree, which an edit's next render parses anyway. */
    private EditSession session(Path file, EditRequest.Guard guard, boolean writesValues) {
        LibraryIndex index = analyzer == null ? null : analyzer.libraryIndex();
        EditWorkspace workspace = writesValues ? EditWorkspace.of(config, state, index, file)
                : EditWorkspace.withoutConstants(config, state, index, file);
        return new EditSession(workspace, state.getCurrentCode(), guard,
                () -> new EditStage(config, state, state.getCompilationUnit().orElse(null), analyzer, null));
    }

    private EditResult land(EditSession session, String label) {
        return land(session, label, session.original());
    }

    private EditResult land(EditSession session, String label, String liveSource) {
        Optional<EditorOutput.Write> write = session.finished(liveSource, label);
        if (write.isEmpty()) return new EditResult.Accepted(List.of());
        output.written(write.get());
        MethodDiff diff = announce(write.get());
        session.added().forEach(output::blockAdded);
        return new EditResult.Accepted(diff.changed());
    }

    /** {@code write} told to every client, before anything that follows from it, and recorded as one step. */
    private MethodDiff announce(EditorOutput.Write write) {
        MethodDiff diff = announce(write.previousCode(), write.newCode(), write.markNewIdentifiersAsUnedited());
        history.recordWrite(write.label(), write.previousCode(), write.newCode(), write.alsoChanged());
        return diff;
    }

    /** What a step put back in the open file, told as any change of it. */
    private void restored(String text) {
        announce(state.getCurrentCode(), text, false);
    }

    private MethodDiff announce(String previous, String now, boolean newNamesUnchosen) {
        MethodDiff diff = MethodDiff.between(state.level(), previous == null ? "" : previous, now);
        openFile().ifPresent(file -> events.accept(
                new CoreEvent.MethodsChanged(file, diff.changed(), diff.removed(), now, newNamesUnchosen)));
        // After the clients heard, since an in-process one reads the outgoing text as the previous; a client that
        // adopted already made this a no-op, and one that does not (out of process, headless) leaves the core current.
        state.setCurrentCode(now);
        return diff;
    }

    /**
     * What the editor itself said goes to the user, as it did before a refusal had a shape. The rest of a refusal's reasons
     * are worded for a model (an id it named, a slot it set) and only the answer carries them.
     */
    private EditResult refused(String file, String prefix, EditSession session, Outcome.Refused no) {
        session.editorSaid().forEach(output::status);
        return refused(file, prefix + String.join(" ", no.reasons()));
    }

    private EditResult refused(String file, String reason) {
        events.accept(new CoreEvent.Refusal(file, reason));
        return new EditResult.Refused(reason);
    }
}
