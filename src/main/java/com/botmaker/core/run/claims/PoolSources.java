package com.botmaker.core.run.claims;

import com.botmaker.core.run.agent.RunPlan;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.grammar.ValueGrammar;
import com.botmaker.java.index.PoolReader;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.run.PluginPool;
import com.botmaker.plugin.api.source.ManagedParam;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The pools a dealing reads (spec {@code 2026-11-15-run-claims-design.md} §2, §4): the plugins' first, each asked
 * for this PC's candidates now, then the bot's own as the grammar read them. Every element is {@link Canonical}'s
 * writing, so two that read the same are one, and the run's agent builds what was dealt from it.
 */
public final class PoolSources {

    private PoolSources() {}

    /**
     * The bot's method a pool fills, which a run's agent rewrites to return what the run was dealt.
     *
     * @param owner      the method's class, its binary name
     * @param method     its name
     * @param descriptor its JVM descriptor, {@code ()Lcom/bot/GameAccount;}
     * @param pool       the {@code @Pools} method that fills it, {@code group#method}, which the agent builds again;
     *                   empty for a plugin's pool
     */
    public record Target(String owner, String method, String descriptor, String pool) {

        public Target {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(descriptor, "descriptor");
            pool = pool == null ? "" : pool;
        }
    }

    /**
     * The pools to deal from.
     *
     * @param pools    in the order a row is dealt
     * @param targets  by each pool's parameter, the method it fills
     * @param warnings what several runs should be told: a pool that could not be read is dealt by nothing
     */
    public record Read(List<Dealer.Source> pools, Map<String, Target> targets, List<String> warnings) {

        public Read {
            pools = List.copyOf(pools);
            targets = Map.copyOf(targets);
            warnings = List.copyOf(warnings);
        }

        /**
         * What row {@code row}'s run is given: for each pool, its method and the value dealt, a list of the picks
         * for a pool that takes several.
         */
        public List<RunPlan.Pooled> pooled(Dealer.Row row) {
            List<RunPlan.Pooled> out = new ArrayList<>();
            for (Dealer.Source pool : pools) {
                List<String> picks = row.picks().get(pool.parameter());
                Target target = targets.get(pool.parameter());
                if (picks == null || picks.isEmpty() || target == null) continue;
                String value = pool.take() > 0 ? Canonical.listOf(picks) : picks.getFirst();
                out.add(new RunPlan.Pooled(target.owner(), target.method(), target.descriptor(), value, target.pool(),
                        target.pool().isEmpty() ? List.of() : pool.elements()));
            }
            return out;
        }

        /** Every method a dealing would rewrite, each with its pool: what the core checks against the compile. */
        public List<RunPlan.Pooled> methods() {
            List<RunPlan.Pooled> out = new ArrayList<>();
            for (Dealer.Source pool : pools) {
                Target target = targets.get(pool.parameter());
                if (target == null) continue;
                out.add(new RunPlan.Pooled(target.owner(), target.method(), target.descriptor(), "", target.pool(),
                        List.of()));
            }
            return out;
        }
    }

    /** One plugin's pools, as the host loaded it. */
    public record Declared(String coordinate, List<PluginPool<?>> pools) {

        public Declared {
            pools = List.copyOf(pools);
        }
    }

    /**
     * The plugins' pools then the bot's.
     *
     * @param plugins  each plugin's pools, a plugin after those it depends on
     * @param bound    the bot's method a plugin's parameter id is bound to; empty for a parameter the bot has no method
     *                 for (a conditional one whose condition does not hold), whose pool is left out of the row
     * @param bot      who the bot's own elements are held under
     * @param readings the bot's {@code @Pools} groups, read
     * @throws Dealer.Failed when a plugin throws, or gives a candidate the grammar cannot write
     */
    public static Read of(List<Declared> plugins, Function<String, Optional<Target>> bound, ValueGrammar grammar,
                          StudioServices services, String bot, List<PoolReader.Reading> readings) {
        List<Dealer.Source> pools = new ArrayList<>();
        Map<String, Target> targets = new LinkedHashMap<>();
        // By parameter id, each element back to the plugin's own value: what a narrowing is handed.
        Map<String, Map<String, Object>> typed = new HashMap<>();
        for (Declared plugin : plugins) {
            for (PluginPool<?> pool : plugin.pools()) {
                Optional<Target> method = bound.apply(pool.parameter().id());
                if (method.isEmpty()) continue;
                pools.add(plugin(plugin.coordinate(), method.get().method(), pool, grammar, services, typed));
                targets.put(pool.parameter().id(), method.get());
            }
        }
        Read own = ofBot(bot, readings);
        pools.addAll(own.pools());
        targets.putAll(own.targets());
        return new Read(pools, targets, own.warnings());
    }

