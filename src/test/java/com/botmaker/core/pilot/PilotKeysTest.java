// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.session.Capability;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeControllerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pilot keyboard: a key has no coordinates, so where it may land is decided by the route — anything on
 * the bot's own screen, and on the host {@code :0} only while the focused window is the streamed frame.
 */
class PilotKeysTest {

    private static final PilotInputService.Bounds FRAME = new PilotInputService.Bounds(100, 50, 800, 600);
    private static final PilotInputService.Kind KEY = PilotInputService.Kind.KEY;
    private static final PilotInputService.Kind TEXT = PilotInputService.Kind.TEXT;

    @AfterEach
    void resetFactory() {
        NativeControllerFactory.setForTesting(null);
    }

    private static PilotRoute session(PilotFakes.RecordingController nc) {
        return new PilotRoute.Session(
                new PilotFakes.FakeSession(nc, null, null, EnumSet.of(Capability.BACKGROUND_CLICK)));
    }

    private static PilotFakes.RecordingController host(Rectangle focused) {
        PilotFakes.RecordingController nc = new PilotFakes.RecordingController();
        nc.foreground = focused == null ? null : new GenericWindow(1L, "focused", focused);
        NativeControllerFactory.setForTesting(nc);
        return nc;
    }

    @Test
    void aKeyOnASessionIsPressedAndReleasedWithoutAFocusCheck() {
        PilotFakes.RecordingController nc = new PilotFakes.RecordingController();
        assertEquals(PilotInputService.Typed.SENT,
                new PilotInputService().type(session(nc), KEY, "enter", "", null, FRAME));
        int code = PilotKey.ENTER.nativeCode();
        assertEquals(List.of("keyDown " + code, "keyUp " + code), nc.calls);
    }

    @Test
    void onTheHostAKeyGoesOnlyToTheStreamedWindow() {
        PilotFakes.RecordingController game = host(new Rectangle(100, 50, 800, 600));
        assertEquals(PilotInputService.Typed.SENT,
                new PilotInputService().type(PilotRoute.DESKTOP, TEXT, "", "gg", null, FRAME));
        assertEquals(List.of("type gg"), game.calls);

        PilotFakes.RecordingController terminal = host(new Rectangle(0, 0, 1920, 1080));
        assertEquals(PilotInputService.Typed.NOT_FOCUSED,
                new PilotInputService().type(PilotRoute.DESKTOP, KEY, "ENTER", "", null, FRAME));
        assertTrue(terminal.calls.isEmpty(), "nothing is typed into a window the phone was not shown");

        PilotFakes.RecordingController nothing = host(null);
        assertEquals(PilotInputService.Typed.NOT_FOCUSED,
                new PilotInputService().type(PilotRoute.DESKTOP, KEY, "ENTER", "", null, FRAME));
        assertTrue(nothing.calls.isEmpty());
    }

    /** Two maximised programs share one rect, so a streamed window is told apart by which window it is. */
    @Test
    void anotherProgramOverTheStreamedWindowTakesNoKey() {
        PilotFakes.RecordingController nc = host(new Rectangle(100, 50, 800, 600));
        GenericWindow game = new GenericWindow(2L, "game", new Rectangle(100, 50, 800, 600));

        assertEquals(PilotInputService.Typed.NOT_FOCUSED,
                new PilotInputService().type(PilotRoute.DESKTOP, TEXT, "", "gg", game, FRAME));
        assertTrue(nc.calls.isEmpty(), nc.calls.toString());

        nc.foreground = game;
        assertEquals(PilotInputService.Typed.SENT,
                new PilotInputService().type(PilotRoute.DESKTOP, TEXT, "", "gg", game, FRAME));
    }

    @Test
    void theFocusMatchAllowsAFewPixelsOfDecorationAndNoMore() {
        assertTrue(PilotInputService.focusedIsFrame(new Rectangle(100, 34, 800, 616), FRAME));
        assertFalse(PilotInputService.focusedIsFrame(new Rectangle(100, 50, 700, 600), FRAME));
        assertFalse(PilotInputService.focusedIsFrame(new Rectangle(0, 0, 0, 0), FRAME));
    }

    @Test
    void anEmulatorGetsAndroidKeyCodesAndText() {
        PilotFakes.RecordingSurface surface = new PilotFakes.RecordingSurface(null);
        PilotRoute route = new PilotRoute.Emulator(surface);
        PilotInputService input = new PilotInputService();

        assertEquals(PilotInputService.Typed.SENT, input.type(route, KEY, "BACKSPACE", "", null, FRAME));
        assertEquals(PilotInputService.Typed.SENT, input.type(route, TEXT, "", "hi there", null, FRAME));
        assertEquals(PilotInputService.Typed.UNSUPPORTED, input.type(route, KEY, "F5", "", null, FRAME));
        assertEquals(List.of("key 67", "text hi there"), surface.calls);
    }

    @Test
    void anUnknownKeyIsRefusedAndTextIsCleanedAndCapped() {
        PilotFakes.RecordingController nc = new PilotFakes.RecordingController();
        PilotInputService input = new PilotInputService();
        assertEquals(PilotInputService.Typed.UNKNOWN_KEY, input.type(session(nc), KEY, "rm -rf", "", null, FRAME));
        assertEquals(PilotInputService.Typed.SENT, input.type(session(nc), TEXT, "", "a\nb\u0007c", null, FRAME));
        assertEquals(List.of("type abc"), nc.calls);
        assertEquals(PilotInputService.MAX_TEXT, PilotInputService.printable("x".repeat(1000)).length());
    }

    @Test
    void aKeyIsNeverAPointerGesture() {
        PilotFakes.RecordingController nc = new PilotFakes.RecordingController();
        assertFalse(new PilotInputService().apply(session(nc), KEY, 10, 10, 1, 0, FRAME));
        assertTrue(nc.calls.isEmpty());
    }

    @Test
    void theWireNamesAreEveryKindInLowerCase() {
        assertEquals(List.of("tap", "down", "move", "up", "scroll", "key", "text"),
                PilotInputService.Kind.wireNames());
    }
}
