// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.plugin;

import com.botmaker.core.plugin.PluginHost.OwnedType;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.api.value.PluginType;
import com.botmaker.plugin.host.FilledTypes;
import com.botmaker.plugin.host.LoadedPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The types and the managed values plugins declare, read off them. */
final class PluginDeclarations {

    private PluginDeclarations() {}

    /**
     * What {@code plugin} declares: its own {@code types()}, then one {@link FilledType} per {@code @Fills}
     * method in its jar whose class it does not declare itself. Throws what {@code types()} throws; a refused
     * {@code @Fills} method declares nothing ({@link PluginComposition#of} warns of it, once per bind).
     */
    static List<PluginType<?>> typesOf(StudioPlugin plugin) {
        List<PluginType<?>> own = plugin.types();
        FilledTypes.Found found = FilledTypes.of(plugin);
        if (found.filled().isEmpty()) return own;
        List<PluginType<?>> types = new ArrayList<>(own == null ? List.of() : own);
        Set<String> declared = new HashSet<>();
        for (PluginType<?> type : types) if (type != null) declared.add(type.type().getName());
        for (FilledTypes.Filled filled : found.filled()) {
            if (declared.add(filled.type().getName())) types.add(FilledType.of(filled));
        }
        return types;
    }

    /** {@link PluginHost#ownedTypes} over {@code set}. */
    static List<OwnedType> ownedTypes(List<LoadedPlugin> set) {
        List<OwnedType> out = new ArrayList<>();
        for (LoadedPlugin loaded : set) {
            List<PluginType<?>> types = PluginCalls.quietly(loaded, "declare types", () -> typesOf(loaded.plugin()));
            if (types == null) continue;
            for (PluginType<?> type : types) {
                Class<?> cls = type == null ? null : PluginCalls.quietly(loaded, "name a type", type::type);
                if (cls != null) out.add(new OwnedType(loaded.coordinate(), loaded.name(), cls));
            }
        }
        return List.copyOf(out);
    }

    /** Every plugin's entries, a throwing or malformed one costing only itself. */
    static List<ManagedValue<?>> mergeManagedValues(List<LoadedPlugin> set) {
        List<ManagedValue<?>> merged = new ArrayList<>();
        for (LoadedPlugin plugin : set) {
            try {
                List<ManagedValue<?>> offered = plugin.plugin().managedValues();
                if (offered == null) continue;
                for (ManagedValue<?> value : offered) {
                    if (value != null && value.id() != null && !value.id().isBlank()) {
                        merged.add(value);
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                // Includes a plugin built against a contract without this method: it simply manages nothing.
                System.err.println("Warning: " + plugin.coordinate() + " could not list managed values: " + e);
            }
        }
        return List.copyOf(merged);
    }

    // parameterGroups(String), parameterGroup(String, String), parameterRows(String) and
    // parameterEdited(ParameterEdit) stood in PluginHost from 2026-09-10 to 2026-09-22, with a GROUPS cache beside
    // CACHE. The contract surface under them is deleted and nothing replaces them here.
    //
    // They asked each plugin for the rows of a section that plugin had declared. Nothing ever declared one:
    // the SDK's group was the only implementation, it declared no rows, and basics' ParameterStore.declare
    // had no caller, so what came back was a pre-2026-09-17 project's JSON and nothing else. A parameter is
    // a @Param field in the bot's own Java, which project/params/JavaParameters reads and writes off the
    // syntax tree -- including one in a file a plugin ships, which BotSources.scan already walks.
    //
    // parameterDeclared went first, on 2026-09-17, offering a declared row to each plugin until one owned it.
}
