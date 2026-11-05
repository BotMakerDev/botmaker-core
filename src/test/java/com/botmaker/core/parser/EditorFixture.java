package com.botmaker.core.parser;

import com.botmaker.core.TestSources;
import com.botmaker.core.parser.guard.RefusalJournal;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectFile;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.project.ProjectTemplate;
import com.botmaker.java.edit.palette.BlockType;
import com.botmaker.java.types.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A real {@link CodeEditor} over a source string, as the core opens one: the file parsed against this test's classpath,
 * and the editor's output kept so a test can assert on the rewritten source and on what the editor said. No block is
 * drawn: a node is found in the tree, by the method it sits in.
 *
 * <p>Studio's own fixture until 2026-11-05 (plan phase 6e), which also draws the blocks; the tests that need no block
 * moved here with this half of it.
 */
public final class EditorFixture {

    private static final ProjectConfig CONFIG = ProjectConfig.forProject("MyBot", Paths.get("/tmp/projects"));

    public final CodeEditor editor;
    public final ProjectState state;
    /** The source this fixture first parsed. */
    public final String source;
    /** The open file's text after the editor's last write, or null while it wrote nothing. */
    public String lastCode;
    /** Every sentence the editor said to the user: how a refused edit announces itself. */
    public final List<String> statusMessages = new ArrayList<>();

    public EditorFixture(String source) {
        this(source, Paths.get("Subject.java").toAbsolutePath());
    }

    public EditorFixture(String source, Path file) {
        this.source = source;
        state = new ProjectState();
        state.addFile(new ProjectFile(file, source));
        state.setActiveFile(file);
        state.setSourcePath(TestSources.SOURCE_ROOT);
        state.setResolvedClasspath(TestSources.runtimeClassPath());
        state.setTemplate(ProjectTemplate.GAME_BOT);
        state.setCurrentCode(source);
        CompilationUnit unit = ProjectAnalyzer.createCompilationUnit(state.getResolvedClasspath(), source,
                TestSources.SOURCE_ROOT, file.toString());
        state.setCompilationUnit(unit);
        RefusalJournal journal =
                RefusalJournal.in(Paths.get(System.getProperty("java.io.tmpdir"), "botmaker-test-refusals"));
        editor = new CodeEditor(CONFIG, state, new EditorOutput() {
            @Override
            public void status(String message) {
                statusMessages.add(message);
            }

            @Override
            public void written(Write write) {
                lastCode = write.newCode();
            }

            @Override
            public void blockAdded(BlockType type) {
            }
        }, new ProjectAnalyzer(null, state), journal);
    }

    /** {@code methodName}'s body in the tree the state holds now. */
    public Block body(String methodName) {
        TypeDeclaration type = (TypeDeclaration) state.getCompilationUnit()
                .orElseThrow(() -> new AssertionError("fixture has no compilation unit"))
                .types().getFirst();
        MethodDeclaration found = null;
        for (MethodDeclaration m : type.getMethods()) {
            if (m.getName().getIdentifier().equals(methodName)) found = m;
        }
        assertNotNull(found, "fixture should have " + methodName + "()");
        return found.getBody();
    }
}
