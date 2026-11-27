// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.core.run.claims.RunRegistry;
import com.botmaker.core.run.claims.RunRegistry.LiveRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryRunsTest {

    @TempDir
    Path dir;

    private RegistryRuns over(RunRegistry registry, OptionalLong viewed) {
        return new RegistryRuns(registry, () -> viewed);
    }

    @Test
    void aRunsRowThatHoldsThisProcessIsNeverSignalled() {
        RunRegistry registry = new RunRegistry(dir, RunRegistry.Alive.PROCESSES);
        registry.hold(LiveRun.ofThisProcess("run-1", "Farm", List.of()));
        RegistryRuns runs = over(registry, OptionalLong.empty());

        assertEquals(List.of("run-1"), runs.live().stream().map(PilotRuns.Listed::id).toList());
        assertTrue(runs.pause("run-1").isPresent());
        assertTrue(runs.stop("run-1").isPresent());
        assertTrue(ProcessHandle.current().isAlive());
    }

    @Test
    void anUnlistedRunAndAnOpenProjectsRunAreRefused() {
        RunRegistry registry = new RunRegistry(dir, RunRegistry.Alive.PROCESSES);
        registry.hold(LiveRun.ofThisProcess("run-1", "Farm", List.of()));
        RegistryRuns runs = over(registry, OptionalLong.of(ProcessHandle.current().pid()));

        assertTrue(runs.stop("nope").orElseThrow().contains("not running"));
        assertEquals("run-1", runs.viewed().orElseThrow());
        assertTrue(runs.resume("run-1").orElseThrow().contains("open project"));
    }
}
