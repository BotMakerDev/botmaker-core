// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.plugin.api.DeclaredPlugin;
import com.botmaker.plugin.api.RunViewHolder;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.run.RunView;
import com.botmaker.plugin.api.run.ViewTarget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunViewsTest {

    /** A plugin that shows a window, or says the window is closed, and draws every frame as its text. */
    public static final class Shows implements RunViewHolder {
        static volatile boolean open = true;

        @Override
        public RunView view() {
            return new RunView() {
                @Override
                public Optional<ViewTarget> target() {
                    return open ? Optional.of(new ViewTarget.Window("7", "Game")) : Optional.empty();
                }

                @Override
                public Optional<String> reason() {
                    return open ? Optional.empty() : Optional.of("The game window is closed.");
                }

                @Override
                public Optional<String> overlay(byte[] frame) {
                    return Optional.of(new String(frame));
                }
            };
        }
    }

    public static final class Plugin extends DeclaredPlugin {
        public Plugin() {
            super(StudioPlugin.declare().views(Shows.class));
        }
    }

    private static RunViews views() {
        return new RunViews(() -> List.of(new Plugin()), null, () -> { });
    }

    @Test
    void noRunShowsNothingAndSaysNothing() {
        RunViews views = views();

        assertTrue(views.shown().target().isEmpty());
        assertTrue(views.shown().reason().isEmpty());
        assertTrue(views.overlay("x".getBytes()).isEmpty());
    }

    @Test
    void aRunShowsWhatItsPluginNamesAndDrawsItsFrames() {
        Shows.open = true;
        RunViews views = views();
        views.started();

        assertEquals(Optional.of(new ViewTarget.Window("7", "Game")), views.shown().target());
        assertEquals(Optional.of("hello"), views.overlay("hello".getBytes()));

        views.ended();
        assertTrue(views.shown().target().isEmpty());
    }

    @Test
    void aClosedWindowGivesItsReasonAndNoTarget() {
        Shows.open = false;
        RunViews views = views();
        views.started();

        assertTrue(views.shown().target().isEmpty());
        assertEquals(Optional.of("The game window is closed."), views.shown().reason());
        Shows.open = true;
    }

    @Test
    void aSecondStartWhileTheRunGoesBuildsNoMoreViews() {
        RunViews views = views();
        views.started();
        views.started();

        assertEquals(1, views.views().size());
    }
}
