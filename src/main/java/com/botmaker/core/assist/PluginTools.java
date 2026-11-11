package com.botmaker.core.assist;

import com.botmaker.core.plugin.HomeServices;
import com.botmaker.core.plugin.PluginHost;
import com.botmaker.core.plugin.ToolCalls;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.assist.AgentContext;
import com.botmaker.plugin.api.assist.AgentReply;
import com.botmaker.plugin.api.overlay.Area;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The bound plugins' assistant tools, as the MCP names them, and served by the core itself while no Studio is attached
 * (2026-11-10, the user's call): a plugin's tool then runs with no screen, and one that needs Studio's (JavaFX, the
 * overlay) says so instead of failing.
 */
public final class PluginTools {

    /** The key of a plugin tool's {@code _meta} that names its plugin; it crosses a client's registration whole. */
    static final String PLUGIN_META = "botmaker/plugin";

    private PluginTools() {}

    /** A plugin tool's MCP description: its own, then the plugin it comes from. */
    public static String described(String description, String pluginName) {
        return description + " (from " + pluginName + ")";
    }

    /** The {@code _meta} of a tool {@code pluginName} serves, which {@link #pluginOf} reads back. */
    public static Map<String, Object> meta(String pluginName) {
        return Map.of(PLUGIN_META, pluginName);
    }

    /** The plugin that serves {@code tool}, by its {@link #meta}; empty for the core's own and a client's own. */
    public static String pluginOf(McpSchema.Tool tool) {
        return tool.meta() != null && tool.meta().get(PLUGIN_META) instanceof String plugin ? plugin : "";
    }

    /** Every bound plugin's tool as {@code <artifactId>_<name>}; two plugins with one artifactId keep the first's. */
    public static List<StudioDriver.PluginTool> named() {
        return named(true);
    }

    /** {@link #named()}, saying a name two plugins share only when {@code warn}: a call's lookup says nothing. */
    private static List<StudioDriver.PluginTool> named(boolean warn) {
        List<StudioDriver.PluginTool> out = new ArrayList<>();
        Set<String> served = new LinkedHashSet<>();
        for (PluginHost.OwnedTool owned : PluginHost.assistantTools()) {
            String name = owned.plugin().artifactId().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_")
                    + "_" + owned.tool().name();
            if (!served.add(name)) {
                if (warn) {
                    System.err.println("Warning: " + owned.plugin() + "'s assistant tool " + name + " is already "
                            + "served by another plugin.");
                }
                continue;
            }
            out.add(new StudioDriver.PluginTool(name, owned.pluginName(), owned.tool()));
        }
        return out;
    }

    /** The bound plugins' tools served by the core with no screen, each call given {@code services}' context. */
    public static List<SyncToolSpecification> headless(McpJsonMapper json, Supplier<StudioServices> services) {
        List<SyncToolSpecification> out = new ArrayList<>();
        for (StudioDriver.PluginTool owned : named()) {
            String description = described(owned.tool().description(), owned.pluginName());
            out.add(McpTools.spec(json, owned.name(), description, McpTools.schemaOf(owned.tool().schema()),
                    meta(owned.pluginName()), request -> {
                // The tool bound now: a rebind closes the loader the served one came from.
                Optional<StudioDriver.PluginTool> live = named(false).stream()
                        .filter(t -> t.name().equals(owned.name())).findFirst();
                if (live.isEmpty()) {
                    return McpTools.result(owned.name() + " is no longer served: its plugin was removed. List the "
                            + "tools again.", true);
                }
                AgentReply reply;
                try {
                    AgentContext context = screenless(services.get());
                    reply = ToolCalls.once(() -> live.get().tool().invoke(McpTools.arguments(request), context));
                } catch (RuntimeException | LinkageError e) {
                    Optional<String> needsBot = needsBot(e);
                    if (needsBot.isPresent()) return McpTools.result(owned.name() + ": " + needsBot.get(), true);
                    return McpTools.result(needsScreen(e) ? needsStudio(owned.name())
                            : owned.name() + " could not start: " + e.getMessage(), true);
                }
                return McpTools.reply(reply);
            }));
        }
        return out;
    }

    /** What a tool that reached for Studio's screen answers with none. */
    static String needsStudio(String tool) {
        return tool + " needs BotMaker Studio open, for its screen: start_studio, then call it again.";
    }

    /** What a home plugin's tool met when it reached for a bot none is open for ({@link HomeServices.NeedsBot}). */
    static Optional<String> needsBot(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HomeServices.NeedsBot needs) return Optional.of(needs.getMessage());
        }
        return Optional.empty();
    }

    /** Whether {@code failure} came from JavaFX or the overlay, which only Studio's process has running. */
    static boolean needsScreen(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = String.valueOf(cause.getMessage());
            if (message.contains("Toolkit not initialized")) return true;
            // A JavaFX class that would not load: its internal name, javafx/scene/Node.
            if (cause instanceof LinkageError && message.startsWith("javafx/")) return true;
            for (StackTraceElement frame : cause.getStackTrace()) {
                if (frame.getClassName().startsWith("javafx.") || frame.getClassName().startsWith("com.sun.javafx.")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A context with no screen: no frame, no watched area, no marks. */
    static AgentContext screenless(StudioServices services) {
        return new AgentContext() {
            @Override
            public StudioServices services() {
                return services;
            }

            @Override
            public Optional<BufferedImage> frame() {
                return Optional.empty();
            }

            @Override
            public Optional<Area> watchedArea() {
                return Optional.empty();
            }
        };
    }
}
