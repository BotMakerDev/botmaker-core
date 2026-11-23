// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.parser;

import com.botmaker.java.edit.ExpressionChoice;
import com.botmaker.java.edit.palette.BlockCategory;
import com.botmaker.java.edit.palette.BlockType;
import com.botmaker.java.edit.palette.Initializer;
import com.botmaker.java.types.ResolvedType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A call dropped into a colon case's body lands after its label (2026-11-23). The case's statements are its switch's,
 * and {@code edit_code}'s call insert wrote at the switch's index 0, above {@code case "":}, which JDT refused as
 * "Index -1 out of bounds".
 */
class SwitchCaseCallInsertTest {

    private static final String SOURCE = """
            package com.mybot;
            public class Subject {
                public static void run() {
                    for (String item : new String[0]) {
                        switch (item) {
                        case "":
                            break;
                        default:
                            break;
                        }
                    }
                }
            }
            """;
    private static final String FIRST_CASE =
            "types[0]/bodyDeclarations[0]/body/statements[0]/body/statements[0]/statements[0]";
    private static final String DEFAULT_CASE =
            "types[0]/bodyDeclarations[0]/body/statements[0]/body/statements[0]/statements[2]";

    @Test
    void aPaletteCallLandsInACaseBody() {
        EditorFixture fixture = new EditorFixture(SOURCE);
        BlockType call = new BlockType.LibraryCall("TEST_LIB", "Library Call", BlockCategory.UTILITY,
                Objects.class, "requireNonNull", List.of(new Initializer.StrLit("x")));
        fixture.editor.addStatement(FIRST_CASE, call, 0);
        assertNotNull(fixture.lastCode, String.join("\n", fixture.statusMessages));
        assertTrue(fixture.lastCode.contains("case \"\":\n                Objects.requireNonNull(\"x\");"),
                fixture.lastCode);
    }

    @Test
    void aMethodCallLandsInACaseBody() {
        EditorFixture fixture = new EditorFixture(SOURCE);
        fixture.editor.addMethodCallStatement(DEFAULT_CASE,
                new ExpressionChoice.Method("System", "exit", List.of(ResolvedType.named("int")), true), 0);
        assertNotNull(fixture.lastCode, String.join("\n", fixture.statusMessages));
        assertTrue(fixture.lastCode.contains("default:\n                System.exit(0);"), fixture.lastCode);
    }
}
