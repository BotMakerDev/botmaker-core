package com.botmaker.core.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectLockTest {

    @TempDir
    Path project;

    @Test
    void aDeadOwnersLockIsTaken() throws Exception {
        Process gone = new ProcessBuilder("true").start();
        gone.waitFor();
        Path file = project.resolve(".botmaker").resolve(ProjectLock.NAME);
        Files.createDirectories(file.getParent());
        Files.writeString(file, Long.toString(gone.pid()));

        try (ProjectLock ignored = ProjectLock.take(project)) {
            assertEquals(ProcessHandle.current().pid(), ProjectLock.owner(project).orElseThrow());
        }
    }

    @Test
    void aHeldProjectIsRefusedNamingItsOwnerUntilLetGo() throws Exception {
        ProjectLock first = ProjectLock.take(project);
        ProjectLock.Held held = assertThrows(ProjectLock.Held.class, () -> ProjectLock.take(project));
        assertEquals(ProcessHandle.current().pid(), held.owner().orElseThrow());
        first.close();
        ProjectLock.take(project).close();
    }
}
