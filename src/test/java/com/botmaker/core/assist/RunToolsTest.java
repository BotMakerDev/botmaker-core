package com.botmaker.core.assist;

import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.run.RunEntries;
import com.botmaker.core.run.claims.RunRegistry;
import com.botmaker.core.run.claims.SeveralAsk;
import com.botmaker.core.run.claims.SeveralRuns;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** run_bot {runs} over a fake dealing, and a run id on run_state, stop and read_trace (plan run-claims, phase 11). */
class RunToolsTest {

    @TempDir
    Path dir;

    private static final CoreEvent.RunsStarted SEVEN_OF_ELEVEN = new CoreEvent.RunsStarted("t-1",
            "7 of 11 can run: 4 instances missing",
            List.of(new CoreEvent.RunsStarted.Started(1, "r-1", Map.of("Sdk#instance", List.of("Instance.named(\"ld-1\")"))),
                    new CoreEvent.RunsStarted.Started(2, "r-2", Map.of())),
            List.of("accounts() could not be read"), List.of("Create 4 instances"), false);

    private static final CoreEvent.RunsStarted COUNTED = new CoreEvent.RunsStarted("t-2",
            "1 of 2 can run: 1 instance missing",
            List.of(new CoreEvent.RunsStarted.Started(1, "", Map.of("Sdk#instance", List.of("Instance.named(\"ld-1\")")))),
            List.of(), List.of("Create 1 instance"), true);

    private static RunTools.Run runs(Integer runs) {
        return new RunTools.Run(null, null, runs, null, null, null);
    }

    /** A bot whose several runs are dealt {@code started}, its live runs in a registry of the test's own. */
    private final class Fake implements BotRuns {
        final Path project = dir.resolve("farm");
        final RunRegistry registry = new RunRegistry(dir.resolve("live"), (pid, started) -> true);
        SeveralAsk asked;

        @Override
        public Optional<Path> projectDir() {
            return Optional.of(project);
        }

        @Override
        public String runBot() {
            return "Started the bot.";
        }

        @Override
        public String runEntry(RunEntries.Declared entry) {
            return "Started " + entry;
        }

        @Override
        public CoreEvent.RunsStarted runSeveral(SeveralAsk ask) {
            asked = ask;
            return ask.how() == SeveralAsk.How.COUNT ? COUNTED : SEVEN_OF_ELEVEN;
        }

        @Override
        public RunRegistry registry() {
            return registry;
        }

        @Override
        public String stop() {
            return "Stopped the run.";
        }
    }

    private static RunLog idleLog() {
        return new RunLog(Clock.systemUTC(), () -> false);
    }

    @Test
    void severalRunsAnswerWhatStartedEachRunItsIdAndOffersOnlyListed() {
        Fake bot = new Fake();
        String answer = RunTools.run(bot, idleLog(), runs(11));
        assertEquals(SeveralAsk.of(11), bot.asked);
        assertTrue(answer.startsWith("7 of 11 can run: 4 instances missing."), answer);
        assertTrue(answer.contains("- run 1, id r-1: Sdk#instance = Instance.named(\"ld-1\")"), answer);
        assertTrue(answer.contains("- run 2, id r-2\n"), answer);
        assertTrue(answer.contains("Warnings: accounts() could not be read"), answer);
        assertTrue(answer.contains("nothing here runs them): Create 4 instances"), answer);
    }

