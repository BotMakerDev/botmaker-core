// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.run;

import com.botmaker.plugin.api.CompanionHolder;
import com.botmaker.plugin.api.DeclaredPlugin;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.run.RunCompanion;
import com.botmaker.plugin.api.run.RunContext;
import com.botmaker.plugin.api.run.RunEntry;
import com.botmaker.plugin.host.PluginCoordinate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunEntriesTest {

    public static final class Main {
        public static void main(String[] args) {
        }
    }

    private static RunEntries.Declared declared(String artifact, String title) {
        return new RunEntries.Declared(PluginCoordinate.parse("com.example:" + artifact), artifact, null,
                RunEntry.of(title, Main::main));
    }

    @Test
    void namedIgnoresCaseAndSpaces() {
        RunEntries.Declared explore = declared("sdk", "Explore map");
        List<RunEntries.Declared> all = List.of(explore, declared("sdk", "Check"));
        assertEquals(explore, RunEntries.named(all, "  explore   MAP ").orElseThrow());
        assertTrue(RunEntries.named(all, "Explore").isEmpty());
    }

    static final RunEntry EXPLORE = RunEntry.of("Keep exploring", Main::main);

    /** What every companion a test's plugin made was told, in order, with the instance that heard it. */
    static final List<String> HEARD = new CopyOnWriteArrayList<>();

    public static final class Companion implements CompanionHolder {
        @Override
        public RunCompanion companion() {
            return new RunCompanion() {
                @Override
                public List<RunEntry> serves() {
                    return List.of(EXPLORE);
                }

                @Override
                public void start(RunContext run) {
                    HEARD.add("start " + run.entry().title() + " " + System.identityHashCode(this));
                }

                @Override
                public void stop() {
                    HEARD.add("stop " + System.identityHashCode(this));
                }
            };
        }
    }

    public static final class FakePlugin extends DeclaredPlugin {
        public FakePlugin() {
            super(StudioPlugin.declare().companions(Companion.class));
        }
    }

    private static RunEntries.Companions companions() {
        HEARD.clear();
        return new RunEntries.Companions(() -> List.of(new FakePlugin()), null);
    }

    @Test
    void aCompanionStartsAndStopsWithItsRun() {
        RunEntries.Companions companions = companions();

        companions.asked(EXPLORE, false);
        assertTrue(HEARD.isEmpty(), "nothing before the run's process is up");
        companions.started();
        companions.ended();

        assertEquals(2, HEARD.size(), HEARD.toString());
        assertTrue(HEARD.getFirst().startsWith("start Keep exploring "), HEARD.toString());
        assertEquals("stop " + HEARD.getFirst().substring(HEARD.getFirst().lastIndexOf(' ') + 1), HEARD.get(1));
        companions.ended();
        assertEquals(2, HEARD.size(), "stopped once");
    }

    @Test
    void twoClientsStartOneCompanion() {
        RunEntries.Companions companions = companions();

        companions.asked(EXPLORE, false);
        // Studio and an MCP session both hear the run start; the core's one set starts once.
        companions.started();
        companions.started();
        companions.ended();

        assertEquals(1, HEARD.stream().filter(h -> h.startsWith("start ")).count(), HEARD.toString());
        assertEquals(1, HEARD.stream().filter(h -> h.startsWith("stop ")).count(), HEARD.toString());
    }

    @Test
    void theBotsOwnRunStartsNoCompanion() {
        RunEntries.Companions companions = companions();

        companions.asked(RunEntry.of("Check", Main::main), false);
        companions.started();
        companions.ended();
        companions.asked(null, false);
        companions.started();

        assertTrue(HEARD.isEmpty(), HEARD.toString());
    }

    @Test
    void anAskWhileARunGoesStartsNothingLater() {
        RunEntries.Companions companions = companions();
        companions.asked(null, false);
        companions.started();

        // Refused by the start ("already running"): the bot's run ends and the next start is not Explore's.
        companions.asked(EXPLORE, true);
        companions.ended();
        companions.started();

        assertTrue(HEARD.isEmpty(), HEARD.toString());
    }

    @Test
    void anAskWhileARunCompilesChangesNothing() {
        RunEntries.Companions companions = companions();
        // The bot's main claimed, still compiling: an ask for Explore is refused by the start.
        companions.asked(null, false);
        companions.asked(EXPLORE, true);
        companions.started();
        assertTrue(HEARD.isEmpty(), HEARD.toString());
        companions.ended();

        // Explore claimed, still compiling: an ask for the bot's main is refused, Explore's companion still starts.
        companions.asked(EXPLORE, false);
        companions.asked(null, true);
        companions.started();
        assertFalse(HEARD.isEmpty(), "Explore's companion started");
    }

    @Test
    void oneTitleFromTwoPluginsIsRefusedNamingBoth() {
        List<RunEntries.Declared> all = List.of(declared("sdk", "Check"), declared("other", "check"));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> RunEntries.named(all, "Check"));
        assertTrue(refused.getMessage().contains("com.example:sdk, com.example:other"), refused.getMessage());
    }
}
