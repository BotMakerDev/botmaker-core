// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.run.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * What the run agent does in one run, read from the file Studio names on the agent's command line: the calls it
 * traces ({@link TracePlan}), where the bot's runs keep their folders, the agent's inner jar, and, for one of several
 * runs, the values its row was dealt (spec {@code 2026-11-15-run-claims-design.md} §5).
 *
 * @param trace    the calls traced; nothing traced when its {@code traced} is empty
 * @param runs     the folder each run's own folder is made under ({@code <bot>/target/botmaker-runs}); empty when no
 *                 log is kept
 * @param inner    the agent's inner jar ({@code runtime.agent.run}, with the wire, the log writer, Jackson and the
 *                 plugin-pom reader); empty when Studio could not write it, and the run gets the trace only
 * @param pooled   the methods the agent rewrites to return what this run was dealt; none for one run, ▶ Try and tests,
 *                 where nothing is rewritten
 * @param runId    this run's id in the live runs of this PC, for a run the core holds there; empty otherwise
 * @param registry the folder of those live runs, which the run's claims are checked against; empty with no run id
 * @param hostValues the folder the plugins' values of this PC are under ({@code UserDirs.config()}), which the run's
 *                 plugins read and write through {@code RunHostValues}; empty when the run keeps none
 */
public record RunPlan(TracePlan trace, Optional<Path> runs, Optional<Path> inner, List<Pooled> pooled,
                      Optional<String> runId, Optional<Path> registry, Optional<Path> hostValues) {

    /**
     * One method the agent rewrites to return a dealt value (spec §5).
     *
     * @param owner      the parameter's class, its binary name
     * @param method     the parameter's method
     * @param descriptor its JVM descriptor, {@code ()Lcom/bot/GameAccount;}: a method that changed type is refused
     * @param value      the value dealt, as {@code Canonical} writes it: Java with every class fully qualified
     * @param pool       the {@code @Pools} method it was dealt from, {@code group#method}, which the agent builds
     *                   again to check it lists what the core read; empty for a plugin's pool
     * @param elements   that pool's elements as the core read them, each as {@code Canonical} writes it
     */
    public record Pooled(String owner, String method, String descriptor, String value, String pool,
                         List<String> elements) {

        public Pooled {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(value, "value");
            pool = pool == null ? "" : pool;
            elements = List.copyOf(elements);
        }

        /** The method's key: its owner, name and descriptor, {@code com.bot.Farm#account()Ljava/lang/String;}. */
        public String key() {
            return owner + "#" + method + descriptor;
        }

        /** {@code Farm.account()}, as a person reads it. */
        public String said() {
            return owner.substring(owner.lastIndexOf('.') + 1).replace('$', '.') + "." + method + "()";
        }
    }

    private static final String RUNS = "runs";
    private static final String INNER = "inner";
    private static final String POOLED = "pooled";
    private static final String RUN_ID = "runId";
    private static final String REGISTRY = "registry";
    private static final String HOST_VALUES = "hostValues";

    public RunPlan {
        pooled = List.copyOf(pooled);
    }

    /** A plan keeping no values of this PC. */
    public RunPlan(TracePlan trace, Optional<Path> runs, Optional<Path> inner, List<Pooled> pooled,
                   Optional<String> runId, Optional<Path> registry) {
        this(trace, runs, inner, pooled, runId, registry, Optional.empty());
    }

    /** A plan of one run: nothing rewritten, held nowhere. */
    public RunPlan(TracePlan trace, Optional<Path> runs, Optional<Path> inner) {
        this(trace, runs, inner, List.of(), Optional.empty(), Optional.empty());
    }

    public void write(Path file) throws IOException {
        Properties properties = new Properties();
        trace.into(properties);
        runs.ifPresent(p -> properties.setProperty(RUNS, p.toAbsolutePath().toString()));
        inner.ifPresent(p -> properties.setProperty(INNER, p.toAbsolutePath().toString()));
        runId.ifPresent(id -> properties.setProperty(RUN_ID, id));
        registry.ifPresent(p -> properties.setProperty(REGISTRY, p.toAbsolutePath().toString()));
        hostValues.ifPresent(p -> properties.setProperty(HOST_VALUES, p.toAbsolutePath().toString()));
        properties.setProperty(POOLED, Integer.toString(pooled.size()));
        for (int i = 0; i < pooled.size(); i++) {
            Pooled one = pooled.get(i);
            String at = POOLED + "." + i + ".";
            properties.setProperty(at + "owner", one.owner());
            properties.setProperty(at + "method", one.method());
            properties.setProperty(at + "descriptor", one.descriptor());
            properties.setProperty(at + "value", one.value());
            properties.setProperty(at + "pool", one.pool());
            properties.setProperty(at + "elements", Integer.toString(one.elements().size()));
            for (int j = 0; j < one.elements().size(); j++) {
                properties.setProperty(at + "elements." + j, one.elements().get(j));
            }
        }
        TracePlan.store(properties, file);
    }

    public static RunPlan read(Path file) throws IOException {
        Properties properties = TracePlan.load(file);
        List<Pooled> pooled = new ArrayList<>();
        int count = count(properties, POOLED);
        for (int i = 0; i < count; i++) {
            String at = POOLED + "." + i + ".";
            List<String> elements = new ArrayList<>();
            int listed = count(properties, at + "elements");
            for (int j = 0; j < listed; j++) elements.add(properties.getProperty(at + "elements." + j, ""));
            pooled.add(new Pooled(properties.getProperty(at + "owner", ""), properties.getProperty(at + "method", ""),
                    properties.getProperty(at + "descriptor", ""), properties.getProperty(at + "value", ""),
                    properties.getProperty(at + "pool", ""), elements));
        }
        String runId = properties.getProperty(RUN_ID, "").strip();
        return new RunPlan(TracePlan.from(properties), path(properties, RUNS), path(properties, INNER), pooled,
                runId.isEmpty() ? Optional.empty() : Optional.of(runId), path(properties, REGISTRY),
                path(properties, HOST_VALUES));
    }

    private static int count(Properties properties, String key) {
        try {
            return Math.max(0, Integer.parseInt(properties.getProperty(key, "0").strip()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The run plan's " + key + " is not a count", e);
        }
    }

    private static Optional<Path> path(Properties properties, String key) {
        String value = properties.getProperty(key, "").strip();
        return value.isEmpty() ? Optional.empty() : Optional.of(Path.of(value));
    }
}
