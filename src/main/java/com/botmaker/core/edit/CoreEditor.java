package com.botmaker.core.edit;

import com.botmaker.core.event.CoreEvent;
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
 * landed goes out through {@link EditorOutput}, as {@code CodeEditor}'s own writes do, and as {@link CoreEvent.MethodsChanged}.
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

    public CoreEditor(ProjectConfig config, ProjectState state, EditorOutput output, ProjectAnalyzer analyzer,
                      Consumer<CoreEvent> events) {
        this.config = config;
        this.state = state;
        this.output = output;
        this.analyzer = analyzer;
        this.events = events;
    }

    /** {@code request}'s edits as one step, or none of them. */
    public EditResult apply(EditRequest request) {
        Optional<Path> file = resolve(request.file());
        if (file.isEmpty()) return refused(request.file(), request.file() + " is not the file open here.");
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
        return open.isEmpty() ? new EditResult.Refused("No file is open.")
                : apply(new EditRequest(open.get(), List.of(edit), EditRequest.Guard.EDITOR_RULES));
    }

    /**
     * A block the user picked from a menu, inserted in the open file. A menu's block can carry more than a palette id
     * says (the method it calls, the variable it assigns), so in-process it crosses as itself.
     */
    public EditResult userInsert(String bodyId, BlockType type, int index) {
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
        MethodDiff diff = MethodDiff.between(state.level(), session.original(), session.source());
        output.written(write.get());
        session.added().forEach(output::blockAdded);
        openFile().ifPresent(file -> events.accept(new CoreEvent.MethodsChanged(file, diff.changed(), diff.removed())));
        return new EditResult.Accepted(diff.changed());
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
