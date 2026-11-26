// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.plugin.api.run.ViewTarget;
import com.botmaker.session.Capability;
import com.botmaker.session.DesktopSession;
import com.botmaker.shared.emulator.EmulatorSurface;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order in which the pilot picks a surface, and the caching that keeps a per-frame question off a platform
 * scan. Every effect is injected, so none of this needs a project on disk or an emulator on the machine.
 */
class PilotRoutesTest {

    /** Records every serial it was asked to open, so the caching can be asserted rather than assumed. */
    private static final class Opener implements Function<String, EmulatorSurface> {
        final List<String> opened = new ArrayList<>();
        /** A serial that resolves to nothing — an emulator no product reports. */
        String unknown;

        @Override
        public EmulatorSurface apply(String serial) {
            opened.add(serial);
            return serial.equals(unknown) ? null : new PilotFakes.RecordingSurface(null);
        }
    }

    /** What a run shows: the emulator of a serial, or the desktop when {@code serial} is null. */
    private static RunViews.Shown showing(String serial) {
        ViewTarget target = serial == null ? ViewTarget.desktop() : ViewTarget.emulator(serial);
        return new RunViews.Shown(Optional.of(target), Optional.empty());
    }

    private static PilotRoutes routes(Supplier<DesktopSession> session, String[] serial, Opener opener) {
        return new PilotRoutes(session, () -> showing(serial[0]), opener);
    }

    /** A holder standing in for the project's {@code BackgroundLauncher}: the pilot asks it on every read. */
    private static final class SessionHolder extends AtomicReference<DesktopSession> {
        Supplier<DesktopSession> asked() {
            return this::get;
        }
    }

    /** No session live — the common case for the emulator and screen rungs below. */
    private static Supplier<DesktopSession> noSession() {
        return new SessionHolder().asked();
    }

    private static PilotFakes.FakeSession liveSession() {
        return new PilotFakes.FakeSession(new PilotFakes.RecordingController(), null, null,
                EnumSet.of(Capability.BACKGROUND_CLICK));
    }

    private static PilotFakes.RecordingSurface surfaceOf(PilotRoute route) {
        return (PilotFakes.RecordingSurface) assertInstanceOf(PilotRoute.Emulator.class, route).surface();
    }

    /** A holder that throws is no session — the rungs below it still decide, and the frame loop lives. */
    @Test
    void aSessionHolderThatThrowsIsNoSession() {
        Opener opener = new Opener();
        Supplier<DesktopSession> broken = () -> {
            throw new IllegalStateException("launcher not ready");
        };

        assertInstanceOf(PilotRoute.Emulator.class,
                routes(broken, new String[]{"127.0.0.1:5555"}, opener).current());
        assertInstanceOf(PilotRoute.Screen.class, routes(broken, new String[]{null}, opener).current());
    }

    @Test
    void aViewOfTheDesktopIsTheScreenRoute() {
        PilotRoute.Screen screen = assertInstanceOf(PilotRoute.Screen.class,
                routes(noSession(), new String[]{null}, new Opener()).current());
        assertEquals(ViewTarget.desktop(), screen.shown());
    }

    @Test
    void anEmulatorTargetBecomesAnEmulatorRoute() {
        Opener opener = new Opener();

        PilotRoute route = routes(noSession(), new String[]{"127.0.0.1:5555"}, opener).current();

        assertInstanceOf(PilotRoute.Emulator.class, route);
        assertEquals(List.of("127.0.0.1:5555"), opener.opened);
    }

    /** Nothing to show is nothing streamed: the view's reason goes to the phone, and never the user's screen. */
    @Test
    void noTargetIsMissingWithTheViewsReason() {
        PilotRoutes routes = new PilotRoutes(noSession(),
                () -> new RunViews.Shown(Optional.empty(), Optional.of("The game window is closed.")),
                new Opener());

        PilotRoute.Missing missing = assertInstanceOf(PilotRoute.Missing.class, routes.current());
        assertEquals("The game window is closed.", missing.reason());
    }

    @Test
    void aFailingLookupIsMissingRatherThanAThrow() {
        PilotRoutes routes = new PilotRoutes(noSession(), () -> {
            throw new IllegalStateException("plugins not bound yet");
        }, serial -> null);

        PilotRoute.Missing missing = assertInstanceOf(PilotRoute.Missing.class, routes.current());
        assertTrue(missing.reason().contains("No plugin"), missing.reason());
    }

    /**
     * A nested session outranks an emulator: the user explicitly asked for background mode and it launched the
     * game itself. The emulator connection is dropped rather than left held behind a route nobody is using.
     * Nobody tells the routes about the session: it appears in the holder, and the next frame's question finds it.
     */
    @Test
    void aLiveSessionOutranksAnEmulatorAndReleasesIt() {
        SessionHolder holder = new SessionHolder();
        PilotRoutes routes = routes(holder.asked(), new String[]{"127.0.0.1:5555"}, new Opener());
        PilotFakes.RecordingSurface surface = surfaceOf(routes.current());

        holder.set(liveSession());

        assertInstanceOf(PilotRoute.Session.class, routes.current());
        assertTrue(surface.closed, "the emulator connection must not stay open behind a session route");
    }

