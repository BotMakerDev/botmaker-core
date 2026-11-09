package com.botmaker.core.parser;

import com.botmaker.java.edit.BlockId;
import com.botmaker.java.edit.palette.BlockCatalog;
import com.botmaker.java.grammar.SourceParser;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Statement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.botmaker.java.parse.JavaLevel.LATEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A statement dropped on the line after a comment, by each gesture that drops one: it lands after the comment, the
 * comment stays where it was, and the statement is laid out like its neighbours (the audit's J1, 2026-11-09: it was
 * the node's debug print, spliced in as text at the comment's end).
 */
class InsertAfterCommentTest {

    private static final String SOURCE = """
            public class Subject {
                public static void run() {
                    System.out.println("a");
                    // note
                    System.out.println(String.valueOf(2));
                }

                public static void only() {
                    // alone
                }
            }
            """;

    private static final String INDENT = "        ";

    /** The line of {@code code} after the one holding {@code comment}. */
    private static String lineAfter(String code, String comment) {
        List<String> lines = code.lines().toList();
        for (int i = 0; i < lines.size() - 1; i++) {
            if (lines.get(i).equals(INDENT + comment)) return lines.get(i + 1);
        }
        throw new AssertionError(comment + " is no longer a line of its own:\n" + code);
    }

    private static String written(EditorFixture f) {
        assertNotNull(f.lastCode, () -> "the edit was refused: " + f.statusMessages);
        assertFalse(SourceParser.hasSyntaxErrors(SourceParser.parse(LATEST, f.lastCode)), f.lastCode);
        return f.lastCode;
    }

    @Test
    void anInsertedBlockGoesUnderTheComment() {
        EditorFixture f = new EditorFixture(SOURCE);
        f.editor.addStatement(BlockId.of(f.body("run")), BlockCatalog.PRINT, 2);
        String line = lineAfter(written(f), "// note");
        assertTrue(line.startsWith(INDENT + "System.out.println(") && !line.contains("valueOf"), line);
    }

    @Test
    void aBlockInsertedInABodyOfOnlyACommentGoesUnderIt() {
        EditorFixture f = new EditorFixture(SOURCE);
        f.editor.addStatement(BlockId.of(f.body("only")), BlockCatalog.PRINT, 1);
        assertTrue(lineAfter(written(f), "// alone").startsWith(INDENT + "System.out.println("), f.lastCode);
    }

    @Test
    void aPasteGoesUnderTheComment() {
        EditorFixture f = new EditorFixture(SOURCE);
        f.editor.pasteCode(BlockId.of(f.body("run")), 2, "System.out.println(\"pasted\");");
        assertEquals(INDENT + "System.out.println(\"pasted\");", lineAfter(written(f), "// note"));
    }

    @Test
    void aStatementTheHostBuiltGoesUnderTheComment() {
        EditorFixture f = new EditorFixture(SOURCE);
        Statement built = (Statement) SourceParser.statements(LATEST, "System.out.println(\"copied\");")
                .statements().getFirst();
        f.editor.insertStatement(BlockId.of(f.body("run")), 2, built, List.of());
        assertEquals(INDENT + "System.out.println(\"copied\");", lineAfter(written(f), "// note"));
    }

    @Test
    void aStatementMovedPastTheCommentUnderItLandsUnderTheComment() {
        EditorFixture f = new EditorFixture(SOURCE);
        Block run = f.body("run");
        Statement first = (Statement) run.statements().getFirst();
        f.editor.moveStatement(BlockId.of(first), BlockId.of(run), BlockId.of(run), 2);
        String code = written(f);
        assertEquals(INDENT + "System.out.println(\"a\");", lineAfter(code, "// note"));
        assertTrue(code.contains("public static void run() {\n" + INDENT + "// note\n"), code);
    }

    @Test
    void aValueLiftedOutOfItsSlotGoesUnderTheComment() {
        EditorFixture f = new EditorFixture(SOURCE);
        Block run = f.body("run");
        MethodInvocation print = (MethodInvocation) ((ExpressionStatement) run.statements().get(1)).getExpression();
        Expression value = (Expression) print.arguments().getFirst();
        f.editor.moveExpressionToStatement(value, BlockId.of(run), 2);
        assertEquals(INDENT + "String.valueOf(2);", lineAfter(written(f), "// note"));
    }
}
