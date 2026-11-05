package com.botmaker.core.run;

import com.botmaker.core.event.CoreEvent;

import java.util.Optional;

/**
 * What a run asks before it compiles, of whoever judges the bot's code (Studio's diagnostics, which know the blocks
 * drawn): the problems to show for the file it judged, and why the run may not start now, said to the user. The run
 * tells every client the problems, whether or not they stop it.
 */
@FunctionalInterface
public interface RunCheck {

    /** Lets every run start: a host that judges nothing before javac does. */
    RunCheck NONE = () -> Verdict.PASS;

    Verdict check();

    /**
     * {@code shown} is every problem of the judged file, an empty list clearing the ones shown before, or empty when
     * no file was judged; {@code refusal} is why the run may not start, or empty when it may.
     */
    record Verdict(Optional<CoreEvent.Diagnostics> shown, Optional<String> refusal) {

        /** Nothing judged, nothing refused. */
        public static final Verdict PASS = new Verdict(Optional.empty(), Optional.empty());

        public Verdict {
            shown = shown == null ? Optional.empty() : shown;
            refusal = refusal == null ? Optional.empty() : refusal;
        }
    }
}
