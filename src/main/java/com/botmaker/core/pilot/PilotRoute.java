// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.plugin.api.run.ViewTarget;
import com.botmaker.session.DesktopSession;
import com.botmaker.shared.emulator.EmulatorSurface;

/**
 * <b>Which surface the pilot is streaming and touching</b> — the one question {@link TargetCapture} and
 * {@link PilotInputService} must always answer the same way, since a gesture is only meaningful in the
 * coordinate space of the frame the user actually tapped. A closed set, switched exhaustively there, so a new
 * surface has to be decided rather than defaulted.
 *
 * <p>The surfaces differ in exactly the way that matters: {@link Session} and {@link Emulator} are
 * background-safe (a bot-owned {@code :N} pointer, and ADB, which has no host cursor to move at all), while
 * {@link Screen} mirrors the user's real screen and drives their real cursor.
 */
public sealed interface PilotRoute {

    /** A window, a monitor or the whole desktop of the user's real {@code :0}, as a view named it. */
    record Screen(ViewTarget shown) implements PilotRoute {
        public Screen {
            if (shown == null || shown instanceof ViewTarget.Emulator) {
                throw new IllegalArgumentException("not a part of this screen: " + shown);
            }
        }
    }

    /** Stream and drive a bot-owned nested {@code :N} display. Wins over everything when one is live. */
    record Session(DesktopSession session) implements PilotRoute {}

    /** Stream and drive an Android emulator over ADB — off the user's desktop entirely. */
    record Emulator(EmulatorSurface surface) implements PilotRoute {}

    /**
     * The surface to show is not there. Nothing is streamed and nothing is driven, and {@code reason} is the
     * sentence the phone is told: the user's own screen never stands in for it.
     */
    record Missing(String reason) implements PilotRoute {}

    /** The route before a frame was pushed, which input refuses until one is. */
    PilotRoute DESKTOP = new Screen(ViewTarget.desktop());

    /**
     * Whether a button held on this route can be released on {@code other}. Every {@link Screen} is the same
     * real display, so a window whose title changes does not end a drag.
     */
    default boolean sameSurface(PilotRoute other) {
        return this instanceof Screen && other instanceof Screen || equals(other);
    }
}
