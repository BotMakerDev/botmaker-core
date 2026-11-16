// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.assist;

import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.run.RunEntries;
import com.botmaker.core.run.claims.RunRegistry;
import com.botmaker.core.run.claims.SeveralAsk;

import java.nio.file.Path;
import java.util.Optional;

/**
 * What runs the open bot for the run tools ({@link RunTools}): Studio's driver with a screen, the core itself without
 * one (plan phase 11). Each answers a sentence, or throws {@link IllegalArgumentException} with why not.
 */
public interface BotRuns {

    /** The open project's folder; empty with none open. */
    default Optional<Path> projectDir() {
        return Optional.empty();
    }

    /** ▶ Run the bot, as the toolbar does. */
    String runBot();

    /** ▶ Run {@code entry}, a plugin's declared run, instead of the bot's {@code main}, as its toolbar button does. */
    String runEntry(RunEntries.Declared entry);

    /**
     * Compiles once and starts the runs {@code ask} asks for, each dealt a row of its pools (spec
     * {@code 2026-11-15-run-claims-design.md} §5), or only counts them; answers what started, once it has.
     *
     * @throws IllegalArgumentException with why none started
     */
    default CoreEvent.RunsStarted runSeveral(SeveralAsk ask) {
        throw new IllegalArgumentException("Several runs at once are not served here.");
    }

    /** The live runs of this PC: where a run id names one of several runs, whichever process started it. */
    default RunRegistry registry() {
        return RunRegistry.ofThisPc();
    }

    /** Stops the run, the try or the debug session. */
    String stop();
}
