package com.botmaker.core.run;

import com.botmaker.core.run.agent.RunAgent;
import com.botmaker.core.run.agent.RunPlan;
import com.botmaker.core.run.agent.TracePlan;
import com.botmaker.core.run.claims.PoolSources;
import com.botmaker.core.run.claims.PooledRuns;
import com.botmaker.core.run.claims.RunRegistry;
import com.botmaker.core.run.claims.SeveralAsk;
import com.botmaker.core.run.pooled.PooledBot;
import com.botmaker.core.run.pooled.PooledParams;
import com.botmaker.core.run.pooled.PooledPools;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.index.PoolReader;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.runlog.RunLog;
import com.botmaker.shared.Diag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Several runs of one bot as the core starts them: real JVMs with the run agent, each dealt its own row of a
 * {@code @Pools} group, reading it through a direct call to the pooled method, and each held in the live runs until its
 * process ends.
 */
class PooledRunsTest {

    private static final String FIRST = "account-1@example.com";
    private static final String SECOND = "account-2@example.com";

    @Test
    void twoRunsReadTheirOwnAccountAndFreeTheirRowsWhenTheyEnd(@TempDir Path dir) throws Exception {
        RunRegistry registry = new RunRegistry(dir.resolve("live"), RunRegistry.Alive.PROCESSES);
        PoolSources.Read read = read(FIRST, SECOND);
        assertEquals(Optional.empty(), PooledRuns.unfit(location(PooledParams.class), read));

        PooledRuns.Outcome outcome = PooledRuns.start("bot", dir.resolve("bot"), read, SeveralAsk.of(2), registry,
                launcher(dir, registry));
        assertEquals(2, outcome.started().size(), outcome.words());
        assertEquals(2, registry.live().size(), "held while they run");

        List<String> said = new ArrayList<>();
        for (PooledRuns.Started run : outcome.started()) said.add(finished(run.process()).output());
        assertTrue(said.stream().anyMatch(out -> out.contains("account=" + FIRST)), said.toString());
        assertTrue(said.stream().anyMatch(out -> out.contains("account=" + SECOND)), said.toString());
        assertTrue(said.stream().noneMatch(out -> out.contains("account=body")), said.toString());
        awaitEmpty(registry);
    }

    @Test
    void aCountAndAnAllOrNoneThatFallsShortHoldAndStartNothing(@TempDir Path dir) {
        RunRegistry registry = new RunRegistry(dir.resolve("live"), RunRegistry.Alive.PROCESSES);
        PooledRuns.Launcher never = (runId, pooled) -> {
            throw new AssertionError("nothing starts");
        };

        PooledRuns.Outcome counted = PooledRuns.start("bot", dir.resolve("bot"), read(FIRST, SECOND),
                new SeveralAsk(3, SeveralAsk.How.COUNT, Map.of()), registry, never);
        assertEquals(2, counted.dealing().rows().size());
        assertTrue(counted.words().startsWith("2 of 3 can run: "), counted.words());
        assertEquals(List.of(), counted.started());
        assertEquals(List.of("", ""), counted.listed().stream().map(PooledRuns.Listed::runId).toList());

        PooledRuns.Outcome none = PooledRuns.start("bot", dir.resolve("bot"), read(FIRST, SECOND),
                new SeveralAsk(3, SeveralAsk.How.ALL_OR_NONE, Map.of()), registry, never);
        assertEquals(List.of(), none.started());
        assertTrue(none.words().startsWith("0 of 3 started: "), none.words());
        assertTrue(none.words().endsWith("all or none was asked, so none started"), none.words());
        assertEquals(List.of(), registry.live(), "nothing held");
    }