    /** A session that goes away (stopped, or its display died) returns the pilot to the view on the next frame. */
    @Test
    void aSessionThatLeavesTheHolderFallsBackToTheView() {
        SessionHolder holder = new SessionHolder();
        holder.set(liveSession());
        PilotRoutes routes = routes(holder.asked(), new String[]{null}, new Opener());
        assertInstanceOf(PilotRoute.Session.class, routes.current());

        holder.set(null);

        assertInstanceOf(PilotRoute.Screen.class, routes.current());
    }

    /**
     * The Waydroid case: gamescope hosts a Wayland-only client, so the session's {@code :N} root has nothing on
     * it and an X11 grab of it returns black forever. The emulator the view names is the route that can actually
     * see those pixels, and it must win.
     */
    @Test
    void aSessionThatCannotBeCapturedOverX11LosesToTheViewsEmulator() {
        Opener opener = new Opener();
        SessionHolder holder = new SessionHolder();
        PilotFakes.FakeSession wayland = liveSession();
        wayland.x11Capturable = false;
        holder.set(wayland);

        PilotRoute route = routes(holder.asked(), new String[]{"127.0.0.1:5555"}, opener).current();

        assertInstanceOf(PilotRoute.Emulator.class, route);
        assertEquals(List.of("127.0.0.1:5555"), opener.opened);
    }

    /**
     * Losing rung 1 is a demotion, not a skip. With no emulator to fall to, an uncapturable session still beats
     * the real desktop — streaming the user's screen to a possibly-public URL and replaying taps on it is a
     * worse answer than a black frame, and the server says why rather than showing it.
     */
    @Test
    void anUncapturableSessionStillOutranksTheUsersRealDesktop() {
        SessionHolder holder = new SessionHolder();
        PilotFakes.FakeSession wayland = liveSession();
        wayland.x11Capturable = false;
        holder.set(wayland);

        assertInstanceOf(PilotRoute.Session.class,
                routes(holder.asked(), new String[]{null}, new Opener()).current());
    }

    /** The ordinary gamescope case — an X11 game in the session — is untouched: rung 1 still wins outright. */
    @Test
    void anX11CapturableSessionStillWinsOverAnEmulator() {
        Opener opener = new Opener();
        SessionHolder holder = new SessionHolder();
        holder.set(liveSession());

        assertInstanceOf(PilotRoute.Session.class,
                routes(holder.asked(), new String[]{"127.0.0.1:5555"}, opener).current());
        assertEquals(List.of(), opener.opened, "the emulator must not even be opened behind a winning session");
    }

    /** An emulator no product reports is a missing surface: the user's screen never stands in for it. */
    @Test
    void anUnresolvableEmulatorStreamsNothingAndSaysWhich() {
        Opener opener = new Opener();
        opener.unknown = "127.0.0.1:9999";

        PilotRoute.Missing missing = assertInstanceOf(PilotRoute.Missing.class,
                routes(noSession(), new String[]{"127.0.0.1:9999"}, opener).current());
        assertTrue(missing.reason().contains("127.0.0.1:9999"), missing.reason());
    }

    /** The scan behind an open is too dear for a frame loop, so a miss is left alone for a while. */
    @Test
    void aMissedEmulatorIsAskedForAgainOnlyAfterAWhile() {
        Opener opener = new Opener();
        opener.unknown = "127.0.0.1:9999";
        AtomicLong now = new AtomicLong(10_000);
        PilotRoutes routes = new PilotRoutes(noSession(), () -> showing("127.0.0.1:9999"), opener, now::get);

        routes.current();
        now.addAndGet(PilotRoutes.MISS_RETRY_MS - 1);
        routes.current();
        assertEquals(1, opener.opened.size());

        now.addAndGet(1);
        routes.current();
        assertEquals(2, opener.opened.size());
    }

    /** The surface is opened once and reused until the view names another serial. */
    @Test
    void theSurfaceIsOpenedOncePerSerialAndReopenedWhenItChanges() {
        Opener opener = new Opener();
        String[] serial = {"127.0.0.1:5555"};
        PilotRoutes routes = routes(noSession(), serial, opener);

        PilotFakes.RecordingSurface first = surfaceOf(routes.current());
        assertSame(first, surfaceOf(routes.current()));
        assertEquals(List.of("127.0.0.1:5555"), opener.opened, "a second frame must not re-scan");

        serial[0] = "127.0.0.1:5557";
        surfaceOf(routes.current());
        assertEquals(List.of("127.0.0.1:5555", "127.0.0.1:5557"), opener.opened);
        assertTrue(first.closed, "the old connection is closed when the view points somewhere else");
    }

    @Test
    void closingReleasesTheHeldConnection() {
        PilotRoutes routes = routes(noSession(), new String[]{"127.0.0.1:5555"}, new Opener());
        PilotFakes.RecordingSurface surface = surfaceOf(routes.current());

        routes.close();

        assertTrue(surface.closed);
    }
}
