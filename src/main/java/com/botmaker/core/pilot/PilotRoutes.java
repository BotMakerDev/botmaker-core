// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.plugin.api.run.ViewTarget;
import com.botmaker.session.DesktopSession;
import com.botmaker.session.launch.BackgroundLauncher;
import com.botmaker.shared.emulator.AdbEmulatorSurface;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorSurface;
import com.botmaker.shared.emulator.Platforms;
import com.botmaker.shared.emulator.ScrcpyEmulatorSurface;

import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Decides the pilot's {@link PilotRoute} and owns the emulator connection that route needs.
 *
 * <p>In order:
 * <ol>
 *   <li>a live nested {@code :N} {@link DesktopSession} <b>whose pixels are on X11</b>
 *       ({@link DesktopSession#x11Capturable()}) — the user explicitly asked for background mode, and it
 *       launched the game itself, so nothing outranks it;</li>
 *   <li>the emulator the run's view names ({@link RunViews#shown});</li>
 *   <li>a live session that lost rung 1 — see below;</li>
 *   <li>the window, monitor or desktop the view names;</li>
 *   <li>otherwise nothing, with the view's reason.</li>
 * </ol>
 *
 * <p><b>Rung 1's condition is the Waydroid case.</b> A gamescope session can host a Wayland-only client whose
 * surface never reaches its embedded Xwayland, leaving a display with no X11 client mapped on it at all — every
 * grab succeeds and every grab is black. Preferring a live session regardless would suppress the one route that
 * <em>can</em> see those pixels (the emulator's ADB surface). A session that loses rung 1 is <b>demoted, not
 * skipped</b>: rung 3 hands it back ahead of the user's screen, because streaming that screen to a
 * possibly-public URL and replaying taps on it is a worse answer than a black frame.
 *
 * <p>An emulator that can't be resolved or reached is {@link PilotRoute.Missing}: nothing is streamed and the
 * phone is told why. The user's screen never stands in for it, since the pilot may be reachable over a public
 * address.
 *
 * <p>The resolved surface is <b>cached</b> and only rebuilt when the named serial changes. Resolution runs a
 * full {@code Platforms.discoverAll()} scan (registry reads, console-tool calls) and the caller is a frame loop,
 * so doing it per frame would cost more than the frame.
 *
 * <p>Constructed with its three effects injected — which session is live, what the run shows, and how a surface
 * is opened — so the ordering above can be tested without a project on disk or an emulator on the machine.
 * {@link #of} is the production wiring.
 */
public final class PilotRoutes implements AutoCloseable {

    private final Supplier<DesktopSession> session;
    private final Supplier<RunViews.Shown> shown;
    private final Function<String, EmulatorSurface> open;

    /** The live emulator surface and the serial it was opened for; both null when the route isn't an emulator. */
    private EmulatorSurface surface;
    private String openedFor;

    /** How long an emulator that could not be opened is left alone before the scan is run for it again. */
    static final long MISS_RETRY_MS = 2000;

    private final LongSupplier clock;
    /** The serial that could not be opened, and when; null when the last one asked for opened. */
    private String missedFor;
    private long missedAt;

    public PilotRoutes(Supplier<DesktopSession> session, Supplier<RunViews.Shown> shown,
                       Function<String, EmulatorSurface> open) {
        this(session, shown, open, System::currentTimeMillis);
    }

    PilotRoutes(Supplier<DesktopSession> session, Supplier<RunViews.Shown> shown,
                Function<String, EmulatorSurface> open, LongSupplier clock) {
        this.session = session;
        this.shown = shown;
        this.open = open;
        this.clock = clock;
    }

    /**
     * The production wiring over a project: the session is the one {@link BackgroundLauncher} holds in this
     * process for {@code resourcesRoot}, and a surface is one ADB connection to the discovered emulator.
     */
    static PilotRoutes of(Path resourcesRoot, RunViews views) {
        return new PilotRoutes(BackgroundLauncher.forProject(resourcesRoot)::session, views::shown,
                PilotRoutes::openAdbSurface);
    }

    /** The routes of a run started beside the open bot: what its views show, never a background session. */
    static PilotRoutes sideOf(RunViews views) {
        return new PilotRoutes(() -> null, views::shown, PilotRoutes::openAdbSurface);
    }

    /** The route to stream and drive right now. Cheap enough for a frame: two reads plus, at most, a scan. */
    public synchronized PilotRoute current() {
        DesktopSession live = safeSession();
        if (live != null && safeX11Capturable(live)) {
            releaseSurface(); // a nested session outranks the emulator; don't hold ADB open behind it
            return new PilotRoute.Session(live);
        }
        RunViews.Shown now = safeShown();
        ViewTarget target = now == null ? null : now.target().orElse(null);
        if (target instanceof ViewTarget.Emulator(String serial)) {
            EmulatorSurface opened = surfaceFor(serial);
            if (opened != null) return new PilotRoute.Emulator(opened);
            return live != null ? new PilotRoute.Session(live) : new PilotRoute.Missing(
                    "The emulator “" + serial + "” is not running or cannot be reached.");
        }
        releaseSurface();
        // A live session outranks the user's screen even when nothing here can read its pixels. A session route
        // that yields nothing is handled downstream: no frame, and the phone is told why.
        if (live != null) return new PilotRoute.Session(live);
        if (target != null) return new PilotRoute.Screen(target);
        return new PilotRoute.Missing(now == null ? NOTHING_SHOWN : now.reason().orElse(NOTHING_SHOWN));
    }

    private static final String NOTHING_SHOWN = "No plugin says what to show of this bot.";

    private EmulatorSurface surfaceFor(String serial) {
        if (surface != null && serial.equals(openedFor)) return surface;
        releaseSurface();
        // Opening runs a discovery of every platform, too dear to repeat on every frame of a stopped emulator.
        if (serial.equals(missedFor) && clock.getAsLong() - missedAt < MISS_RETRY_MS) return null;
        surface = open.apply(serial);
        openedFor = surface == null ? null : serial;
        missedFor = surface == null ? serial : null;
        missedAt = clock.getAsLong();
        return surface;
    }

    /** A session that throws when asked is one we can't vouch for; treat it as capturable and let the grab decide. */
    private static boolean safeX11Capturable(DesktopSession live) {
        try {
            return live.x11Capturable();
        } catch (Exception e) {
            return true;
        }
    }

    /** Drops the held emulator connection, if any. The pilot server calls this when it shuts down. */
    @Override
    public synchronized void close() {
        releaseSurface();
    }

    private void releaseSurface() {
        if (surface != null) {
            try {
                surface.close();
            } catch (Throwable ignored) {
                // A surface whose connection already died closes noisily; that is not a shutdown failure.
            }
            surface = null;
        }
        openedFor = null;
    }

    /** A holder that answers badly is no session: the frame loop must not die of it, and the rungs go on. */
    private DesktopSession safeSession() {
        try {
            return session.get();
        } catch (Exception e) {
            return null;
        }
    }

    private RunViews.Shown safeShown() {
        try {
            return shown.get();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * One surface for the discovered emulator at this adb address, or {@code null} when there is none.
     *
     * <p>Always the scrcpy surface, never the bare ADB one — <b>including for emulators</b>:
     * {@link ScrcpyEmulatorSurface} <em>is</em> an {@link AdbEmulatorSurface} with a faster path tried in front
     * of it, so asking for the fast one can only be better. Where the fast path can't start, one push attempt is
     * made in front of the plain path, retried at most every 30 seconds.
     */
    private static EmulatorSurface openAdbSurface(String serial) {
        for (EmulatorInstance instance : Platforms.discoverAll()) {
            if (serial.equals(instance.endpoint())) return new ScrcpyEmulatorSurface(instance);
        }
        return null;
    }
}
