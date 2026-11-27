// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import java.util.List;
import java.util.Optional;

/**
 * The runs a phone may pick from: the live runs of this PC's run registry, each named by the core's own run id, and
 * which of them the pilot holds a view for.
 *
 * <p>The pilot serves one open project, so {@link #viewed} is at most one of them; every other live run is listed
 * but has no picture, no target and no control yet (the plan's phase 4b gives each run its own).
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
}
