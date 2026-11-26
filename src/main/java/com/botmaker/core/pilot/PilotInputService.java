// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.session.Capability;
import com.botmaker.session.DesktopSession;
import com.botmaker.session.PointerPolicy;
import com.botmaker.session.remote.WindowIds;
import com.botmaker.shared.emulator.EmulatorSurface;

import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Replays the pilot's manual "Interact" gestures onto whichever surface the bot is acting on.
 *
 * <p>The pilot streams that surface; with Interact armed, a tap/drag/scroll on the stream arrives here in the
 * coordinates of the frame the user touched, and is replayed on the {@link PilotRoute} that produced it — the
 * same plumbing a running bot's {@code Mouse} calls use, so a hand gesture and a bot action reach the game by
 * exactly one path. Two of the three routes go through a {@link NativeController} (a real {@code :0} desktop
 * or a nested {@code :N} display); the third is an emulator, where there is no display and no pointer and the
 * verbs are Android's own whole-gesture ones (see {@link #applyToEmulator}).
 *
 * <p><b>Reliability beats cursor safety here.</b> The cursor-preserving Linux default ({@code XSendEvent})
 * sends synthetic events flagged {@code send_event=True}, which every Wine/Proton game — the targets Interact
 * exists for — ignores, so taps silently did nothing. Windows had the same bug in a different dialect:
 * {@code PostMessage} puts a click in the window's message queue, which a game reading raw input never looks
 * at. The controller is therefore asked once, lazily, for {@link NativeController#useReliableInput()}, which
 * on both platforms switches to real device input. After escalating, {@link #supportsBackgroundInput}
 * honestly reports {@code false}, which {@code PilotServer} forwards as the state message's
 * {@code backgroundInput} flag and the pilot renders as its "moves the computer's real cursor" warning. The
 * escalation is process-wide and sticky.
 *
 * <p><b>Every display-backed gesture drives the real pointer</b>, and on the host {@code :0} each puts it back where it
 * started. A tap used to take the old {@code postLeftClickScreen} on the theory that it was the one gesture
 * with a cursor-preserving direct-to-window path — that path is exactly the one games drop, which is why taps
 * did nothing while drags (already on {@code mouseMove}/{@code mouseButton}) worked. In a session the
 * restoring warp is dropped as well ({@link PointerPolicy}): there is no user cursor to hand back.
 *
 * <p><b>Bounds are not optional.</b> Every coordinate is clamped to the rect the client was actually shown
 * (the last pushed frame's surface). A pilot session is reachable over a public tunnel URL; without the clamp
 * a client could drive the pointer anywhere on the host's desktop, including over BotMaker's own windows.
 *
 * <p><b>A held button is always released.</b> An out-of-bounds gesture used to be dropped whatever it was,
 * including the {@code UP} ending a drag — so a drag released past the edge of the streamed frame left
 * {@code BTN_LEFT} down on the virtual device. On X a held button is an implicit pointer grab on the window
 * that got the press: every later click anywhere on the host goes there, which is the reported "I can't click
 * anything until BotMaker is shut down" (shutdown destroys the uinput device, which drops the grab). A
 * mid-drag {@code MOVE}/{@code UP} is therefore <em>clamped</em> to the frame rather than dropped — the clamp
 * is what the bounds rule was always for — and {@link #releaseHeld()} lets the owner let go on any exit the
 * gesture protocol doesn't cover: a phone that vanishes mid-drag, a route change under the drag, a throw, or
 * the host closing.
 */
public final class PilotInputService implements AutoCloseable {

    /** The surface the client is currently being shown — the only region input may land in. */
    public record Bounds(int sx, int sy, int sw, int sh) {
        boolean contains(int x, int y) {
            return x >= sx && x < sx + sw && y >= sy && y < sy + sh;
        }

        /** The nearest point inside — the last row/column is {@code s+len-1}, not {@code s+len}. */
        int clampX(int x) {
            return Math.max(sx, Math.min(x, sx + sw - 1));
        }

        int clampY(int y) {
            return Math.max(sy, Math.min(y, sy + sh - 1));
        }
    }

    /**
     * One step from the client. {@code amount} is only read for {@link Kind#SCROLL}; {@link Kind#KEY} and
     * {@link Kind#TEXT} carry no coordinates and go through {@link #type} rather than {@link #apply}.
     */
    public enum Kind {
        TAP, DOWN, MOVE, UP, SCROLL, KEY, TEXT;

        /** The wire names of every kind, lower case — what the server announces a phone may send. */
        public static java.util.List<String> wireNames() {
            return java.util.Arrays.stream(values()).map(k -> k.name().toLowerCase(java.util.Locale.ROOT)).toList();
        }
    }

    /** What became of a {@link Kind#KEY} or {@link Kind#TEXT} step. Every outcome but {@link #SENT} is told to the phone. */
    public enum Typed {
        SENT(""),
        UNKNOWN_KEY("Pilot does not know that key."),
        NOT_FOCUSED("Keys go only to the bot's window. Tap the window first so it has focus."),
        UNSUPPORTED("This screen does not take that key."),
        FAILED("The key could not be sent.");

        private final String notice;

        Typed(String notice) {
            this.notice = notice;
        }

        /** The sentence the phone shows, empty for {@link #SENT}. */
        public String notice() {
            return notice;
        }
    }

    /** The longest text one {@link Kind#TEXT} step types; a paste longer than a chat line is not a keystroke. */
    static final int MAX_TEXT = 256;

    /** How far (px) each edge of the focused window may sit from the streamed frame's and still count as it. */
    static final int FOCUS_TOLERANCE = 16;

    /**
     * The host {@code :0} controller, resolved lazily (constructing it probes X11/Win32 and must not run at
     * the host's startup) and only when the route actually is the desktop. Its escalation is sticky by design.
     */
    private NativeController host;

    /** Where the pointer was when the current drag started, restored on {@code UP}. Null when not dragging. */
    private java.awt.Point dragOrigin;

    /** The button a {@code DOWN} pressed and no {@code UP} has released yet, or {@code 0} when none is held. */
    private int heldButton;

    /** Which client holds it: only that one may move or release it. */
    private Object heldBy;

    /**
     * Clients whose drag was refused or timed out: the moves and the release they still send belong to no press,
     * and must neither move the pointer nor tap.
     */
    private final Set<Object> cancelled = new HashSet<>();

    /** When the holder last sent a step, for {@link #releaseStale}. */
    private long heldSeenAt;

    /**
     * The route the held button was pressed on — the one it must be released on. A release on "the current
     * route" would leave the button down on the surface that actually has it the moment the route changes
     * mid-drag, which is the same wedge by a longer path.
     */
    private PilotRoute heldRoute;

    /** Where on the surface the current drag started — the emulator route's swipe needs both ends at once. */
    private java.awt.Point touchOrigin;

    /** When the current drag started, so an emulator swipe lasts as long as the gesture actually did. */
    private long touchStartedAt;

    /** The most wheel steps one gesture scrolls, either way: the amount is the client's, and each step is an event. */
    static final int MAX_SCROLL = 10;

    /** The longest swipe an emulator is sent. A longer one would tie up the device for as long as the finger lay. */
    static final long MAX_SWIPE_MS = 3000;

    /** How long a button stays held without a step from its holder before it is let go. */
    static final long HOLD_LIMIT_MS = 30_000;

    /** How often, and how many times, {@link #bringForward} looks for the raised window to take focus. */
    private static final long FOCUS_POLL_MS = 20;
    private static final int FOCUS_POLLS = 10;

    private final LongSupplier clock;

    public PilotInputService() {
        this(System::currentTimeMillis);
    }

    PilotInputService(LongSupplier clock) {
        this.clock = clock;
    }

    /** {@link #apply(Object, PilotRoute, Kind, int, int, int, int, Bounds)} for a single client. */
    boolean apply(PilotRoute route, Kind kind, int x, int y, int button, int amount, Bounds bounds) {
        return apply(this, route, kind, x, y, button, amount, bounds);
    }

    /**
     * Applies one gesture at the coordinates of {@code route}'s surface, ignoring anything outside
     * {@code bounds}.
     *
     * <p>{@code client} owns the button a {@code DOWN} presses: until it comes up, every pointer gesture from
     * another client is rejected, and so is a second press or a tap from anyone.
     *
     * <p>An emulator's gesture is sent outside this object's lock: a swipe blocks for as long as it lasts, and
     * the state message and the release paths must not wait behind it.
     *
     * <p>The route is passed in rather than read from a holder because it must be the route that produced the
     * frame the user touched — the caller records it beside the bounds when it pushes a frame. Reading "the
     * current route" here instead would let a gesture aimed at an emulator land on the desktop the instant the
     * project's capture source changed under it.
     *
     * @return true if the gesture was dispatched, false if it was rejected (out of bounds, no controller)
     */
    public boolean apply(Object client, PilotRoute route, Kind kind, int x, int y, int button, int amount,
                         Bounds bounds) {
        if (bounds == null || kind == Kind.KEY || kind == Kind.TEXT) return false;
        Runnable onDevice;
        synchronized (this) {
            // A route that changed under a drag ends that drag on the surface that owns the button, now, before
            // anything is dispatched to the new one.
            if (heldButton != 0 && !routeOrDesktop(route).sameSurface(heldRoute)) releaseHeld();
            if (route instanceof PilotRoute.Missing) return false;
            if (kind == Kind.DOWN) cancelled.remove(client);
            if ((kind == Kind.MOVE || kind == Kind.UP) && cancelled.contains(client)) {
                if (kind == Kind.UP) cancelled.remove(client);
                return false;
            }
            if (heldButton != 0 && (!client.equals(heldBy) || kind == Kind.DOWN || kind == Kind.TAP)) {
                if (kind == Kind.DOWN && !client.equals(heldBy)) cancelled.add(client);
                return false;
            }
            if (!bounds.contains(x, y)) {
                // Dropping a mid-drag MOVE/UP is what strands the button; the frame edge is where it belongs.
                if (heldButton == 0 || (kind != Kind.MOVE && kind != Kind.UP)) return false;
                x = bounds.clampX(x);
                y = bounds.clampY(y);
            }
            int btn = button < 1 || button > 3 ? 1 : button;
            if (kind == Kind.UP && heldButton != 0) btn = heldButton; // release what was pressed
            int steps = Math.max(-MAX_SCROLL, Math.min(amount, MAX_SCROLL));
            if (heldButton != 0) heldSeenAt = clock.getAsLong();
            if (!(route instanceof PilotRoute.Emulator(EmulatorSurface surface))) {
                return applyToDisplay(client, route, kind, x, y, btn, steps);
            }
            // No pointer to strand here, but the same clamp has to apply or the swipe's end is thrown away —
            // so the emulator route takes part in the held-gesture bookkeeping too.
            if (kind == Kind.DOWN) hold(client, route, btn);
            onDevice = emulatorStep(surface, kind, x, y, steps);
            if (kind == Kind.UP) forgetHeld();
        }
        try {
            onDevice.run();
            return true;
        } catch (Exception e) {
            Diag.warn("Pilot", "Interact " + kind + " on emulator failed: " + e.getMessage());
            return false;
        }
    }

    private void hold(Object client, PilotRoute route, int button) {
        heldButton = button;
        heldRoute = route;
        heldBy = client;
        heldSeenAt = clock.getAsLong();
    }

    private void forgetHeld() {
        heldButton = 0;
        heldRoute = null;
        heldBy = null;
    }

    private boolean applyToDisplay(Object client, PilotRoute route, Kind kind, int x, int y, int btn, int amount) {
        NativeController nc = controller(route);
        if (nc == null) return false;

        try {
            switch (kind) {
                // Whether a gesture hands the cursor back is PointerPolicy's call, not this switch's. The drag
                // gestures can't restore mid-gesture in any case: the cursor has to stay with the gesture until the
                // button is released, so UP is where the restore belongs (see dragOrigin).
                case TAP -> PointerPolicy.click(nc, sessionOf(route), x, y, btn);
                case DOWN -> {
                    dragOrigin = nc.cursorPosition();
                    nc.mouseMove(x, y);
                    nc.mouseButton(btn, true);
                    hold(client, route, btn);   // recorded only once the press actually went out
                }
                case MOVE -> nc.mouseMove(x, y);
                case UP -> {
                    nc.mouseMove(x, y);
                    nc.mouseButton(btn, false);
                    PointerPolicy.restoreTo(nc, sessionOf(route), dragOrigin);
                    dragOrigin = null;
                    forgetHeld();
                }
                case SCROLL -> { nc.mouseMove(x, y); nc.scroll(amount); }
            }
            return true;
        } catch (Exception e) {
            Diag.warn("Pilot", "Interact " + kind + " failed: " + e.getMessage());
            // A throw part-way through a drag is exactly the case that leaves a button down.
            releaseHeld();
            return false;
        }
    }

    /**
     * Presses one key ({@link Kind#KEY}, a {@link PilotKey} constant's name such as {@code ENTER}) or types text
     * ({@link Kind#TEXT}) on {@code route}.
     *
     * <p><b>A key has no coordinates, so it cannot be clamped to the frame the way a tap is</b>: it goes to
     * whatever has focus. On a nested session or an emulator that is the bot's own screen, so anything goes.
     * On the host {@code :0} it could be a terminal or BotMaker itself, and the pilot may be reachable over a
     * public tunnel URL, so a key is sent there only while the focused window <em>is</em> the streamed frame
     * ({@link #focusedIsFrame}); otherwise it is refused and the phone is told to tap the window first. (The
     * maintainer's call.)
     */
    public Typed type(PilotRoute route, Kind kind, String keyName, String text, GenericWindow window, Bounds bounds) {
        if (bounds == null || route instanceof PilotRoute.Missing) return Typed.FAILED;
        String typed = kind == Kind.TEXT ? printable(text) : "";
        if (kind == Kind.TEXT && typed.isEmpty()) return Typed.SENT;
        PilotKey key = null;
        if (kind == Kind.KEY) {
            key = PilotKey.fromWire(keyName).orElse(null);
            if (key == null) return Typed.UNKNOWN_KEY;
        } else if (kind != Kind.TEXT) {
            return Typed.UNKNOWN_KEY;
        }
        try {
            if (route instanceof PilotRoute.Emulator(EmulatorSurface surface)) {
                if (key == null) {
                    surface.text(typed);
                    return Typed.SENT;
                }
                int code = key.androidCode();
                if (code < 0) return Typed.UNSUPPORTED;
                surface.key(code);
                return Typed.SENT;
            }
            // Locked here only: an emulator's key blocks on ADB, and the frame loop must not wait behind it.
            return typeOnDisplay(route, key, typed, window, bounds);
        } catch (Exception e) {
            Diag.warn("Pilot", "Interact " + kind + " failed: " + e.getMessage());
            return Typed.FAILED;
        }
    }

    private synchronized Typed typeOnDisplay(PilotRoute route, PilotKey key, String typed, GenericWindow window,
                                             Bounds bounds) {
        NativeController nc = controller(route);
        if (nc == null) return Typed.FAILED;
        if (sessionOf(route) == null) {
            GenericWindow focused = nc.getForegroundWindow();
            boolean streamed = window != null ? inFront(nc, window, bounds)
                    : focusedIsFrame(focused == null ? null : focused.getRect(), bounds);
            if (!streamed) return Typed.NOT_FOCUSED;
        }
        if (key == null) {
            nc.typeText(typed);
        } else {
            nc.keyDown(key.nativeCode());
            nc.keyUp(key.nativeCode());
        }
        return Typed.SENT;
    }

    /**
     * Whether the focused window is the frame the phone is shown: every edge within {@link #FOCUS_TOLERANCE}.
     * A window target's frame is that window's own rect, so the two match exactly when the game has focus; a
     * whole-screen frame matches only a fullscreen window.
     */
    static boolean focusedIsFrame(java.awt.Rectangle focused, Bounds frame) {
        if (focused == null || focused.width <= 0 || focused.height <= 0) return false;
        return Math.abs(focused.x - frame.sx()) <= FOCUS_TOLERANCE
                && Math.abs(focused.y - frame.sy()) <= FOCUS_TOLERANCE
                && Math.abs(focused.x + focused.width - (frame.sx() + frame.sw())) <= FOCUS_TOLERANCE
                && Math.abs(focused.y + focused.height - (frame.sy() + frame.sh())) <= FOCUS_TOLERANCE;
    }

    /** {@code text} without control characters, at most {@link #MAX_TEXT} characters. */
    static String printable(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder();
        text.codePoints().filter(c -> !Character.isISOControl(c))
                .limit(MAX_TEXT).forEach(out::appendCodePoint);
        return out.toString();
    }

    /** {@code route}, never null — {@link PilotRoute#DESKTOP} is what a missing route means everywhere here. */
    private static PilotRoute routeOrDesktop(PilotRoute route) {
        return route == null ? PilotRoute.DESKTOP : route;
    }

    /**
     * Let go of whatever button is held, on the route it was pressed on, and hand the cursor back. Idempotent
     * and safe to call when nothing is held — the owner calls it on every exit a drag can take that isn't an
     * {@code UP}: the client disconnecting, the server closing, a route change, a failed dispatch.
     */
    public synchronized void releaseHeld() {
        PilotRoute route = heldRoute;
        int btn = heldButton;
        java.awt.Point origin = dragOrigin;
        forgetHeld();
        dragOrigin = null;
        touchOrigin = null;
        if (btn == 0 || route == null || route instanceof PilotRoute.Emulator) return;
        try {
            NativeController nc = controller(route);
            if (nc == null) return;
            nc.mouseButton(btn, false);
            PointerPolicy.restoreTo(nc, sessionOf(route), origin);
        } catch (Exception e) {
            Diag.warn("Pilot", "Interact could not release button " + btn + ": " + e.getMessage());
        }
    }

    /** {@link #releaseHeld()} only when {@code client} is the holder: one phone leaving must not end another's drag. */
    public synchronized void releaseHeldBy(Object client) {
        cancelled.remove(client);
        if (client.equals(heldBy)) releaseHeld();
    }

    /** The press of {@code client}'s drag was not sent: ignore the rest of that drag. */
    public synchronized void cancelGesture(Object client) {
        cancelled.add(client);
    }

    /** Lets go of a button its holder has sent nothing about for {@link #HOLD_LIMIT_MS}: its release was lost. */
    public synchronized void releaseStale() {
        if (heldButton == 0 || clock.getAsLong() - heldSeenAt <= HOLD_LIMIT_MS) return;
        cancelled.add(heldBy);
        releaseHeld();
    }

    /**
     * Makes a gesture on the host {@code :0} land on the window the phone is shown rather than on whatever
     * covers it: {@code window} is raised and focused unless it already has focus.
     *
     * @return false when it could not be brought forward, and the gesture must then not be sent. True on any
     *         other route, and for a frame that is not one window ({@code window} is null)
     */
    public boolean bringForward(PilotRoute route, GenericWindow window, Bounds bounds) {
        if (window == null || bounds == null || !(routeOrDesktop(route) instanceof PilotRoute.Screen)) return true;
        NativeController nc;
        synchronized (this) {
            nc = controller(route);
        }
        if (nc == null) return false;
        try {
            if (inFront(nc, window, bounds)) return true;
            nc.focusWindow(window);
            for (int i = 0; i < FOCUS_POLLS; i++) {
                if (inFront(nc, window, bounds)) return true;
                Thread.sleep(FOCUS_POLL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Diag.warn("Pilot", "Interact could not bring the window forward: " + e.getMessage());
        }
        return false;
    }

    private static boolean inFront(NativeController nc, GenericWindow window, Bounds bounds) {
        GenericWindow focused = nc.getForegroundWindow();
        if (focused == null) return false;
        long wanted = WindowIds.of(window);
        long had = WindowIds.of(focused);
        // By id where both have one: two maximised windows share a rect.
        return wanted != 0 && had != 0 ? wanted == had : focusedIsFrame(focused.getRect(), bounds);
    }

    /** Releases any held button. The controller itself is process-wide and sticky, so it is not torn down. */
    @Override
    public void close() {
        releaseHeld();
    }

    /**
     * One gesture against an emulator, which has <b>no pointer to move</b> — the reason this can't be a
     * translation of the desktop path call for call. Android's input verbs are whole gestures:
     *
     * <ul>
     *   <li>{@code TAP} is one {@code input tap};</li>
     *   <li>a drag is one {@code input swipe} carrying <em>both</em> ends, so {@code DOWN} only remembers where
     *       it started, {@code MOVE} dispatches nothing at all (a swipe per move would be a stutter of
     *       unrelated flicks, not a drag), and {@code UP} sends the whole thing — over the time the user
     *       actually took, which is what separates a drag from a fling;</li>
     *   <li>{@code SCROLL} is a swipe centred on the pointer.</li>
     * </ul>
     *
     * <p>An {@code UP} with no remembered {@code DOWN} (a reconnect mid-gesture) degrades to a tap rather than
     * to a swipe from nowhere.
     */
    private Runnable emulatorStep(EmulatorSurface surface, Kind kind, int x, int y, int amount) {
        switch (kind) {
            case TAP -> { return () -> surface.tap(x, y); }
            case DOWN -> {
                touchOrigin = new java.awt.Point(x, y);
                touchStartedAt = clock.getAsLong();
            }
            case UP -> {
                java.awt.Point from = touchOrigin;
                touchOrigin = null;
                if (from == null) return () -> surface.tap(x, y);
                long lasted = Math.max(1, Math.min(clock.getAsLong() - touchStartedAt, MAX_SWIPE_MS));
                return () -> surface.drag(from.x, from.y, x, y, lasted);
            }
            case SCROLL -> { return () -> surface.scroll(x, y, amount); }
            default -> { /* MOVE: a touch screen has no hover; KEY and TEXT never get here */ }
        }
        return () -> { };
    }

    /**
     * The session a gesture drives, or {@code null} for the host {@code :0} — the input to
     * {@link PointerPolicy}, which decides whether a gesture hands the cursor back. That decision used to live
     * here as a private {@code sessionOwnsPointer()}; it moved to shared once it turned out the SDK's own click
     * path had never implemented it and was throwing every in-session click away.
     */
    private static DesktopSession sessionOf(PilotRoute route) {
        return route instanceof PilotRoute.Session(DesktopSession s) ? s : null;
    }

    /**
     * True when synthesized input on {@code route} leaves the user's real cursor alone. Both non-desktop routes
     * are background-safe by construction — a nested session's {@code :N} pointer is the bot's alone
     * ({@link Capability#BACKGROUND_CLICK}), and an emulator is driven by ADB, which has no host cursor to
     * touch in the first place. Only the {@code :0} path can fail this, once it has escalated to device input
     * (see {@link NativeController}).
     */
    public synchronized boolean supportsBackgroundInput(PilotRoute route) {
        // Nothing is driven on a missing surface, so the user's cursor is not at stake.
        if (route instanceof PilotRoute.Emulator || route instanceof PilotRoute.Missing) return true;
        DesktopSession s = sessionOf(route);
        if (s != null) return s.has(Capability.BACKGROUND_CLICK);
        // Asking must not resolve the controller: that switches :0 to real device input, process-wide, for a
        // phone that may only be watching. Before any gesture the answer is what the first one will make true.
        return host != null && host.supportsBackgroundInput();
    }

    /**
     * The controller a gesture drives on a display-backed route. A nested session's {@code :N} controller is
     * already device-level and background-safe, so it needs no {@code useReliableInput()} escalation — and
     * routing through it keeps Interact in the same coordinate space as the streamed {@code :N} frame. On the
     * desktop route the host {@code :0} controller is resolved (and escalated to a reliable input path) exactly
     * once, lazily, so the core's startup, emulator routes and bot-only sessions never pay for it — and never lose
     * the cursor-safe default unless Interact was actually used on {@code :0}.
     */
    private NativeController controller(PilotRoute route) {
        DesktopSession s = sessionOf(route);
        if (s != null) return s.controller();
        if (host == null) {
            try {
                NativeController nc = NativeControllerFactory.get();
                if (!nc.useReliableInput()) {
                    Diag.warn("Pilot", "No reliable input backend available — taps may not reach the game "
                            + "(see LinuxController.useReliableInput).");
                }
                host = nc;
            } catch (Exception e) {
                Diag.warn("Pilot", "Interact unavailable: " + e.getMessage());
            }
        }
        return host;
    }
}
