// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.plugin.api.run.BackgroundLaunch;
import com.botmaker.session.SessionBackend;
import com.botmaker.session.display.SessionBackends;
import com.botmaker.session.launch.BackgroundLauncher;
import com.botmaker.shared.launch.LaunchSpec;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Background mode of the open project (pilot in core, phase 5): the bot's game started in a private display that the
 * pilot then shows and drives, while the user's real cursor stays free. The plugin names what to start
 * ({@link RunViews#background()}); this brings the display up in the project's one {@link BackgroundLauncher}, which
 * the pilot's routes already read, and which a run is handed by its hand-off arguments.
 *
 * <p>The outcome of a start is kept as a sentence a client shows until the next start or stop; {@code changed} is
 * called when anything a client shows differs.
 */
public final class PilotBackground {

    private final RunViews views;
    private final BackgroundLauncher launcher;
    private final Runnable changed;

    private volatile String message;
    private volatile boolean failed;

    public PilotBackground(RunViews views, Path resourcesRoot, Runnable changed) {
        this.views = views;
        this.launcher = BackgroundLauncher.forProject(resourcesRoot);
        this.changed = changed;
    }

    /** What a client shows now. */
    public PilotState.Background status() {
        boolean offered = views.background().isPresent();
        boolean running = launcher.isRunning();
        SessionBackends.DisplaySize size = running ? launcher.activeSize() : null;
        return new PilotState.Background(offered, running, running ? launcher.activeDisplay() : null,
                running ? launcher.attachedTitle() : null, size == null ? null : size.describe(), message, failed);
    }

    /**
     * Starts the bot's game in a private display; the answer to the client is only whether the start was taken up, the
     * outcome follows in {@link #status()}.
     *
     * @return why nothing was started, empty when the start is under way
     */
    public Optional<String> start() {
        Optional<BackgroundLaunch> wanted = views.background();
        if (wanted.isEmpty()) {
            return Optional.of("This bot has nothing to start in a private display: its settings keep the game on the "
                    + "desktop, it has no launch target, or its target already runs off the desktop.");
        }
        LaunchSpec spec = LaunchSpec.parse(wanted.get().spec());
        if (spec == null) {
            return Optional.of("The launch target “" + wanted.get().spec() + "” is not one this computer can start.");
        }
        SessionBackend backend = SessionBackend.fromId(wanted.get().backend())
                .orElseGet(() -> SessionBackends.preferredBackend(spec));
        if (!SessionBackends.isAvailable(backend)) {
            return Optional.of(backend.binaryName() + " is not installed, and the private display needs it.");
        }
        say(true, "Starting…", false);
        launcher.start(backend, spec, BackgroundLauncher.DEFAULT_WIDTH, BackgroundLauncher.DEFAULT_HEIGHT,
                (ok, text) -> say(ok, text, !ok));
        return Optional.empty();
    }

    /** Takes the private display down and returns the pilot to the real desktop; safe with none running. */
    public void stop() {
        launcher.stop();
        say(true, "Background mode is off.", false);
    }

    private void say(boolean ok, String text, boolean failure) {
        message = text;
        failed = failure && !ok;
        changed.run();
    }
}
