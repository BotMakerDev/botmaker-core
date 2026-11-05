package com.botmaker.core.parser;

import com.botmaker.java.grammar.SourceParser;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static com.botmaker.java.parse.JavaLevel.LATEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whatever is typed in a comment block stays in that comment, and the code around it stays the code it was. */
class CommentEditTest {

    private static final String SOURCE = """
            package com.mybot;
            public class Subject {
                public static void run() {
                    // marker
                    int b = 2;
                }
            }
            """;

    @ParameterizedTest
    @ValueSource(strings = {"*/\n}", "a */ b", "*/ int c = 3; /*", "one\r}", "\\u000a}", "*\\u002f\n}", "C:\\users\\me"})
    void whatIsTypedStaysCommentText(String typed) {
        EditorFixture f = new EditorFixture(SOURCE);

        f.editor.updateComment(comment(f), typed);

        assertNotNull(f.lastCode, () -> "the edit was refused: " + f.statusMessages);
        CompilationUnit written = SourceParser.parse(LATEST, f.lastCode);
        assertEquals(List.of(), Arrays.stream(written.getProblems()).map(IProblem::getMessage).toList(), f.lastCode);
        assertEquals(1, written.getCommentList().size(), f.lastCode);
        MethodDeclaration run = ((TypeDeclaration) written.types().getFirst()).getMethods()[0];
        assertEquals(1, run.getBody().statements().size(), f.lastCode);
        assertEquals("int b=2;", run.getBody().statements().getFirst().toString().strip(), f.lastCode);
    }

    @Test
    void anEditReplacesTheCommentItWasMadeOn() {
        EditorFixture f = new EditorFixture(SOURCE);

        f.editor.updateComment(comment(f), "renamed");

        assertTrue(f.lastCode.contains("// renamed"), f.lastCode);
        assertFalse(f.lastCode.contains("marker"), f.lastCode);
    }

    @Test
    void aCommentWithCodeAfterItOnItsLineDoesNotSwallowTheCode() {
        EditorFixture f = new EditorFixture(SOURCE.replace("// marker\n        int b", "/* marker */ int b"));

        f.editor.updateComment(comment(f), "renamed");

        assertTrue(f.lastCode.contains("/* renamed */ int b = 2;"), f.lastCode);
    }

    @Test
    void aCommentBeforeAClosingBraceOnItsLineLeavesTheBrace() {
        EditorFixture f = new EditorFixture("""
                package com.mybot;
                public class Subject {
                    public static void run() { /* marker */ }
                }
                """);

        f.editor.updateComment(comment(f), "renamed");

        assertNotNull(f.lastCode, () -> "the edit was refused: " + f.statusMessages);
        assertTrue(f.lastCode.contains("/* renamed */"), f.lastCode);
        assertEquals(List.of(), Arrays.stream(SourceParser.parse(LATEST, f.lastCode).getProblems())
                .map(IProblem::getMessage).toList(), f.lastCode);
    }

    private static Comment comment(EditorFixture f) {
        return (Comment) f.state.getCompilationUnit().orElseThrow().getCommentList().getFirst();
    }
}
