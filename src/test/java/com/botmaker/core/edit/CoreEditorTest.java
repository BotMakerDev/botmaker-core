package com.botmaker.core.edit;

import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.parser.EditorOutput;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectFile;
import com.botmaker.core.project.ProjectState;
import com.botmaker.java.edit.BlockId;
import com.botmaker.java.edit.palette.BlockCatalog;
import com.botmaker.java.edit.palette.BlockType;
import com.botmaker.java.types.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An edit by {@code BlockId}, over the live tree, with no block drawn anywhere. */
class CoreEditorTest {

    private static final String SOURCE = """
            package t;
            public class Subject {
                void run() {
                    int a = 1;
                    int b = a;
                }
            }
            """;
    private static final String OPEN = "t/Subject.java";

    private final List<EditorOutput.Write> writes = new ArrayList<>();
    private final List<String> said = new ArrayList<>();
    private final List<BlockType> added = new ArrayList<>();
    private final List<CoreEvent> events = new ArrayList<>();
    private CompilationUnit cu;
    private CoreEditor editor;

    @BeforeEach
    void open() {
        Path root = Paths.get("src", "main", "java").toAbsolutePath();
        Path file = root.resolve(OPEN);
        List<String> classpath = List.of(System.getProperty("java.class.path").split(File.pathSeparator));
        ProjectState state = new ProjectState();
        state.addFile(new ProjectFile(file, SOURCE));
        state.setActiveFile(file);
        state.setSourcePath(root);
        state.setResolvedClasspath(classpath);
        state.setCurrentCode(SOURCE);
        cu = ProjectAnalyzer.createCompilationUnit(classpath, SOURCE, root, file.toString());
        state.setCompilationUnit(cu);
        EditorOutput output = new EditorOutput() {
            @Override
            public void status(String message) {
                said.add(message);
            }

            @Override
            public void written(Write write) {
                writes.add(write);
            }

            @Override
            public void blockAdded(BlockType type) {
                added.add(type);
            }
        };
        editor = new CoreEditor(ProjectConfig.forProject("MyBot", Paths.get("/tmp/projects")), state, output,
                new ProjectAnalyzer(null, state), event -> {
                    events.add(event);
                    // What a client does with a change: it takes the file's new text as the one it shows.
                    if (event instanceof CoreEvent.MethodsChanged changed) state.setCurrentCode(changed.source());
                });
    }

    private Block body() {
        MethodDeclaration run = ((TypeDeclaration) cu.types().getFirst()).getMethods()[0];
        return run.getBody();
    }

    private String line(int index) {
        return BlockId.of((ASTNode) body().statements().get(index));
    }

    private EditResult apply(EditRequest.Guard guard, Edit... edits) {
        return editor.apply(new EditRequest(OPEN, List.of(edits), guard));
    }

    @Test
    void aDeleteLandsAsOneWriteAndSaysWhichMethodChanged() {
        EditResult result = apply(EditRequest.Guard.EDITOR_RULES, new Edit.Delete(line(1)));

        EditResult.Accepted accepted = assertInstanceOf(EditResult.Accepted.class, result);
        assertEquals(List.of("run"), accepted.methods().stream().map(ChangedMethod::name).toList());
        assertEquals(1, writes.size());
        assertFalse(writes.getFirst().newCode().contains("int b"));
        assertTrue(writes.getFirst().newCode().contains("int a"));
        assertEquals(SOURCE, writes.getFirst().previousCode());
        CoreEvent.MethodsChanged event = assertInstanceOf(CoreEvent.MethodsChanged.class, events.getFirst());
        assertEquals(OPEN, event.file());
        assertEquals(writes.getFirst().newCode(), event.source());
    }

    /** An undo puts the open file back as a change of it, which a client redraws from, and records no step of its own. */
    @Test
    void anUndoAnnouncesTheTextItPutBack() {
        apply(EditRequest.Guard.EDITOR_RULES, new Edit.Delete(line(1)));
        events.clear();

        assertTrue(editor.step(HistoryStep.UNDO));

        CoreEvent.MethodsChanged back = assertInstanceOf(CoreEvent.MethodsChanged.class, events.getFirst());
        assertEquals(SOURCE, back.source());
        assertEquals(List.of("run"), back.methods().stream().map(ChangedMethod::name).toList());
        CoreEvent.HistoryChanged history = assertInstanceOf(CoreEvent.HistoryChanged.class, events.getLast());
        assertFalse(history.canUndo());
        assertTrue(history.canRedo());
        assertFalse(editor.step(HistoryStep.UNDO), "nothing is left to undo");
        assertEquals(1, writes.size(), "an undo is no write of the editor's");
    }

    @Test
    void aMoveReordersABody() {
        apply(EditRequest.Guard.EDITOR_RULES, new Edit.Move(line(1), BlockId.of(body()), 0));

        String moved = writes.getFirst().newCode();
        assertTrue(moved.indexOf("int b") < moved.indexOf("int a"), moved);
    }

    @Test
    void aMenusBlockIsInsertedAndAnnounced() {
        BlockType type = BlockCatalog.all().stream().filter(BlockType::isStatement).findFirst().orElseThrow();

        assertInstanceOf(EditResult.Accepted.class, editor.userEdit(new CanvasEdit.InsertBlock(BlockId.of(body()), 0, type)));

        assertEquals(1, writes.size());
        assertEquals(List.of(type), added);
    }

