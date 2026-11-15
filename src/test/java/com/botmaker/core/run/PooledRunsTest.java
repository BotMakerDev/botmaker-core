package com.botmaker.core.run;

import com.botmaker.core.run.agent.RunAgent;
import com.botmaker.core.run.agent.RunPlan;
import com.botmaker.core.run.agent.TracePlan;
import com.botmaker.core.run.claims.PoolSources;
import com.botmaker.core.run.claims.PooledRuns;
import com.botmaker.core.run.claims.RunRegistry;
import com.botmaker.core.run.pooled.PooledBot;
import com.botmaker.core.run.pooled.PooledParams;
import com.botmaker.core.run.pooled.PooledPools;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.index.PoolReader;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.runlog.RunLog;
import com.botmaker.shared.Diag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
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

        PooledRuns.Outcome outcome = PooledRuns.start("bot", dir.resolve("bot"), read, 2, Map.of(), registry,
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
    void aRunThatCrashesFreesItsRowForTheNextStart(@TempDir Path dir) throws Exception {
        RunRegistry registry = new RunRegistry(dir.resolve("live"), RunRegistry.Alive.PROCESSES);
        PoolSources.Read read = read(FIRST, SECOND);

        PooledRuns.Outcome crashed = PooledRuns.start("bot", dir.resolve("bot"), read, 2, Map.of(), registry,
                launcher(dir, registry, "crash"));
        for (PooledRuns.Started run : crashed.started()) assertEquals(9, finished(run.process()).exit());
        awaitEmpty(registry);

        PooledRuns.Outcome again = PooledRuns.start("bot", dir.resolve("bot"), read, 2, Map.of(), registry,
                launcher(dir, registry));
        assertEquals(2, again.started().size(), "both rows were freed: " + again.words());
        for (PooledRuns.Started run : again.started()) finished(run.process());
        awaitEmpty(registry);
    }

    @Test
    void aPoolThatNoLongerListsWhatWasDealtStopsTheRunBeforeItsMain(@TempDir Path dir) throws Exception {
        RunRegistry registry = new RunRegistry(dir.resolve("live"), RunRegistry.Alive.PROCESSES);
        // Read before an edit the run's compile has: the pool lists another account now.
        PoolSources.Read read = read(FIRST, "account-3@example.com");

        PooledRuns.Outcome outcome = PooledRuns.start("bot", dir.resolve("bot"), read, 1, Map.of(), registry,
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