    /** The bot's own pools, in its groups' order; one that does not read is a warning and no pool. */
    public static Read ofBot(String bot, List<PoolReader.Reading> readings) {
        List<Dealer.Source> pools = new ArrayList<>();
        Map<String, Target> targets = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (PoolReader.Reading reading : readings) {
            if (!reading.reads() || reading.fills().isEmpty()) {
                warnings.add(unread(reading, reading.problem()));
                continue;
            }
            List<String> elements;
            try {
                elements = reading.elements().stream().map(Canonical::of).toList();
            } catch (Canonical.Unwritten e) {
                warnings.add(unread(reading, e.getMessage()));
                continue;
            }
            PoolReader.Filled filled = reading.fills().get();
            String parameter = filled.owner() + "#" + filled.method();
            Dealer.Source pool = Dealer.Source.bot(bot, parameter, reading.method(), elements);
            if (reading.take() > 0) pool = pool.taking(reading.take());
            if (reading.each() > 1) pool = pool.sharedBy(reading.each());
            pools.add(reading.rotates() ? pool.rotating() : pool);
            targets.put(parameter, new Target(filled.owner(), filled.method(), filled.descriptor(),
                    reading.group() + "#" + reading.method()));
        }
        return new Read(pools, targets, warnings);
    }

    private static String unread(PoolReader.Reading reading, String why) {
        return reading.method() + "() could not be read (" + why + "); make sure the runs differ";
    }

    private static <T> Dealer.Source plugin(String coordinate, String method, PluginPool<T> pool, ValueGrammar grammar,
                                            StudioServices services, Map<String, Map<String, Object>> typed) {
        ManagedParam<T> parameter = pool.parameter();
        Map<String, T> values = new LinkedHashMap<>();
        List<T> candidates;
        try {
            candidates = pool.candidates(services);
        } catch (RuntimeException | LinkageError e) {
            throw new Dealer.Failed(coordinate, "failed while giving the candidates of " + method, e);
        }
        for (T candidate : candidates) {
            JavaValue written = grammar.spell(parameter.form(), candidate).orElseThrow(() -> new Dealer.Failed(
                    coordinate, "gave a candidate of " + method + " the host cannot write", null));
            String element;
            try {
                element = Canonical.of(written);
            } catch (Canonical.Unwritten e) {
                throw new Dealer.Failed(coordinate, "gave a candidate of " + method + " no run can be given", e);
            }
            values.putIfAbsent(element, candidate);
        }
        typed.put(parameter.id(), Map.copyOf(values));
        Dealer.Narrowing narrowing = (free, picks) -> {
            List<T> given = free.stream().map(values::get).toList();
            List<T> left = pool.left(given, picksOf(picks, typed), services);
            return free.stream().filter(element -> left.contains(values.get(element))).toList();
        };
        return Dealer.Source.plugin(coordinate, parameter.id(), method, List.copyOf(values.keySet()))
                .narrowedBy(narrowing);
    }

    /** The row's picks as a plugin reads them: its own parameters' and other plugins', each its typed value. */
    private static PluginPool.Picks picksOf(Map<String, List<String>> picks, Map<String, Map<String, Object>> typed) {
        return new PluginPool.Picks() {
            @Override
            public <V> Optional<V> of(ManagedParam<V> parameter) {
                List<String> pick = parameter == null ? null : picks.get(parameter.id());
                if (pick == null || pick.isEmpty()) return Optional.empty();
                Object value = typed.getOrDefault(parameter.id(), Map.of()).get(pick.getFirst());
                return Optional.ofNullable(parameter.cast(value));
            }
        };
    }
}