    @Test
    @Timeout(60)
    void allOrNoneStopsTheRunsItLaunchedWhenOneDoesNotStart(@TempDir Path dir) throws Exception {
        RunRegistry registry = new RunRegistry(dir.resolve("live"), RunRegistry.Alive.PROCESSES);
        PooledRuns.Launcher real = launcher(dir, registry);
        List<String> launched = new ArrayList<>();
        PooledRuns.Launcher secondFails = (runId, pooled) -> {
            launched.add(runId);
            if (launched.size() == 2) throw new IOException("no JVM");
            return real.launch(runId, pooled);
        };

        PooledRuns.Outcome outcome = PooledRuns.start("bot", dir.resolve("bot"), read(FIRST, SECOND),
                new SeveralAsk(2, SeveralAsk.How.ALL_OR_NONE, Map.of()), registry, secondFails);
        assertEquals(List.of(), outcome.started(), outcome.words());
        assertTrue(outcome.words().contains("did not start: no JVM"), outcome.words());
        assertTrue(outcome.words().endsWith("all or none was asked, so none started"), outcome.words());
        assertEquals(List.of(), registry.live(), "both freed");
    }

    @Test
    void aRunThatCrashesFreesItsRowForTheNextStart(@TempDir Path dir) throws Exception {
        RunRegistry registry = new RunRegistry(dir.resolve("live"), RunRegistry.Alive.PROCESSES);
        PoolSources.Read read = read(FIRST, SECOND);

        PooledRuns.Outcome crashed = PooledRuns.start("bot", dir.resolve("bot"), read, SeveralAsk.of(2), registry,
                launcher(dir, registry, "crash"));
        for (PooledRuns.Started run : crashed.started()) assertEquals(9, finished(run.process()).exit());
        awaitEmpty(registry);

        PooledRuns.Outcome again = PooledRuns.start("bot", dir.resolve("bot"), read, SeveralAsk.of(2), registry,
                launcher(dir, registry));
        assertEquals(2, again.started().size(), "both rows were freed: " + again.words());
        for (PooledRuns.Started run : again.started()) finished(run.process());
        awaitEmpty(registry);
    }

