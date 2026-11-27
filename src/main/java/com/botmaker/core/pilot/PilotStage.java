// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.session.Preview;
import com.botmaker.shared.capture.GenericWindow;

/**
 * What the pilot streams and drives for one run: the views that say what it shows, the route they resolve to, the
 * picture grabbed from it, its H.264 encode, and the input a phone replays on it (pilot in core phase 4b-2). Every
 * phone shown a run is served by that run's stage, so two phones on two runs never share a picture, an encoder or a
 * held mouse button.
 *
 * <p>The main stage is the open project's (a run it holds, or its idle views); the other stages are runs this core
 * started beside it, each with its own views, no background session and no telemetry or trace. Frame-loop fields are
 * touched from the {@code pilot-frame} thread only, unless said volatile.
 */
final class PilotStage implements AutoCloseable {

    /**
     * What the most recently pushed frame showed: the route that produced it, the window when it was one window on
     * {@code :0} (a tap raises it first), and its rect, the only region Interact gestures may land in ({@code null}
     * while no frame stands). A gesture belongs to the frame the user touched, not to whatever the route resolver
     * would answer by the time it arrives, so it reads all three at once.
     */
    record Pushed(PilotRoute route, GenericWindow window, PilotInputService.Bounds bounds) {

        /** The same route with no frame to touch. */
        Pushed withoutFrame() {
            return new Pushed(route, null, null);
        }
    }

    /** The run this stage serves, or {@code null} for the open project's. */
    final String run;
    final RunViews views;
    final PilotRoutes routes;
    final TargetCapture capture;
    final PilotVideo video;
    final PilotInputService input = new PilotInputService();

    volatile Pushed pushed = new Pushed(PilotRoute.DESKTOP, null, null);
    /** Whether clients have been told a stream is running, so start/stop are each announced exactly once. */
    boolean videoAnnounced;
    /** How many ticks in a row produced no frame. */
    int emptyFrames;
    /** The sentence sent with the {@code state} message while frames are missing, or {@code null} when they aren't. */
    volatile String emptyReason;
    /** The last surface logged, so a log line is printed on change instead of 24 times a second. */
    String loggedSurface;

    PilotStage(String run, RunViews views, PilotRoutes routes, TargetCapture capture, PilotVideo video) {
        this.run = run;
        this.views = views;
        this.routes = routes;
        this.capture = capture;
        this.video = video;
    }

    /** The stage of a run this core started beside the open bot: no background session, so no rung 1. */
    static PilotStage side(String run, RunViews views, int fps) {
        return new PilotStage(run, views, PilotRoutes.sideOf(views), new TargetCapture(() -> 0L),
                new PilotVideo(Preview.MAX_EDGE, fps));
    }

    /** Lets go of the encoder, the mouse and the emulator connection. */
    @Override
    public void close() {
        video.close(); // the ffmpeg is reaped with its session anyway, but not until the session goes
        input.close();  // never hand the desktop back with a mouse button still down
        routes.close(); // drop any held emulator connection with the loop that was using it
        pushed = pushed.withoutFrame();
    }
}
