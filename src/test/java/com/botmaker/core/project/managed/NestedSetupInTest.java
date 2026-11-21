// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.project.managed;

import com.botmaker.java.grammar.JavaValue;
import com.botmaker.plugin.api.source.ManagedValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A batch's setup write on a holder found earlier (2026-11-21): written only while that class is still the one
 * marked with the set's id and the constant's class still has a setup of one return, so a stale hint writes nothing.
 */
class NestedSetupInTest {

    private static final String ID = ManagedValue.idOf(TestValue.Id.SCREENS);

    private static final List<ManagedValue<?>> KNOWN = List.of(ManagedValue.openSet(TestValue.Id.SCREENS)
            .ofClasses(TestScreen.class)
            .fields(TestScreen.Field.class, TestScreen::field)
            .setup(TestScreen::setup)
            .referredToAs(TestScreen::named)
            .in(TestValue.Holder.SCREENS).because("Mine."));

    private static final String SCREENS = """
            package com.bot.plugins.sdk;

            import com.botmaker.core.project.managed.TestValue;

            @TestValue(TestValue.Id.SCREENS)
            public final class Screens {

                public static final class Home {
                    public Setup setup() {
                        return Setup.of();
                    }
                }

                public static final Home HOME = new Home();
            }
            """;

    @Test
    void theHolderStillMarkedIsWritten() {
        String written = NestedClasses.setupSetIn(SCREENS, ID, "Screens", "HOME", "setup", java("Setup.of().at(null)"),
                KNOWN);

        assertTrue(written.contains("return Setup.of().at(null);"), written);
    }

    @Test
    void aHolderNoLongerThereOrMarkedSoWritesNothing() {
        JavaValue value = java("Setup.of().at(null)");

        assertNull(NestedClasses.setupSetIn(SCREENS, ManagedValue.idOf(TestValue.Id.PICTURES), "Screens", "HOME",
                "setup", value, KNOWN), "another set's id");
        assertNull(NestedClasses.setupSetIn(SCREENS, ID, "Renamed", "HOME", "setup", value, KNOWN), "a holder renamed");
        assertNull(NestedClasses.setupSetIn(SCREENS, ID, "Screens", "GONE", "setup", value, KNOWN), "a constant gone");
        assertNull(NestedClasses.setupSetIn(SCREENS.replace("@TestValue(TestValue.Id.SCREENS)", ""), ID, "Screens",
                "HOME", "setup", value, KNOWN), "the mark taken off");
        assertNull(NestedClasses.setupSetIn(SCREENS.replace("public static final Home HOME",
                "public static final int MAX = 3;\n    public static final Home HOME"), ID, "Screens", "HOME", "setup",
                value, KNOWN), "a helper constant makes it a plain set");
        assertNull(NestedClasses.setupSetIn(SCREENS.replace("HOME = new Home();", "HOME = new Home(), AWAY = new Home();"),
                ID, "Screens", "HOME", "setup", value, KNOWN), "a constant declared with another");
    }

    @Test
    void theOneFileWriteStillLeavesAMissingSetupAsItWas() {
        assertEquals(SCREENS, NestedClasses.setupSet(SCREENS, "Screens", "GONE", "setup", java("Setup.of()")));
    }

    private static JavaValue java(String source) {
        return new JavaValue(JavaValue.parse(source).orElseThrow().node(), List.of(), source);
    }
}
