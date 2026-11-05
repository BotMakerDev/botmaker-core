package com.botmaker.core.assist;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code read_trace}'s cursor ({@code after}), what {@code botmaker bot run} follows a run by. */
class RunLogFollowTest {

    @Test
    void aCursorGivesEachWholeLineOnceWithTheRunCounts() {
        RunLog log = new RunLog(Clock.systemUTC(), () -> false);
        log.output("earlier\n");
        RunLog.Since now = log.since(-1, 100);
        assertEquals(List.of(), now.lines(), "-1 starts from now");

        log.requested("the bot");
        log.started();
        log.output("hello\nLoad");
        RunLog.Since first = log.since(now.next(), 100);
        assertEquals(List.of("── started the bot ──", "hello"), first.lines(), "the unfinished line waits");
        assertEquals(1, first.mark().starts());

        log.output("ing done\n");
        log.stopped();
        RunLog.Since second = log.since(first.next(), 100);
        assertEquals("Loading done", second.lines().getFirst());
        assertEquals(1, second.mark().ends());
        assertEquals("next=" + second.next() + " starts=1 ends=1 busy=false skipped=0\nLoading done\n"
                + second.lines().get(1), RunTools.followed(second));
        assertEquals(List.of(), log.since(second.next(), 100).lines(), "nothing twice");
    }

    @Test
    void linesNoLongerKeptAreCounted() {
        RunLog log = new RunLog(Clock.systemUTC(), () -> false);
        RunLog.Since start = log.since(-1, 10);
        for (int i = 0; i < RunLog.LIMIT + 5; i++) log.output(i + "\n");

        RunLog.Since read = log.since(start.next(), 10);

        assertEquals(5, read.skipped());
        assertEquals("5", read.lines().getFirst());
        assertEquals(10, read.lines().size(), "at most what was asked; the next read goes on");
        assertEquals("15", log.since(read.next(), 1).lines().getFirst());
    }
}
