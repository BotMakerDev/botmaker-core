package com.botmaker.core.plugin;

import com.botmaker.plugin.api.run.RunEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostRunsTest {

    public static final class Explore {
        public static void main(String[] args) {
        }
    }

    private static final RunEntry EXPLORE = RunEntry.of("Keep exploring", Explore::main).offBar();

    /** What the host was asked, one line per ask. */
    private final List<String> asked = new ArrayList<>();

    private final HostRuns.Requests requests = new HostRuns.Requests() {
        @Override
        public void start() {
            asked.add("start");
        }

        @Override
        public void startEntry(String title, String mainClass) {
            asked.add("entry " + title + " " + mainClass);
        }

        @Override
        public void stop() {
            asked.add("stop");
        }

        @Override
        public void status(String message) {
            asked.add("status " + message);
        }
    };

    private HostRuns runs(boolean declared) {
        return HostRuns.install(requests, OptionalLong::empty, null, entry -> declared && entry.equals(EXPLORE));
    }

    @AfterEach
    void clear() {
        HostRuns.clear();
    }

    @Test
    void startEntryAsksTheHost() {
        runs(true).start(EXPLORE);
        assertEquals(List.of("entry Keep exploring " + Explore.class.getName()), asked);
    }

    @Test
    void undeclaredEntryIsRefused() {
        runs(false).start(EXPLORE);
        assertEquals(1, asked.size());
        assertTrue(asked.getFirst().startsWith("status Can't start Keep exploring"), asked.getFirst());
    }

    @Test
    void refusedWhileRunning() {
        HostRuns runs = runs(true);
        runs.ran(true);
        runs.start(EXPLORE);
        assertEquals(1, asked.size());
        assertTrue(asked.getFirst().contains("a run is going"), asked.getFirst());
    }
}
