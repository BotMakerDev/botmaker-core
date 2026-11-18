// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.assist;

import com.botmaker.core.plugin.PluginHost;
import com.botmaker.core.plugin.ReleasedPlugins;
import com.botmaker.core.run.RunEntries;
import com.botmaker.plugin.api.assist.AssistantTool;
import com.botmaker.plugin.api.run.RunEntry;
import com.botmaker.plugin.host.LoadedPlugin;
import com.botmaker.plugin.host.PluginCoordinate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code operations}: every bound plugin's operations in one answer (web discovery plan, phase 5) — its assistant
 * tools and its declared runs, each with its parameters as a JSON schema — for a caller that is no model, the shell's
 * {@code botmaker tool}. The answer is JSON, read by the cli's {@code Operations}: a change to its keys changes both.
 *
 * <p>A tool is called by the name the MCP serves it under ({@code call}); a run is started by {@code run_bot}, its
 * {@code entry} the run's title and its {@code args} the JSON its schema takes. The plugins are the bound ones: the
 * open project's, or with none open the catalog's the core binds by itself, whose runs then need a bot opened first.
 */
public final class Operations {

    /** The tool's name. */
    public static final String TOOL = "operations";
    /** {@code kind} of an operation that is an assistant tool. */
    static final String KIND_TOOL = "tool";
    /** {@code kind} of an operation that is a declared run. */
    static final String KIND_RUN = "run";

    private static final ObjectMapper JSON = new ObjectMapper();

    private Operations() {}

    /** One bound plugin and what it offers: its tools and its runs. */
    record Listed(PluginCoordinate plugin, String name, String version, List<AssistantTool<?>> tools,
                  List<RunEntry> runs) {
    }

    /** {@code operations}, answered from what is bound now, {@code project} the open one. */
    static AssistantTool<?> tool(BotRuns runs) {
        return McpTools.said(TOOL, "List every plugin's operations, for a program rather than a model: a JSON "
                + "object with the open project's folder (null for none) and each plugin with its assistant tools "
                + "(called by their MCP name, call) and its runs (started by run_bot entry, args), each with its "
                + "parameters' JSON schema.", () -> answer(runs.projectDir(), bound()));
    }

    /** What is bound now, plugin by plugin; a plugin with nothing to offer is left out. */
    static List<Listed> bound() {
        List<PluginHost.OwnedTool> tools = PluginHost.assistantTools();
        List<RunEntries.Declared> runs = RunEntries.all();
        List<Listed> out = new ArrayList<>();
        for (LoadedPlugin plugin : PluginHost.plugins()) {
            List<AssistantTool<?>> its = tools.stream().filter(t -> t.plugin().equals(plugin.coordinate()))
                    .<AssistantTool<?>>map(PluginHost.OwnedTool::tool).toList();
            List<RunEntry> itsRuns = runs.stream().filter(r -> r.plugin().equals(plugin.coordinate()))
                    .map(RunEntries.Declared::entry).toList();
            if (its.isEmpty() && itsRuns.isEmpty()) continue;
            out.add(new Listed(plugin.coordinate(), plugin.name(), ReleasedPlugins.versionOf(plugin.jar()), its,
                    itsRuns));
        }
        return out;
    }

    /**
     * The answer: {@code {"project": …, "plugins": [{"groupId", "artifactId", "name", "version", "operations":
     * [{"kind", "name", "call", "description", "schema"}]}]}}. A tool whose MCP name an earlier plugin's took is left
     * out, as the MCP leaves it out.
     */
    static String answer(Optional<Path> project, List<Listed> plugins) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", project.map(Path::toString).orElse(null));
        List<Map<String, Object>> listed = new ArrayList<>();
        Set<String> served = new HashSet<>();
        for (Listed plugin : plugins) {
            List<Map<String, Object>> operations = new ArrayList<>();
            for (AssistantTool<?> tool : plugin.tools()) {
                String call = PluginTools.mcpName(plugin.plugin(), tool.name());
                if (!served.add(call)) continue;
                operations.add(operation(KIND_TOOL, tool.name(), call, tool.description(),
                        McpTools.schemaObject(tool.schema())));
            }
            for (RunEntry run : plugin.runs()) {
                operations.add(operation(KIND_RUN, run.title(), null, "", McpTools.schemaObject(run.schema())));
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("groupId", plugin.plugin().groupId());
            one.put("artifactId", plugin.plugin().artifactId());
            one.put("name", plugin.name());
            one.put("version", plugin.version());
            one.put("operations", operations);
            listed.add(one);
        }
        out.put("plugins", listed);
        try {
            return JSON.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> operation(String kind, String name, String call, String description,
                                                 Map<String, Object> schema) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("kind", kind);
        one.put("name", name);
        if (call != null) one.put("call", call);
        one.put("description", description);
        one.put("schema", schema);
        return one;
    }
}