    @Test
    void severalRunsRefuseAnEntryAWaitAndFewerThanOne() {
        Fake bot = new Fake();
        assertThrows(IllegalArgumentException.class, () -> RunTools.run(bot, idleLog(), runs(0)));
        assertThrows(IllegalArgumentException.class, () -> RunTools.run(bot, idleLog(), runs(SeveralRuns.MOST + 1)));
        assertThrows(IllegalArgumentException.class, () -> RunTools.run(bot, idleLog(),
                new RunTools.Run("Explore map", null, 2, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> RunTools.run(bot, idleLog(),
                new RunTools.Run(null, 30, 2, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> RunTools.run(bot, idleLog(),
                new RunTools.Run(null, null, 2, null, null, List.of("5:Sdk#instance=x"))));
        // A dry run, all or none and pins are a start of several runs' only.
        assertThrows(IllegalArgumentException.class, () -> RunTools.run(bot, idleLog(),
                new RunTools.Run(null, null, null, true, null, null)));
        assertThrows(IllegalArgumentException.class, () -> RunTools.run(bot, idleLog(),
                new RunTools.Run(null, null, null, null, null, List.of("Sdk#instance=x"))));
        assertEquals(null, bot.asked);
    }

    @Test
    void aDryRunListsWhatCouldRunWithNoRunIdAndPinsAndAllOrNoneReachTheStart() {
        Fake bot = new Fake();
        String answer = RunTools.run(bot, idleLog(), new RunTools.Run(null, null, 2, true, null, null));
        assertEquals(SeveralAsk.How.COUNT, bot.asked.how());
        assertTrue(answer.startsWith("1 of 2 can run: 1 instance missing.\n- run 1: Sdk#instance = "), answer);
        assertTrue(!answer.contains("read_trace"), "nothing started to read: " + answer);

        RunTools.run(bot, idleLog(), new RunTools.Run(null, null, 2, null, true,
                List.of("2:Sdk#instance=Instance.named(\"ld-2\")")));
        assertEquals(new SeveralAsk(2, SeveralAsk.How.ALL_OR_NONE,
                Map.of(1, Map.of("Sdk#instance", List.of("Instance.named(\"ld-2\")")))), bot.asked);
    }

    @Test
    void aRunIdSaysWhetherItGoesAndReadsItsOwnOutput() throws Exception {
        Fake bot = new Fake();
        bot.registry.hold(RunRegistry.LiveRun.ofThisProcess("r-1", SeveralRuns.bot(bot.project), List.of()));
        Files.createDirectories(SeveralRuns.outputs(bot.project));
        Files.writeString(SeveralRuns.log(bot.project, "r-1"), "one\ntwo\nthree\n");

        assertEquals("Run r-1 goes.", RunTools.state(bot, idleLog(), "r-1"));
        assertTrue(RunTools.state(bot, idleLog(), "r-9").startsWith("Run r-9 does not go"));
        assertTrue(RunTools.state(bot, idleLog(), null).endsWith("1 run of this bot go: r-1."));
        assertEquals("Run r-1 goes.\ntwo\nthree", RunTools.ownOutput(bot, "r-1", 2));
        assertThrows(IllegalArgumentException.class, () -> RunTools.ownOutput(bot, "r-9", 5));
        assertThrows(IllegalArgumentException.class, () -> RunTools.ownOutput(bot, "../x", 5));

        // One more run waits for them, and a plain stop is for them; this test's own process is never killed.
        String refused = assertThrows(IllegalArgumentException.class,
                () -> RunTools.run(bot, idleLog(), runs(null))).getMessage();
        assertTrue(refused.startsWith("1 run of this bot goes"), refused);
        assertEquals("Stopped 0 runs. run_state says when they have ended.", RunTools.stopped(bot, idleLog(), null));
    }

    @Test
    void aStopWithoutARunIdStopsTheRunAndAnUnknownIdStopsNothing() {
        Fake bot = new Fake();
        assertEquals("Stopped the run.", RunTools.stopped(bot, idleLog(), null));
        assertTrue(RunTools.stopped(bot, idleLog(), "r-9").startsWith("Run r-9 does not go"));
        assertEquals("None of this bot's several runs goes.", RunTools.stopped(bot, idleLog(), "all"));
    }

    @Test
    void aLongOutputIsReadFromItsEnd() throws Exception {
        Fake bot = new Fake();
        Files.createDirectories(SeveralRuns.outputs(bot.project));
        StringBuilder printed = new StringBuilder();
        for (int line = 1; line <= 5000; line++) printed.append("line ").append(line).append('\n');
        Files.writeString(SeveralRuns.log(bot.project, "r-2"), printed);
        assertEquals(List.of("line 4999", "line 5000"), SeveralRuns.lastLines(bot.project, "r-2", 2));
        assertEquals(5000, SeveralRuns.lastLines(bot.project, "r-2", 5000).size());
    }
}
