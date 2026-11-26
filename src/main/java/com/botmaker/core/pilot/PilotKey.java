// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.shared.platform.Os;

import java.util.Locale;
import java.util.Optional;

/**
 * A key the Remote Pilot's phone presses, named on the wire by its constant ({@code ENTER}, {@code PAGE_UP}).
 *
 * <p>Each carries the X11 keysym a display takes (macOS too), the Windows virtual-key code, and the Android
 * {@code KEYCODE_*} an emulator takes ({@code input keyevent}). Android codes are given only for the phone's
 * special-key row plus the letters and digits; any other answers {@code -1} on an emulator and the phone is told
 * the screen does not take it, rather than a guess pressing some other key.
 */
enum PilotKey {
    A(0x61, 0x41, 29), B(0x62, 0x42, 30), C(0x63, 0x43, 31), D(0x64, 0x44, 32), E(0x65, 0x45, 33),
    F(0x66, 0x46, 34), G(0x67, 0x47, 35), H(0x68, 0x48, 36), I(0x69, 0x49, 37), J(0x6A, 0x4A, 38),
    K(0x6B, 0x4B, 39), L(0x6C, 0x4C, 40), M(0x6D, 0x4D, 41), N(0x6E, 0x4E, 42), O(0x6F, 0x4F, 43),
    P(0x70, 0x50, 44), Q(0x71, 0x51, 45), R(0x72, 0x52, 46), S(0x73, 0x53, 47), T(0x74, 0x54, 48),
    U(0x75, 0x55, 49), V(0x76, 0x56, 50), W(0x77, 0x57, 51), X(0x78, 0x58, 52), Y(0x79, 0x59, 53),
    Z(0x7A, 0x5A, 54),

    NUM0(0x30, 0x30, 7), NUM1(0x31, 0x31, 8), NUM2(0x32, 0x32, 9), NUM3(0x33, 0x33, 10), NUM4(0x34, 0x34, 11),
    NUM5(0x35, 0x35, 12), NUM6(0x36, 0x36, 13), NUM7(0x37, 0x37, 14), NUM8(0x38, 0x38, 15), NUM9(0x39, 0x39, 16),

    F1(0xFFBE, 0x70), F2(0xFFBF, 0x71), F3(0xFFC0, 0x72), F4(0xFFC1, 0x73),
    F5(0xFFC2, 0x74), F6(0xFFC3, 0x75), F7(0xFFC4, 0x76), F8(0xFFC5, 0x77),
    F9(0xFFC6, 0x78), F10(0xFFC7, 0x79), F11(0xFFC8, 0x7A), F12(0xFFC9, 0x7B),

    ENTER(0xFF0D, 0x0D, 66), ESCAPE(0xFF1B, 0x1B, 111), SPACE(0x20, 0x20, 62), TAB(0xFF09, 0x09, 61),
    // KEYCODE_DEL is Android's backspace; 112 is KEYCODE_FORWARD_DEL.
    BACKSPACE(0xFF08, 0x08, 67), DELETE(0xFFFF, 0x2E, 112),

    SHIFT(0xFFE1, 0x10), CTRL(0xFFE3, 0x11), ALT(0xFFE9, 0x12), META(0xFFEB, 0x5B),

    LEFT(0xFF51, 0x25, 21), UP(0xFF52, 0x26, 19), RIGHT(0xFF53, 0x27, 22), DOWN(0xFF54, 0x28, 20),

    BACKQUOTE(0x60, 0xC0), MINUS(0x2D, 0xBD), EQUALS(0x3D, 0xBB), LEFT_BRACKET(0x5B, 0xDB),
    RIGHT_BRACKET(0x5D, 0xDD), BACKSLASH(0x5C, 0xDC), SEMICOLON(0x3B, 0xBA), QUOTE(0x27, 0xDE),
    COMMA(0x2C, 0xBC), PERIOD(0x2E, 0xBE), SLASH(0x2F, 0xBF),

    // KEYCODE_MOVE_HOME and KEYCODE_MOVE_END.
    HOME(0xFF50, 0x24, 122), END(0xFF57, 0x23, 123), PAGE_UP(0xFF55, 0x21, 92), PAGE_DOWN(0xFF56, 0x22, 93),
    INSERT(0xFF63, 0x2D), CAPS_LOCK(0xFFE5, 0x14), NUM_LOCK(0xFF7F, 0x90),

    NUMPAD_0(0xFFB0, 0x60), NUMPAD_1(0xFFB1, 0x61), NUMPAD_2(0xFFB2, 0x62), NUMPAD_3(0xFFB3, 0x63),
    NUMPAD_4(0xFFB4, 0x64), NUMPAD_5(0xFFB5, 0x65), NUMPAD_6(0xFFB6, 0x66), NUMPAD_7(0xFFB7, 0x67),
    NUMPAD_8(0xFFB8, 0x68), NUMPAD_9(0xFFB9, 0x69),
    NUMPAD_ADD(0xFFAB, 0x6B), NUMPAD_SUBTRACT(0xFFAD, 0x6D), NUMPAD_MULTIPLY(0xFFAA, 0x6A),
    NUMPAD_DIVIDE(0xFFAF, 0x6F), NUMPAD_DECIMAL(0xFFAE, 0x6E),
    /** Windows has no virtual key of its own for the numpad's Enter, so there it is {@link #ENTER}. */
    NUMPAD_ENTER(0xFF8D, 0x0D, 66);

    private final int linuxKeySym;
    private final int windowsVk;
    private final int androidCode;

    PilotKey(int linuxKeySym, int windowsVk) {
        this(linuxKeySym, windowsVk, -1);
    }

    PilotKey(int linuxKeySym, int windowsVk, int androidCode) {
        this.linuxKeySym = linuxKeySym;
        this.windowsVk = windowsVk;
        this.androidCode = androidCode;
    }

    /** The key the wire names, in either case; empty for a name this host does not know. */
    static Optional<PilotKey> fromWire(String name) {
        if (name == null) return Optional.empty();
        try {
            return Optional.of(valueOf(name.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    /** The key code to feed the native controller on this OS. */
    int nativeCode() {
        return Os.current() == Os.WINDOWS ? windowsVk : linuxKeySym;
    }

    /** The Android key code, or {@code -1} when the emulator route does not press this key. */
    int androidCode() {
        return androidCode;
    }
}
