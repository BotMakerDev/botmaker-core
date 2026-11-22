// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.plugin;

import com.botmaker.core.plugin.PluginHost.OwnedGuide;
import com.botmaker.core.plugin.PluginHost.OwnedTool;
import com.botmaker.plugin.api.assist.AssistantTool;
import com.botmaker.plugin.host.LoadedPlugin;

import java.util.ArrayList;
import java.util.List;

/** What one set of plugins gives the assistant, its tools and its guides. Asked once, for the set's whole binding. */
final class PluginAssistant {

    private final List<OwnedTool> tools;
    private final List<OwnedGuide> guides;

    PluginAssistant(List<LoadedPlugin> set) {
        tools = mergeAssistantTools(set);
        guides = mergeAssistantGuides(set);
    }

    /** {@link PluginHost#assistantTools}. */
    List<OwnedTool> tools() {
        return tools;
    }

    /** {@link PluginHost#assistantGuides}. */
    List<OwnedGuide> guides() {
        return guides;
    }

    static List<OwnedGuide> mergeAssistantGuides(List<LoadedPlugin> set) {
        List<OwnedGuide> merged = new ArrayList<>();
        for (LoadedPlugin plugin : set) {
            try {
                String text = plugin.plugin().assistantGuide();
                if (text != null && !text.isBlank()) merged.add(new OwnedGuide(plugin.name(), text.strip()));
            } catch (RuntimeException | LinkageError e) {
                // Includes a plugin built against a contract without this method: it simply says nothing.
                System.err.println("Warning: " + plugin.coordinate() + " could not give its assistant guide: " + e);
            }
        }
        return List.copyOf(merged);
    }

    static List<OwnedTool> mergeAssistantTools(List<LoadedPlugin> set) {
        List<OwnedTool> merged = new ArrayList<>();
        for (LoadedPlugin plugin : set) {
            try {
                List<AssistantTool<?>> offered = plugin.plugin().assistantTools();
                if (offered == null) continue;
                for (var tool : offered) {
                    if (tool != null) merged.add(new OwnedTool(plugin.coordinate(), plugin.name(), tool));
                }
            } catch (RuntimeException | LinkageError e) {
                // Includes a plugin built against a contract without this method: it simply offers none.
                System.err.println("Warning: " + plugin.coordinate() + " could not list assistant tools: " + e);
            }
        }
        return List.copyOf(merged);
    }
}
