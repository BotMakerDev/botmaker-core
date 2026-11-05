package com.botmaker.core.assist;

import com.botmaker.core.run.RunEntries;

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

    /** Stops the run, the try or the debug session. */
    String stop();
}
