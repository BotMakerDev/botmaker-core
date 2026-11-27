// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.plugin.api.DeclaredPlugin;
import com.botmaker.plugin.api.RunViewHolder;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.run.BackgroundLaunch;
import com.botmaker.plugin.api.run.RunView;
import com.botmaker.plugin.api.run.ViewTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PilotBackgroundTest {

    @TempDir
    Path dir;

    private PilotBackground over(RunViews views, AtomicInteger changes) {
        return new PilotBackground(views, dir, changes::incrementAndGet);
    }

    @Test
    void aBotWithNothingForAPrivateDisplayOffersNoneAndRefusesToStart() {
        AtomicInteger changes = new AtomicInteger();
        PilotBackground background = over(new RunViews(List::of, null, Runnable::run, () -> { }), changes);

        assertFalse(background.status().offered());
        assertFalse(background.status().running());
        assertTrue(background.start().orElseThrow().contains("nothing to start"));
        assertEquals(0, changes.get());
    }

    @Test
    void aPluginsWishIsOfferedAndAStartThatCannotBeginSaysWhy() {
        AtomicInteger changes = new AtomicInteger();
        RunViews views = new RunViews(() -> List.of(new RunViewsTest.Plugin()), null, Runnable::run, () -> { });
        PilotBackground background = over(views, changes);

        assertTrue(background.status().offered());
        // "exe:game" is no launch target the launcher can isolate, so nothing comes up, whichever way it is refused.
        // A computer without the session backend refuses at once (the answer); one with it says why in the status.
        boolean said = background.start().isPresent();
        assertFalse(background.status().running());
        assertTrue(said || background.status().message() != null);
    }

    @Test
    void aWishNoComputerCanReadIsRefusedBeforeAnythingIsSpent() {
        RunViews views = new RunViews(() -> List.of(new Unreadable()), null, Runnable::run, () -> { });

        assertTrue(over(views, new AtomicInteger()).start().orElseThrow().contains("not one this computer"));
    }

    /** A plugin whose bot names a launch target with no colon in it. */
    public static final class Unreadable extends DeclaredPlugin {
        public Unreadable() {
            super(StudioPlugin.declare().views(Odd.class));
        }
    }

    public static final class Odd implements RunViewHolder {
        @Override
        public RunView view() {
            return new RunView() {
                @Override
                public Optional<ViewTarget> target() {
                    return Optional.empty();
                }

                @Override
                public Optional<BackgroundLaunch> background() {
                    return Optional.of(new BackgroundLaunch("nocolon", ""));
                }
            };
        }
    }

    @Test
    void stoppingWithNothingRunningSaysSoAndTellsTheClients() {
        AtomicInteger changes = new AtomicInteger();
        PilotBackground background = over(new RunViews(List::of, null, Runnable::run, () -> { }), changes);

        background.stop();

        assertEquals("Background mode is off.", background.status().message());
        assertEquals(1, changes.get());
    }
}
