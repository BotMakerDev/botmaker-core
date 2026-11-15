package com.botmaker.core.run.claims;

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
import java.util.Optional;
import java.util.function.Function;

/**
 * The pools a dealing reads (spec {@code 2026-11-15-run-claims-design.md} §2, §4): the plugins' first, each asked
 * for this PC's candidates now, then the bot's own as the grammar read them. Every element is what the grammar
 * writes, printed one way, so two that read the same are one.
 */
public final class PoolSources {

    private PoolSources() {}

    /**
     * The pools to deal from.
     *
     * @param pools    in the order a row is dealt
     * @param warnings what several runs should be told: a pool that could not be read is dealt by nothing
     */
    public record Read(List<Dealer.Source> pools, List<String> warnings) {

        public Read {
            pools = List.copyOf(pools);
            warnings = List.copyOf(warnings);
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
     * @param bound    the name of the bot's method a plugin's parameter id is bound to; empty for a parameter the
     *                 bot has no method for (a conditional one whose condition does not hold), whose pool is left
     *                 out of the row
     * @param bot      who the bot's own elements are held under
     * @param readings the bot's {@code @Pools} groups, read
     * @throws Dealer.Failed when a plugin throws, or gives a candidate the grammar cannot write
     */
    public static Read of(List<Declared> plugins, Function<String, Optional<String>> bound, ValueGrammar grammar,
                          StudioServices services, String bot, List<PoolReader.Reading> readings) {
        List<Dealer.Source> pools = new ArrayList<>();
        // By parameter id, each element back to the plugin's own value: what a narrowing is handed.
        Map<String, Map<String, Object>> typed = new HashMap<>();
        for (Declared plugin : plugins) {
            for (PluginPool<?> pool : plugin.pools()) {
                Optional<String> method = bound.apply(pool.parameter().id());
                if (method.isEmpty()) continue;
                pools.add(plugin(plugin.coordinate(), method.get(), pool, grammar, services, typed));
            }
        }
        Read own = ofBot(bot, readings);
        pools.addAll(own.pools());
        return new Read(pools, own.warnings());
    }

    /** The bot's own pools, in its groups' order; one that does not read is a warning and no pool. */
    public static Read ofBot(String bot, List<PoolReader.Reading> readings) {
        List<Dealer.Source> pools = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (PoolReader.Reading reading : readings) {
            if (!reading.reads() || reading.fills().isEmpty()) {
                warnings.add(reading.method() + "() could not be read (" + reading.problem()
                        + "); make sure the runs differ");
                continue;
            }
            PoolReader.Filled filled = reading.fills().get();
            Dealer.Source pool = Dealer.Source.bot(bot, filled.owner() + "#" + filled.method(), reading.method(),
                    reading.elements().stream().map(PoolSources::written).toList());
            if (reading.take() > 0) pool = pool.taking(reading.take());
            if (reading.each() > 1) pool = pool.sharedBy(reading.each());
            pools.add(reading.rotates() ? pool.rotating() : pool);
        }
        return new Read(pools, warnings);
    }

    /** An element as every dealing compares it: its tree printed, however its author spaced it. */
    public static String written(JavaValue value) {
        return value.node().toString();
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
            values.putIfAbsent(written(written), candidate);
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
