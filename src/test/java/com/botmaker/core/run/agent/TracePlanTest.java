package com.botmaker.core.run.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which classes a run's trace rewrites: the bot's class folder, and the folders a reload compiles it into. */
class TracePlanTest {

    @TempDir
    Path bot;

    private static URL url(Path folder) throws MalformedURLException {
        return folder.toUri().toURL();
    }

    @Test
    void aReloadsGenerationIsTheBots() throws MalformedURLException {
        TracePlan plan = TracePlan.of(Set.of(bot.resolve("target/classes")), Set.of());

        assertTrue(plan.isBotClass(url(bot.resolve("target/classes"))));
        assertTrue(plan.isBotClass(url(bot.resolve("target/botmaker-explore/gen-3"))));
        assertFalse(plan.isBotClass(url(bot.resolve("target/botmaker-explore"))), "the generations' folder itself");
        assertFalse(plan.isBotClass(url(bot.resolve("target/test-classes"))));
        assertFalse(plan.isBotClass(url(bot.resolve("other/botmaker-explore/gen-1"))));
    }
}