    @Test
    void aGestureLandsThroughTheEditorAndSaysWhichMethodChanged() {
        String literal = BlockId.of(((VariableDeclarationFragment) ((VariableDeclarationStatement)
                body().statements().getFirst()).fragments().getFirst()).getInitializer());

        EditResult result = editor.userEdit(new CanvasEdit.SetLiteral(literal, "7"));

        EditResult.Accepted accepted = assertInstanceOf(EditResult.Accepted.class, result);
        assertEquals(List.of("run"), accepted.methods().stream().map(ChangedMethod::name).toList());
        assertTrue(writes.getFirst().newCode().contains("int a = 7;"), writes.getFirst().newCode());
        assertInstanceOf(CoreEvent.MethodsChanged.class, events.getFirst());
    }

    /** A gesture's Java is the user's: an assistant's request cannot carry one, whatever it asks. */
    @Test
    void anAssistantsRequestCarryingAGestureIsRefused() {
        EditResult result = apply(EditRequest.Guard.EVERY_EDIT,
                new CanvasEdit.InsertStatement(BlockId.of(body()), 0, "System.exit(0);", List.of()));

        assertInstanceOf(EditResult.Refused.class, result);
        assertTrue(writes.isEmpty());
    }

    @Test
    void aGestureGoesAlone() {
        EditResult result = apply(EditRequest.Guard.EDITOR_RULES, new Edit.Delete(line(1)),
                new CanvasEdit.RestoreFile(SOURCE));

        assertInstanceOf(EditResult.Refused.class, result);
        assertTrue(writes.isEmpty());
    }

    @Test
    void aGestureOnANodeTheTreeLacksIsRefused() {
        assertInstanceOf(EditResult.Refused.class, editor.userEdit(new CanvasEdit.SetLiteral("types[0]/nowhere", "7")));
        assertTrue(writes.isEmpty());
    }

    @Test
    void anUnknownIdIsRefusedAndNothingIsWritten() {
        EditResult result = apply(EditRequest.Guard.EDITOR_RULES, new Edit.Delete("types[0]/nowhere"));

        assertInstanceOf(EditResult.Refused.class, result);
        assertTrue(writes.isEmpty());
        assertInstanceOf(CoreEvent.Refusal.class, events.getFirst());
        assertTrue(said.isEmpty(), "a refusal worded for a model does not reach the status bar");
    }

    @Test
    void aRequestLandsWholeOrNotAtAll() {
        EditResult result = apply(EditRequest.Guard.EDITOR_RULES,
                new Edit.Delete(line(1)), new Edit.Delete("types[0]/nowhere"));

        EditResult.Refused refused = assertInstanceOf(EditResult.Refused.class, result);
        assertTrue(refused.reason().startsWith("Edit 2 of 2"), refused.reason());
        assertTrue(writes.isEmpty());
    }

    @Test
    void severalEditsLandAsOneStep() {
        apply(EditRequest.Guard.EDITOR_RULES, new Edit.Delete(line(1)), new Edit.Delete(line(0)));

        assertEquals(1, writes.size());
        assertFalse(writes.getFirst().newCode().contains("int"));
        assertEquals(SOURCE, writes.getFirst().previousCode());
    }

    /** What the user did by hand lands and the error shows; an assistant's is refused: it cannot be asked. */
    @Test
    void theGuardDecidesWhetherANewErrorRefuses() {
        Edit deleteWhatIsRead = new Edit.Delete(line(0));

        assertInstanceOf(EditResult.Refused.class, apply(EditRequest.Guard.EVERY_EDIT, deleteWhatIsRead));
        assertTrue(writes.isEmpty());

        assertInstanceOf(EditResult.Accepted.class, apply(EditRequest.Guard.EDITOR_RULES, deleteWhatIsRead));
        assertEquals(1, writes.size());
    }

    @Test
    void aLocalIsRenamedWithItsUses() {
        apply(EditRequest.Guard.EVERY_EDIT, new Edit.Rename(line(0), "first"));

        assertTrue(writes.getFirst().newCode().contains("int b = first;"), writes.getFirst().newCode());
    }

    @Test
    void aFileNotOpenHereIsRefused() {
        EditResult result = editor.apply(new EditRequest("t/Other.java", List.of(new Edit.Delete(line(0)))));

        assertInstanceOf(EditResult.Refused.class, result);
        assertTrue(writes.isEmpty());
    }

    @Test
    void aCommitOfASessionWhoseFileMovedOnIsRefused() {
        Path root = Paths.get("src", "main", "java").toAbsolutePath();
        EditWorkspace workspace = new EditWorkspace(ProjectConfig.forProject("MyBot", Paths.get("/tmp/projects")),
                root.resolve(OPEN), List.of(System.getProperty("java.class.path").split(File.pathSeparator)), root,
                null, null, null, null, null, List.of());
        EditSession session = new EditSession(workspace, SOURCE);
        assertInstanceOf(Outcome.Accepted.class, session.delete(line(1)));

        assertInstanceOf(EditResult.Refused.class, editor.commit(session, SOURCE + "// the user typed\n", "turn"));
        assertTrue(writes.isEmpty());

        assertInstanceOf(EditResult.Accepted.class, editor.commit(session, SOURCE, "turn"));
        assertEquals("turn", writes.getFirst().label());
    }
}
