// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.plugin.api.run.ViewTarget;
import com.botmaker.session.DesktopSession;
import com.botmaker.session.Preview;
import com.botmaker.session.PreviewFrame;
import com.botmaker.session.launch.BackgroundLauncher;
import com.botmaker.session.remote.WindowIds;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.shared.capture.RobotCapture;
import com.botmaker.shared.capture.ScreenCapture;
import com.botmaker.shared.emulator.EmulatorSurface;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Grabs one non-intrusive frame of a {@link PilotRoute}, plus the JPEG encoding used to ship it. Split out from
 * the server so the capture/scale/encode path is testable on its own.
 *
 * <p>A nested session's {@code :N} screen or an emulator's framebuffer <em>is</em> the surface; on the real
 * {@code :0} the route names the window, monitor or desktop. A window that is not there is no frame, never the
 * desktop: the screen is streamed only when the view names the desktop or a monitor ({@link #whyNothing}).
 * Window targets use shared JNA {@code captureWindow} (no focus, prompt-free on X11/XWayland); screen targets
 * fall back to AWT {@link Robot} (limited on Wayland).
 */
final class TargetCapture {

    /**
     * A captured frame plus the absolute surface origin/size its pixel (0,0) maps to.
     *
     * <p>{@code img} is {@code null} when the frame never existed as pixels on this side — a nested session
     * encodes its own preview in the process that holds the display (see {@link Resolved#jpeg()}), and decoding
     * it here purely to satisfy this field would add a codec pass. The rect is the half every consumer needs, and
     * it is present either way.
     *
     * <p>{@code window} is set when the frame is one window on {@code :0}, so a gesture can be kept off whatever
     * covers it; {@code null} for every other surface.
     */
    record Capture(BufferedImage img, int sx, int sy, int sw, int sh, GenericWindow window) {
        Capture(BufferedImage img, int sx, int sy, int sw, int sh) {
            this(img, sx, sy, sw, sh, null);
        }
    }

    /**
     * A frame together with <b>the route it was actually taken on</b> — the two halves of one fact, returned as
     * one value so they cannot disagree: a tap is clamped to these bounds and replayed on this route.
     */
    record Resolved(PilotRoute route, Capture cap, byte[] jpeg) {

        /** A frame that is still pixels here; the encode happens when someone asks for {@link #bytes()}. */
        Resolved(PilotRoute route, Capture cap) {
            this(route, cap, null);
        }

        /**
         * The frame as JPEG bytes, encoding it now if it wasn't encoded at the source. {@code null} means the
         * encode failed, which the caller treats exactly like a missed grab.
         */
        byte[] bytes() {
            return jpeg != null ? jpeg : Preview.jpeg(cap.img(), Preview.MAX_EDGE, Preview.QUALITY);
        }
    }

    /** The live session's host window on {@code :0}, or {@code 0} — see {@link #captureWindowTarget}. */
    private final LongSupplier sessionHostWindow;

    /** The open window of an id a view gave ({@link ViewTarget.Window#id}), or {@code null} when it is not open. */
    private final Function<String, GenericWindow> windowOfId;

    TargetCapture(LongSupplier sessionHostWindow) {
        this(sessionHostWindow, TargetCapture::openWindow);
    }

    TargetCapture(LongSupplier sessionHostWindow, Function<String, GenericWindow> windowOfId) {
        this.sessionHostWindow = sessionHostWindow;
        this.windowOfId = windowOfId;
    }

    /** The production wiring: the host window comes from the project's one {@link BackgroundLauncher}. */
    static TargetCapture of(Path resourcesRoot) {
        return new TargetCapture(BackgroundLauncher.forProject(resourcesRoot)::hostWindowId);
    }

    /**
     * Grabs the frame to preview this tick, on the surface {@code route} names, and reports which route it came
     * from — see {@link Resolved}. {@code null} means "no frame this tick"; the client shows its last one and
     * the pilot's published bounds are left untouched.
     *
     * <p><b>A route never falls back to the desktop.</b> A {@link PilotRoute.Session} or
     * {@link PilotRoute.Emulator} exists precisely because the bot is not on the user's desktop, so a failed grab
     * there resolves to nothing rather than to {@code :0}: a pilot is reachable over a public Funnel URL, and
     * "the emulator hiccuped" is not consent to stream a desktop.
     */
    Resolved resolve(PilotRoute route) {
        return switch (route) {
            case PilotRoute.Session(DesktopSession s) -> {
                Resolved encoded = encodedSession(route, s);
                yield encoded != null ? encoded : wrap(route, captureSession(s));
            }
            case PilotRoute.Emulator(EmulatorSurface surface) -> wrap(route, captureEmulator(surface));
            case PilotRoute.Screen(ViewTarget shown) -> wrap(route, captureScreen(shown));
            case PilotRoute.Missing ignored -> null;
        };
    }

    private static Resolved wrap(PilotRoute route, Capture cap) {
        return cap == null ? null : new Resolved(route, cap);
    }

    private Capture captureScreen(ViewTarget shown) {
        return switch (shown) {
            case ViewTarget.Window window -> captureWindowTarget(safeWindow(window.id()));
            case ViewTarget.Monitor monitor -> captureBounds(screenBounds(monitor.index()));
            case ViewTarget.Desktop ignored -> captureBounds(virtualBounds());
            // An emulator's pixels are the emulator route's and no screen's.
            case ViewTarget.Emulator ignored -> null;
        };
    }

    /** The sentence the phone is told while a screen route has no frame. */
    static String whyNothing(ViewTarget shown) {
        if (shown instanceof ViewTarget.Window window) {
            String name = window.title().isBlank() ? "of the game" : "“" + window.title() + "”";
            return "The window " + name + " is not open on the computer.";
        }
        return "The screen could not be captured.";
    }

    /**
     * Ask a nested session to encode a frame of itself and take the bytes as they are: the pixels are never
     * decoded here. The rect comes back with the bytes and is not assumed — a compositing backend never paints
     * its root, so the frame that has pixels is a <em>window</em> (see {@link com.botmaker.session.PreviewFrame}).
     *
     * <p>{@code null} means "no fast path", and the caller falls back to {@link #captureSession}.
     */
    private Resolved encodedSession(PilotRoute route, DesktopSession s) {
        try {
            PreviewFrame frame = s.previewFrame(Preview.MAX_EDGE, Preview.QUALITY);
            if (frame == null || frame.jpeg() == null || frame.jpeg().length == 0) return null;
            Rectangle r = frame.surface();
            if (r == null || r.isEmpty()) return null;
            return new Resolved(route, new Capture(null, r.x, r.y, r.width, r.height), frame.jpeg());
        } catch (Throwable ex) {
            return null;
        }
    }

    /**
     * Grab the nested session's <b>screen</b> — the whole {@code :N} root, tagged with
     * {@link DesktopSession#screen()}, the same space {@link PilotInputService} drives. The screen rather than the
     * attached window, because a launcher chain has no window for seconds at a time. A compositing backend
     * leaves the root black, so a blank screen falls back to the attached window.
     */
    private Capture captureSession(DesktopSession s) {
        try {
            BufferedImage img = s.captureScreen(); // the :N root — no :0 focus, non-intrusive
            Rectangle screen = s.screen();
            if (isBlank(img)) img = null;
            if (img != null && screen != null && !screen.isEmpty()) {
                return new Capture(img, screen.x, screen.y, screen.width, screen.height);
            }
            GenericWindow win = s.attached();
            if (win == null) return null;
            BufferedImage windowImg = s.capture();
            if (isBlank(windowImg)) return null;
            Rectangle b = win.getRect();
            return new Capture(windowImg, b.x, b.y, b.width, b.height);
        } catch (Throwable ex) {
            return null;
        }
    }

    /**
     * Whether a frame counts as "nothing was captured" — {@link Preview#isBlank}, which the agent applies to
     * the same frames on its side of the pipe, so the two session paths cannot disagree about an empty root.
     */
    static boolean isBlank(BufferedImage img) {
        return Preview.isBlank(img);
    }

    /**
     * Grab the emulator's screen over ADB, tagged with its <em>own framebuffer</em> rect — origin {@code (0,0)},
     * because an emulator's pixels are not on any host screen. That rect is what {@link PilotInputService}
     * clamps a gesture to and what {@code input tap} expects.
     */
    private Capture captureEmulator(EmulatorSurface surface) {
        try {
            BufferedImage img = surface.grab();
            return img == null ? null : new Capture(img, 0, 0, img.getWidth(), img.getHeight());
        } catch (Throwable ex) {
            return null;
        }
    }

    /**
     * A window on the real {@code :0} — <b>unless it is the live session's own container</b>.
     *
     * <p>gamescope renames its output window after the app it hosts, so with a game running on {@code :N} the
     * window a view names on {@code :0} can be gamescope itself. Streaming it looks right and is a trap: the frame
     * is tagged with a host rect, so Interact escalates the {@code :0} controller to real device input and fires
     * clicks <em>into the container</em>. The session route already wins over this one ({@link PilotRoutes});
     * refusing the window here makes the bad path unreachable rather than merely outranked.
     */
    private Capture captureWindowTarget(GenericWindow win) {
        if (win == null) return null;
        try {
            long host = safeHostWindow();
            if (host != 0 && WindowIds.of(win) == host) return null;
            BufferedImage img = NativeControllerFactory.get().captureWindow(win); // no focus — non-intrusive
            if (img == null) return null;
            Rectangle b = win.getRect();
            return new Capture(img, b.x, b.y, b.width, b.height, win);
        } catch (Throwable ex) {
            return null;
        }
    }

    /**
     * One {@link Robot} per thread, built once: constructing one goes through AWT's permission checks and
     * allocates a peer. Per thread because {@code Robot} is not documented thread-safe, and null when this JVM
     * has no display, which reads as "no frame this tick" like every other failure here.
     */
    private static final ThreadLocal<Robot> ROBOTS = ThreadLocal.withInitial(() -> {
        try {
            return new Robot();
        } catch (Throwable ex) {
            return null;
        }
    });

    private Capture captureBounds(Rectangle b) {
        if (b == null) return null;
        try {
            if (!ScreenCapture.robotReadsScreen()) {
                // Wayland: the Robot's grab asks the screen-cast portal each time; the screenshot program does not.
                // A region past the grab's edge is clamped, so only a whole one counts as the frame.
                BufferedImage img = ScreenCapture.captureRegion(b);
                return img == null || img.getWidth() != b.width || img.getHeight() != b.height ? null
                        : new Capture(img, b.x, b.y, b.width, b.height);
            }
            Robot robot = ROBOTS.get();
            if (robot == null) return null;
            BufferedImage img = RobotCapture.capture(robot, b);
            return img == null ? null : new Capture(img, b.x, b.y, b.width, b.height);
        } catch (Throwable ex) {
            return null;
        }
    }

    /** {@code 0} — "no session, so no window to refuse" — is the safe answer to every failure here. */
    private long safeHostWindow() {
        try {
            return sessionHostWindow.getAsLong();
        } catch (Exception e) {
            return 0;
        }
    }

    private GenericWindow safeWindow(String id) {
        try {
            return windowOfId.apply(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** A view names a window by its native handle's string. */
    private static GenericWindow openWindow(String id) {
        for (GenericWindow window : NativeControllerFactory.get().getAllWindows()) {
            if (id.equals(String.valueOf(window.getNativeHandle()))) return window;
        }
        return null;
    }

    /**
     * The screen rects, in the device pixels the input path taps in ({@link ScreenCapture}). They are asked of
     * AWT, which throws {@link java.awt.HeadlessException} where there is no display — so each answers
     * {@code null} instead, which {@link #captureBounds} reads as "no frame this tick".
     */
    private static Rectangle screenBounds(int index) {
        try {
            if (index >= 0 && index < ScreenCapture.screens().size()) {
                return ScreenCapture.monitorBounds(index);
            }
        } catch (Throwable ignored) {
            // fall through to the primary, which reports its own failure the same way
        }
        return primaryBounds();
    }

    private static Rectangle primaryBounds() {
        try {
            GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
            int primary = List.of(env.getScreenDevices()).indexOf(env.getDefaultScreenDevice());
            // -1 when the screens changed between the two reads: no frame this tick, not the whole desktop.
            return primary < 0 ? null : ScreenCapture.monitorBounds(primary);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Rectangle virtualBounds() {
        try {
            Rectangle bounds = ScreenCapture.getVirtualScreenBounds();
            return bounds.isEmpty() ? new Rectangle(0, 0, 1920, 1080) : bounds;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
