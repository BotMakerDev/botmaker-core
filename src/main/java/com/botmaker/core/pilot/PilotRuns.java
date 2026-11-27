// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The runs a phone may pick from: the live runs of this PC's run registry, each named by the core's own run id, and
 * which of them the pilot holds a view for.
 *
 * <p>The pilot serves one open project, so {@link #viewed} is at most one of them; every other live run is listed
 * but has no picture, no target and no input; it can be stopped, paused and resumed by its id (phase 4b).
 */
public interface PilotRuns {

    /** No run is listed: the phone sees the game as the open project's views show it, with or without a bot. */
    PilotRuns NONE = new PilotRuns() {
        @Override
        public List<Listed> live() {
            return List.of();
        }

        @Override
        public Optional<String> viewed() {
            return Optional.empty();
        }
    };

    /**
     * One live run as a phone lists it.
     *
     * @param id            the core's run id, opaque to the phone
     * @param bot           what the run plays, for the picker's label
     * @param startedMillis when its process started, epoch milliseconds, 0 when unknown
     */
    record Listed(String id, String bot, long startedMillis) {}

    /** The live runs, the longest running first, so "the first run" is the same on every call. */
    List<Listed> live();

    /** The run the pilot holds a view for, when it is among {@link #live}. */
    Optional<String> viewed();

    /**
     * What the plugins show of the live run {@code id}, when it is not the one {@link #viewed} (that one has the
     * pilot's own views): a run started beside the open bot by this core. Empty for a run of another process, which has
     * no picture and no input here.
     */
    default Optional<RunViews> viewsOf(String id) {
        return Optional.empty();
    }

    /** The listed runs this list paused itself (by {@link #pause}); a run it did not pause is not named. */
    default Set<String> paused() {
        return Set.of();
    }

    /**
     * Ends the live run {@code id}, which the pilot holds no view for: a phone can still stop it. Only an id of
     * {@link #live} is acted on, never a pid a phone names.
     *
     * @return why it could not be done, or empty when it was
     */
    default Optional<String> stop(String id) {
        return Optional.of("This run cannot be controlled from here.");
    }

    /** {@link #stop}, for pausing the run. */
    default Optional<String> pause(String id) {
        return Optional.of("This run cannot be controlled from here.");
    }

    /** {@link #stop}, for resuming a run {@link #pause} paused. */
    default Optional<String> resume(String id) {
        return Optional.of("This run cannot be controlled from here.");
    }
}
