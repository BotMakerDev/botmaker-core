// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.run.claims;

import com.botmaker.core.plugin.PluginHost;
import com.botmaker.core.run.claims.RunRegistry.Hold;
import com.botmaker.core.run.claims.RunRegistry.LiveRun;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.grammar.ValueGrammar;
import com.botmaker.plugin.api.run.LiveRuns;
import com.botmaker.plugin.api.run.PluginPool;
import com.botmaker.plugin.api.source.ManagedParam;
import com.botmaker.plugin.host.LoadedPlugin;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The host's {@link LiveRuns} (spec {@code 2026-11-15-run-claims-design.md} §4): setup work holds a pooled value in
 * the registry of live runs as a run of this process would, under the plugin whose pool fills the parameter and as
 * {@link PoolSources} writes the element, so a dealing finds it held. The hold is freed when it is closed, or when
 * this process ends.
 */
public final class RegistryHolds implements LiveRuns {

    private final RunRegistry registry;
    private final Supplier<ValueGrammar> grammar;
    private final Function<String, Optional<String>> owner;

    /**
     * @param owner by a parameter's managed id, the coordinate of the plugin whose pool fills it; empty when none does,
     *              and the hold then holds nothing
     */
    public RegistryHolds(RunRegistry registry, Supplier<ValueGrammar> grammar,
                         Function<String, Optional<String>> owner) {
        this.registry = registry;
        this.grammar = grammar;
        this.owner = owner;
    }

    /** Over this PC's registry and the bound plugins' pools and grammar. */
    public static RegistryHolds ofThisPc() {
        return new RegistryHolds(RunRegistry.ofThisPc(), PluginHost::grammar, RegistryHolds::pooledBy);
    }

    @Override
    public <T> Held hold(ManagedParam<T> parameter, T value, String by) throws Taken {
        // No bound plugin's pool fills it (no bot is open here): this process deals it nothing, and holds nothing.
        Optional<String> pooled = owner.apply(parameter.id());
        if (pooled.isEmpty()) return () -> {};
        String coordinate = pooled.get();
        JavaValue written = grammar.get().spell(parameter.form(), value).orElseThrow(() ->
                new IllegalArgumentException("The host cannot write " + value + " for " + parameter.id()));
        String element;
        try {
            element = Canonical.of(written);
        } catch (Canonical.Unwritten e) {
            throw new IllegalArgumentException("No run can be given " + value + " for " + parameter.id(), e);
        }
        String runId = RunRegistry.newRunId();
        String who = by == null || by.isBlank() ? "setup work" : by.strip();
        Optional<RunRegistry.Conflict> conflict = registry.hold(
                LiveRun.ofThisProcess(runId, who, List.of(Hold.plugin(coordinate, parameter.id(), element))));
        if (conflict.isPresent()) throw new Taken(conflict.get().words());
        AtomicBoolean open = new AtomicBoolean(true);
        return () -> {
            if (open.getAndSet(false)) registry.release(runId);
        };
    }

    /** The bound plugin whose pool fills the parameter {@code id}, the first in the host's order. */
    private static Optional<String> pooledBy(String id) {
        for (LoadedPlugin plugin : PluginHost.plugins()) {
            List<PluginPool<?>> pools = plugin.plugin().pools();
            if (pools == null) continue;
            for (PluginPool<?> pool : pools) {
                if (pool.parameter().id().equals(id)) return Optional.of(plugin.coordinate().toString());
            }
        }
        return Optional.empty();
    }
}
