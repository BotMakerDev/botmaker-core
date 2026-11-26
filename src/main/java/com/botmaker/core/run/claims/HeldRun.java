// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.run.claims;

import com.botmaker.core.run.claims.RunRegistry.LiveRun;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One run that was dealt nothing, live in the registry from its start to its end (spec
 * {@code 2026-11-26-run-places-design.md} §5): ▶ Run, {@code run_bot}, a plugin's own run and a side run. It holds no
 * element, so its start is never refused; what it plays on it claims itself, and that claim is checked against every
 * other run's, pooled ones included. A debug session and ▶ Try are not held: their claims are granted unchecked.
 */
public final class HeldRun implements AutoCloseable {

    private final RunRegistry registry;
    private final String runId;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final List<Path> forgotten = new CopyOnWriteArrayList<>();

    private HeldRun(RunRegistry registry, String runId) {
        this.registry = registry;
        this.runId = runId;
    }

    /**
     * A run of {@code bot} made live in {@code registry}, held by this process until {@link #movedTo}; empty when the
     * registry cannot be written, and the run then goes with its claims granted, as it did before every run was held.
     */
    public static Optional<HeldRun> open(RunRegistry registry, String bot) {
        String runId = RunRegistry.newRunId();
        try {
            registry.hold(LiveRun.ofThisProcess(runId, bot, List.of()));
            return Optional.of(new HeldRun(registry, runId));
        } catch (UncheckedIOException | IllegalStateException e) {
            System.err.println("This run's claims are not checked against other runs: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Its id in the registry, which the run's agent serves its claims under. */
    public String runId() {
        return runId;
    }

    /** The folder of the live runs its claims are checked in. */
    public Path registry() {
        return registry.folder();
    }

    /**
     * The run's own process holds it now: it stays live while the bot runs, whatever this process does. A registry
     * that cannot be written leaves it held by this process, which frees it at the run's end all the same.
     */
    public void movedTo(ProcessHandle process) {
        try {
            registry.locked(locked -> locked.moveTo(runId, process));
        } catch (UncheckedIOException | IllegalStateException e) {
            System.err.println("This run stays held by BotMaker, not by its own process: " + e.getMessage());
        }
    }

    /** {@code file}, deleted when the run is closed: its plan, which the bot's JVM reads once at its start. */
    public Path forgetsAtEnd(Path file) {
        forgotten.add(file);
        return file;
    }

    /** Frees it and every claim it made, and deletes its files; once. */
    @Override
    public void close() {
        if (!open.getAndSet(false)) return;
        registry.release(runId);
        for (Path file : forgotten) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                System.err.println("Could not delete " + file + ": " + e.getMessage());
            }
        }
    }
}
