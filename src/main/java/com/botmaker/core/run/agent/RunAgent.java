// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.run.agent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The one agent Studio starts every bot with ({@code -javaagent:<jar>=<plan file>}): the host inside the bot's JVM
 * (spec {@code docs/superpowers/specs/2026-11-03-run-reports-design.md} §7, plan phase 3c). Before the bot's
 * {@code main} it reads the plan, starts the run's inner half (the log, the channel each declared plugin gets, the
 * wire to Studio) and installs the call trace ({@code docs/refactor/40-run-trace.md}).
 *
 * <p><b>Two halves.</b> This package runs on the bot's class path, which every bot class links to through
 * {@link TracedCalls}, so it names only the JDK ({@code RunAgentJarTest}). The inner half,
 * {@code runtime.agent.run}, names the wire, runlog, Jackson and plugin-host; Studio writes it to a jar of its own
 * with those packages ({@code runtime.RunAgentJar}), loaded child first ({@link InnerLoader}), so the bot's own
 * versions of them never meet Studio's.
 *
 * <p>A run never fails over the agent: a part that cannot start says so on standard error, and the bot runs
 * without it.
 */
public final class RunAgent {

    private static final String INNER = "com.botmaker.core.run.agent.run.AgentRun";

    /**
     * The exit code of a run the agent stopped before or as it started: one of several runs that could not take the
     * values it was dealt, or have its claims checked. A run on the wrong values would play with another run's.
     */
    public static final int STOPPED = 78;

    private RunAgent() {}

    private static Optional<byte[]> classBytes(ClassLoader loader, String binaryName) {
        try (InputStream in = loader.getResourceAsStream(binaryName.replace('.', '/') + ".class")) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** The agent's entry point: reads the plan Studio wrote, starts the run's host, and traces calls. */
    public static void premain(String planFile, Instrumentation instrumentation) {
        if (planFile == null || planFile.isBlank()) return;
        RunPlan plan;
        try {
            plan = RunPlan.read(Path.of(planFile));
        } catch (IOException | RuntimeException e) {
            System.err.println("[BotMaker] this run has no log and no trace: " + e.getMessage());
            return;
        }
        ClassLoader bot = ClassLoader.getSystemClassLoader();
        PooledMethods rewrites = plan.pooled().isEmpty() ? null : new PooledMethods(plan.pooled());
        if (rewrites != null) {
            // Before anything loads a listed class: building a pool again names its parameter (fills(Farm::account)).
            instrumentation.addTransformer(rewrites);
            Optional<String> unfit = PooledMethods.unfit(plan.pooled(), owner -> classBytes(bot, owner));
            if (unfit.isPresent()) {
                MainStop.install(instrumentation, unfit.get());
                return;
            }
        }
        RunLines lines = plan.inner().map(jar -> inner(jar, plan)).orElse(null);
        if (plan.runId().isPresent() && plan.several() && lines == null) {
            // Its claims would all be granted, and two runs would take one key. One run dealt nothing goes on with
            // them granted, as every run did before each was held.
            MainStop.install(instrumentation,
                    "this run is one of several, and its claims could not be checked against the others");
            return;
        }
        TracedCalls.to(lines);
        // Before the pools are built again, which loads bot classes: a class loaded before it is never traced.
        if (!plan.trace().traced().isEmpty()) instrumentation.addTransformer(new CallSites(plan.trace()));
        Optional<String> unbuilt = plan.pooled().isEmpty() ? Optional.empty()
                : PooledValues.load(plan.pooled(), bot);
        if (unbuilt.isEmpty() && rewrites != null) unbuilt = rewrites.failed();
        if (unbuilt.isPresent()) {
            if (lines != null) lines.ended();
            MainStop.install(instrumentation, unbuilt.get());
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            TracedCalls.reportOpen();
            if (lines != null) lines.ended();
        }, "botmaker-run-end"));
    }

    /** Starts the inner half from {@code jar}; null when it cannot, and the run keeps only the trace on stderr. */
    private static RunLines inner(Path jar, RunPlan plan) {
        try {
            InnerLoader loader = new InnerLoader(jar.toUri().toURL(), RunAgent.class.getClassLoader());
            return (RunLines) Class.forName(INNER, true, loader).getMethod("start", RunPlan.class)
                    .invoke(null, plan);
        } catch (InvocationTargetException e) {
            System.err.println("[BotMaker] this run keeps no log: " + e.getCause());
        } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError e) {
            System.err.println("[BotMaker] this run keeps no log: " + e);
        }
        return null;
    }
}
