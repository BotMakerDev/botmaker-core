package com.botmaker.core.assist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A plugin's tool served by the core with no Studio: no screen, and a tool that reaches for one says so. */
class PluginToolsTest {

    @Test
    void aToolThatReachesForJavaFxNeedsStudio() {
        assertTrue(PluginTools.needsScreen(new IllegalStateException("Toolkit not initialized")));
        assertTrue(PluginTools.needsScreen(new RuntimeException("wrapped",
                new NoClassDefFoundError("javafx/scene/Node"))));
        assertFalse(PluginTools.needsScreen(new IllegalArgumentException("No such setting: speed")));
        assertFalse(PluginTools.needsScreen(new IllegalStateException("C:/javafx-games/x.png is missing")),
                "a message that only mentions javafx");
        assertTrue(PluginTools.needsStudio("botmaker_setup").startsWith("botmaker_setup needs BotMaker Studio open"));
    }

    @Test
    void theCoresContextHasNoScreen() {
        var context = PluginTools.screenless(null);
        assertTrue(context.frame().isEmpty());
        assertTrue(context.watchedArea().isEmpty());
    }
}
