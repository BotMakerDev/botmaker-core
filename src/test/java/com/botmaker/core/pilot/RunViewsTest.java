// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.plugin.api.DeclaredPlugin;
import com.botmaker.plugin.api.RunViewHolder;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.run.RunView;
import com.botmaker.plugin.api.run.ViewTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunViewsTest {

    private static final Optional<ViewTarget> GAME = Optional.of(new ViewTarget.Window("7", "Game"));

    /** A plugin that shows a window, or says the window is closed, and draws every frame as its text. */
    public static final class Shows implements RunViewHolder {
        static volatile boolean open = true;
        static volatile Runnable changed;

        @Override
        public RunView view() {
            return new RunView() {
                @Override
                public Optional<ViewTarget> target() {
                    return open ? GAME : Optional.empty();
                }

                @Override
                public Optional<String> reason() {
                    return open ? Optional.empty() : Optional.of("The game window is closed.");
                }

                @Override
                public Optional<String> overlay(byte[] frame) {
                    return Optional.of(new String(frame));
                }

                @Override
                public void start(StudioServices services, Runnable told) {
                    changed = told;
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
        List<StudioPlugin> bound = List.of(new Plugin());
        return new RunViews(() -> bound, null, Runnable::run, () -> { });
    }

    @BeforeEach
    void theWindowIsOpen() {
        Shows.open = true;
    }

    @Test
    void withoutARunTheGameShowsAsItStands() {
        RunViews views = views();

        assertEquals(GAME, views.shown().target());
        assertSame(views.views().getFirst(), views.views().getFirst());
    }

    @Test
    void noPluginShowsNothingAndSaysNothing() {
        RunViews views = new RunViews(List::of, null, Runnable::run, () -> { });

        assertTrue(views.shown().target().isEmpty());
        assertTrue(views.shown().reason().isEmpty());
        assertTrue(views.overlay("x".getBytes()).isEmpty());
    }

    @Test
    void otherBoundPluginsGetNewIdleViews() {
        AtomicReference<List<StudioPlugin>> bound = new AtomicReference<>(List.of());
        RunViews views = new RunViews(bound::get, null, Runnable::run, () -> { });
        assertTrue(views.shown().target().isEmpty());

        bound.set(List.of(new Plugin()));

        assertEquals(GAME, views.shown().target());
    }

    @Test
    void aRunHasItsOwnViewsWhichGoWithIt() {
        RunViews views = views();
        RunView idle = views.views().getFirst();

        views.started();
        RunView running = views.views().getFirst();
        assertNotSame(idle, running);
        assertEquals(GAME, views.shown().target());
        assertEquals(Optional.of("hello"), views.overlay("hello".getBytes()));

        views.ended();
        assertNotSame(running, views.views().getFirst());
    }

    @Test
    void aClosedWindowGivesItsReasonAndNoTarget() {
        Shows.open = false;
        RunViews views = views();
        views.started();

        assertTrue(views.shown().target().isEmpty());
        assertEquals(Optional.of("The game window is closed."), views.shown().reason());
    }

    @Test
    void aViewThatSaysItChangedIsAskedAgain() {
        AtomicInteger told = new AtomicInteger();
        List<StudioPlugin> bound = List.of(new Plugin());
        RunViews views = new RunViews(() -> bound, null, Runnable::run, told::incrementAndGet);
        assertEquals(GAME, views.shown().target());

        Shows.open = false;
        Shows.changed.run();

        assertTrue(views.shown().target().isEmpty());
        assertEquals(1, told.get());
    }

    @Test
    void aSecondStartWhileTheRunGoesBuildsNoMoreViews() {
        RunViews views = views();
        views.started();
        RunView first = views.views().getFirst();
        views.started();

        assertEquals(1, views.views().size());
        assertSame(first, views.views().getFirst());
    }

    /** A view reads the project, so it is asked on the project's thread, which the frame loop never waits for. */
    @Test
    void viewsAreAskedOnTheThreadGivenAndNoCallerWaits() {
        Queue<Runnable> queued = new ArrayDeque<>();
        List<StudioPlugin> bound = List.of(new Plugin());
        RunViews views = new RunViews(() -> bound, null, queued::add, () -> { });

        assertTrue(views.shown().target().isEmpty());
        assertTrue(views.shown().target().isEmpty());
        assertEquals(1, queued.size(), "one ask at a time");

        queued.remove().run();
        assertEquals(GAME, views.shown().target());
    }

    @Test
    void aClosedProjectShowsNothing() {
        RunViews views = views();
        views.started();

        views.close();
        views.started();

        assertTrue(views.views().isEmpty());
        assertTrue(views.shown().target().isEmpty());
    }
}
