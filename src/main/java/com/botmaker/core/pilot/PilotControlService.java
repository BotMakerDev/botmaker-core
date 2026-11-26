// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.plugin.api.Runs;

import java.io.IOException;
import java.util.Optional;

/**
 * Pause/resume for a running bot, used by the remote pilot. Bots are plain OS processes
 * ({@link Runs#pid()}), so pause is a kernel-level {@code SIGSTOP} and resume a
 * {@code SIGCONT} on that pid.
 *
 * <p><b>Crash-free but crude:</b> {@code SIGSTOP} cannot be caught or ignored by the JVM, so this never
 * corrupts the process — but it freezes the bot wherever it happens to be (possibly mid-action), and any
 * wall-clock timing the bot relies on skews across the pause. <b>Unix-only:</b> Windows has no signal
 * equivalent, so {@link #isSupported()} is false there and pause/resume are no-ops that report why.
 */
public final class PilotControlService {

    private final Runs runs;
    private volatile boolean paused;

    public PilotControlService(Runs runs) {
        this.runs = runs == null ? Runs.NONE : runs;
    }

    /** Whether pause/resume can work on this OS (Unix signals). */
    public static boolean isSupported() {
        return !System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public boolean isPaused() {
        return runs.isPaused() || paused;
    }

    /**
     * Through the host when it pauses runs ({@link Runs#canPause()}), so the pilot and the host's run
     * overlay share one pause; signalled here otherwise, for a host whose contract has no pause.
     *
     * @return why the bot could not be paused, or empty when it was
     */
    public synchronized Optional<String> pause() {
        if (runs.canPause()) {
            runs.pause();
            return runs.isPaused() ? Optional.empty() : Optional.of("Could not pause the bot.");
        }
        return signal("STOP", true);
    }

    /** @return why the bot could not be resumed, or empty when it was */
    public synchronized Optional<String> resume() {
        if (runs.isPaused()) {
            runs.resume();
            return runs.isPaused() ? Optional.of("Could not resume the bot.") : Optional.empty();
        }
        return signal("CONT", false);
    }

    private Optional<String> signal(String sig, boolean nextPaused) {
        if (!isSupported()) return Optional.of("Pause/resume is not supported on Windows.");
        var pid = runs.pid();
        if (pid.isEmpty()) return Optional.of("No bot is running.");
        try {
            int code = new ProcessBuilder("kill", "-" + sig, Long.toString(pid.getAsLong()))
                    .inheritIO().start().waitFor();
            if (code != 0) return Optional.of("kill -" + sig + " exited " + code);
            paused = nextPaused;
            return Optional.empty();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Optional.of("Could not signal bot: " + e.getMessage());
        }
    }

    /** Reset pause state when a run ends (the pid is gone; a fresh run starts unpaused). */
    public void onRunStopped() {
        paused = false;
    }
}