    @Test
    @Timeout(value = 90, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void ofTwoLiveRunsClaimingOneKeyOnlyOneGetsIt(@TempDir Path dir) throws Exception {
        RunRegistry registry = new RunRegistry(dir.resolve("live"), RunRegistry.Alive.PROCESSES);
        PooledRuns.Outcome outcome = PooledRuns.start("bot", dir.resolve("bot"), read(FIRST, SECOND), SeveralAsk.of(2),
                registry, launcher(dir, registry, "claim"));
        try {
            assertEquals(2, outcome.started().size(), outcome.words());

            List<String> claimed = new ArrayList<>();
            List<BufferedReader> outputs = new ArrayList<>();
            for (PooledRuns.Started run : outcome.started()) {
                BufferedReader out = run.process().inputReader(StandardCharsets.UTF_8);
                outputs.add(out);
                claimed.add(claimedLine(out));
            }
            assertEquals(List.of("claimed=false", "claimed=true"), claimed.stream().sorted().toList(),
                    claimed.toString());
            assertEquals(1, registry.live().stream().filter(run -> run.claims().contains(PooledBot.KEY)).count());

            for (int i = 0; i < outcome.started().size(); i++) {
                Process process = outcome.started().get(i).process();
                process.getOutputStream().close();
                outputs.get(i).transferTo(Writer.nullWriter());
                assertTrue(process.waitFor(30, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue());
            }
            awaitEmpty(registry);
        } finally {
            // A run that never claimed waits on its input for ever: ended here, its row with it.
            for (PooledRuns.Started run : outcome.started()) run.process().destroyForcibly();
        }
    }

    @Test
    void aKeyACrashedRunClaimedIsFreeForTheNextRun(@TempDir Path dir) throws Exception {
        RunRegistry registry = new RunRegistry(dir.resolve("live"), RunRegistry.Alive.PROCESSES);
        for (int attempt = 1; attempt <= 2; attempt++) {
            PooledRuns.Outcome outcome = PooledRuns.start("bot", dir.resolve("bot"), read(FIRST, SECOND),
                    SeveralAsk.of(1), registry, launcher(dir, registry, "claim-and-crash"));
            Finished run = finished(outcome.started().getFirst().process());
            assertEquals(9, run.exit(), run.output());
            assertTrue(run.output().contains("claimed=true"), "run " + attempt + ": " + run.output());
            awaitEmpty(registry);
        }
    }

    /** The line a {@code claim} run says once it has claimed, read as it comes: the run stays alive after it. */
    private static String claimedLine(BufferedReader out) throws IOException {
        for (String line = out.readLine(); line != null; line = out.readLine()) {
            if (line.startsWith("claimed=")) return line;
        }
        throw new AssertionError("the run ended before it claimed");
    }

    @Test
    void aPoolThatNoLongerListsWhatWasDealtStopsTheRunBeforeItsMain(@TempDir Path dir) throws Exception {
        RunRegistry registry = new RunRegistry(dir.resolve("live"), RunRegistry.Alive.PROCESSES);
        // Read before an edit the run's compile has: the pool lists another account now.
        PoolSources.Read read = read(FIRST, "account-3@example.com");

        PooledRuns.Outcome outcome = PooledRuns.start("bot", dir.resolve("bot"), read, SeveralAsk.of(1), registry,
                launcher(dir, registry));
        Finished run = finished(outcome.started().getFirst().process());
        assertEquals(RunAgent.STOPPED, run.exit(), run.output());
        assertTrue(run.output().contains("PooledPools.accounts()"), run.output());
        assertTrue(!run.output().contains("account="), "the bot's main never ran: " + run.output());
        awaitEmpty(registry);
    }

    @Test
    void aMethodTheCompileLacksRefusesTheStart() throws Exception {
        PoolReader.Filled gone = new PoolReader.Filled(PooledParams.class.getName(), "login", "()Ljava/lang/String;",
                String.class, String.class);
        PoolSources.Read read = PoolSources.ofBot("bot", List.of(new PoolReader.Reading(PooledPools.class.getName(),
                "accounts", Optional.of(gone), Optional.empty(), values(FIRST), 0, 1, false, "")));
        Optional<String> why = PooledRuns.unfit(location(PooledParams.class), read);
        assertTrue(why.orElseThrow().contains("PooledParams.login() is not in the compiled bot"), why.get());
    }

    private static PoolSources.Read read(String... accounts) {
        PoolReader.Filled filled = new PoolReader.Filled(PooledParams.class.getName(), "account",
                "()Ljava/lang/String;", String.class, String.class);
        return PoolSources.ofBot("bot", List.of(new PoolReader.Reading(PooledPools.class.getName(), "accounts",
                Optional.of(filled), Optional.empty(), values(accounts), 0, 1, false, "")));
    }

    private static List<JavaValue> values(String... accounts) {
        List<JavaValue> out = new ArrayList<>();
        for (String account : accounts) out.add(JavaValue.parse("\"" + account + "\"").orElseThrow());
        return out;
    }

    /** Starts {@link PooledBot} in a JVM of its own with the run agent, as {@code CodeExecutionService} does. */
    private static PooledRuns.Launcher launcher(Path dir, RunRegistry registry, String... args) {
        return (runId, pooled) -> {
            try {
                RunAgentJar.Jars jars = RunAgentJar.jars();
                Path tests = location(PooledBot.class);
                Path plan = dir.resolve("plan-" + runId + ".properties");
                new RunPlan(new TracePlan(Set.of(tests), Set.of(), Set.of()), Optional.of(dir.resolve(RunLog.FOLDER)),
                        jars.inner(), pooled, Optional.of(runId), Optional.of(registry.folder())).write(plan);
                String classpath = String.join(File.pathSeparator, tests.toString(),
                        location(StudioPlugin.class).toString(), location(Diag.class).toString(),
                        location(RunLog.class).toString());
                List<String> command = new ArrayList<>(List.of(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-javaagent:" + jars.agent() + "=" + plan, "-cp", classpath, PooledBot.class.getName()));
                command.addAll(List.of(args));
                return new ProcessBuilder(command).redirectErrorStream(true).start();
            } catch (URISyntaxException e) {
                throw new IOException(e);
            }
        };
    }

    private record Finished(int exit, String output) {}

    private static Finished finished(Process process) throws IOException, InterruptedException {
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        // Well under the sixty seconds an exit from premain takes on Windows (MainStop).
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), output);
        return new Finished(process.exitValue(), output);
    }

    /** The registry once each ended run's release has landed: freed on exit, asynchronously. */
    private static void awaitEmpty(RunRegistry registry) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!registry.live().isEmpty() && System.nanoTime() < until) Thread.sleep(50);
        assertEquals(List.of(), registry.live());
    }

    private static Path location(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}
