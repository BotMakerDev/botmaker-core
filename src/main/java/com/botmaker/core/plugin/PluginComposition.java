// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.plugin;

import com.botmaker.java.grammar.JavaNames;
import com.botmaker.java.grammar.ValueGrammar;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.plugin.api.value.PluginType;
import com.botmaker.plugin.host.FilledTypes;
import com.botmaker.plugin.host.LoadedPlugin;
import com.botmaker.plugin.host.PluginLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a set of plugins composes to: the grammar over what they declare, the plugins that are in it, and
 * a failure per plugin that was left out.
 *
 * @param grammar  what {@link PluginHost#grammar()} serves
 * @param bound    {@code set} minus whatever could not compose, in the same order
 * @param rejected one entry per plugin left out, in the shape {@link PluginHost#failures()} already reports
 */
record PluginComposition(ValueGrammar grammar, List<LoadedPlugin> bound,
                         List<PluginLoader.PluginFailure> rejected) {

    /**
     * Folds the plugins' declarations into one grammar, <b>dropping a plugin that will not compose rather
     * than the whole set</b> (2026-09-19).
     *
     * <p>Two plugins declaring one type is genuinely unanswerable — the class <em>is</em> the identity, and
     * which plugin's {@code fresh()} and editor a project gets cannot depend on load order — so one of them
     * has to go. Which one is classpath order, which is Maven's own answer and the only one here that is not
     * a guess. Offering an editor for another plugin's type is not declaring it: that is
     * {@link StudioPlugin#slotEditors()}, and the user picks between the two.
     *
     * <p>Total, like every other pass over plugin code here: a plugin whose {@code types()} or
     * {@code componentTypes()} throws is a rejection and not a failed bind.
     */
    static PluginComposition of(List<LoadedPlugin> set) {
        List<PluginType<?>> types = new ArrayList<>();
        List<ComponentType<?>> components = new ArrayList<>();
        List<LoadedPlugin> kept = new ArrayList<>();
        List<PluginLoader.PluginFailure> rejected = new ArrayList<>();
        Map<String, String> claimedBy = new HashMap<>();
        for (LoadedPlugin loaded : set) {
            StudioPlugin plugin = loaded.plugin();
            List<PluginType<?>> offered;
            List<ComponentType<?>> parts;
            List<String> names = new ArrayList<>();
            try {
                offered = PluginDeclarations.typesOf(plugin);
                for (String problem : FilledTypes.of(plugin).problems()) {
                    System.err.println("Warning: plugin " + loaded.coordinate() + ": " + problem);
                }
                parts = plugin.componentTypes();
                if (offered != null) {
                    for (PluginType<?> type : offered) if (type != null) names.add(JavaNames.canonical(type.type()));
                }
            } catch (RuntimeException | LinkageError e) {
                rejected.add(new PluginLoader.PluginFailure(loaded.name(), e));
                continue;
            }
            List<String> clashes = names.stream().filter(claimedBy::containsKey).distinct().toList();
            if (!clashes.isEmpty()) {
                rejected.add(new PluginLoader.PluginFailure(loaded.name(),
                        new IllegalStateException(clashMessage(clashes, claimedBy))));
                continue;
            }
            for (String name : names) claimedBy.putIfAbsent(name, loaded.name());
            if (offered != null) offered.stream().filter(t -> t != null).forEach(types::add);
            if (parts != null) parts.stream().filter(c -> c != null).forEach(components::add);
            kept.add(loaded);
        }
        return new PluginComposition(ValueGrammar.of(types, components), List.copyOf(kept), List.copyOf(rejected));
    }

    /** Why a plugin was left out, naming the types and whoever already declared them. */
    private static String clashMessage(List<String> clashes, Map<String, String> claimedBy) {
        String owner = claimedBy.getOrDefault(clashes.getFirst(), "another plugin");
        return "it was left out: it declares " + String.join(", ", clashes)
                + ", which " + owner + " already declares (a method marked @Fills declares its return type too)."
                + " Two plugins cannot both own a type — install one of them, or move to a version of one that"
                + " no longer declares it.";
    }
}
