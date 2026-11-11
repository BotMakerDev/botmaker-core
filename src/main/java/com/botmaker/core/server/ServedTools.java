package com.botmaker.core.server;

import com.botmaker.core.CoreJson;
import com.botmaker.core.assist.LiveBot;
import com.botmaker.core.assist.McpTools;
import com.botmaker.core.event.CoreEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The tools the core's MCP serves (plan phase 10d): its own, over the open project's files while a project is open,
 * each attached client's, registered over its channel ({@link Request.Tools}) and forwarded to it call by call
 * ({@link Reply.Call}), and last its home and run tools, which a client's of the same name shadow (phase 11). Studio's screen, runs and plugin windows are Studio's tools, so they come and go with Studio; the
 * bot's Java is the core's.
 *
 * <p>A call of the core's own tool is first admitted by the client with a screen ({@link Request.Tools#admits}): a
 * question on screen or a plugin move in Studio holds it, as Studio's own tools are held there.
 */
public final class ServedTools {

    /** How long a forwarded call may take: a {@code ui} press waits on a window, a run on the bot. */
    static final Duration CALL_TIMEOUT = Duration.ofMinutes(15);
    /** How long a client may take to admit a call of the core's own tool. */
    static final Duration ADMIT_TIMEOUT = Duration.ofSeconds(30);

    private final McpJsonMapper json = new JacksonMcpJsonMapper(new ObjectMapper());
    private final AtomicLong calls = new AtomicLong();
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    /** Each client's tools by name, in the order registered; guarded by this. */
    private final Map<Channel, Map<String, SyncToolSpecification>> theirs = new LinkedHashMap<>();
    /** The client the core's own tools ask first, the last one that said it admits. */
    private volatile Channel admitting;
    private List<SyncToolSpecification> own = List.of();
    /** The core's home and run tools, served after every client's, so a client's of the same name wins. */
    private List<SyncToolSpecification> fallback = List.of();
    /** The bound plugins' tools with no screen, served like {@link #fallback}, before it. */
    private List<SyncToolSpecification> plugins = List.of();
    /** The core's tools no client stands in for that act ({@link #held}), served with or without a client. */
    private List<SyncToolSpecification> held = List.of();
    /** The core's tools about itself, served with or without a client, after every other. */
    private List<SyncToolSpecification> lasting = List.of();
    /** Every tool served now by name, so a change removes and adds exactly what changed. */
    private final Map<String, SyncToolSpecification> served = new LinkedHashMap<>();
    /** Every call of a served tool, told to the clients ({@link #watch}). */
    private final ToolCallLog log = new ToolCallLog(json, Clock.systemUTC());
    private McpSyncServer mcp;

    /** A call sent to a client, answered by its {@link Request.Called}. */
    private record Pending(Channel channel, CompletableFuture<String> answer) {
    }

    /**
     * {@code told} hears the calls kept so far, then every call of a served tool as it begins and ends, until the
     * returned stop: a client's channel, with or without a project open.
     */
    Runnable watch(Consumer<CoreEvent> told) {
        return log.watch(told);
    }

    /** Serves on {@code server} from now on what was registered before. */
    synchronized void serveOn(McpSyncServer server) {
        this.mcp = server;
        refresh();
    }

    /** The core's own tools over {@code bot}, the open project's; none when {@code bot} is null (no project open). */
    synchronized void own(LiveBot bot) {
        own = bot == null ? List.of() : McpTools.own(json, bot).stream().map(this::admitted).toList();
        refresh();
    }

    /** The core's own home and run tools ({@code CoreHome}), served after every client's. */
    synchronized void fallback(List<SyncToolSpecification> tools) {
        fallback = List.copyOf(tools);
        refresh();
    }

    /** The core's tools about itself ({@code core_status}, {@code quit_core}), served whoever is attached. */
    synchronized void lasting(List<SyncToolSpecification> tools) {
        lasting = List.copyOf(tools);
        refresh();
    }

    /**
     * The core's tools no client serves that act on something ({@code device}): served whoever is attached, before
     * {@link #lasting}, and admitted by the client with a screen as the core's own are.
     */
    synchronized void held(List<SyncToolSpecification> tools) {
        held = tools.stream().map(this::admitted).toList();
        refresh();
    }

    /**
     * The bound plugins' tools the core serves with no screen ({@code PluginTools.headless}), while no client serves
     * its own: Studio's copy of each, with its screen, wins once it attaches (2026-11-10).
     */
    synchronized void plugins(List<SyncToolSpecification> tools) {
        plugins = List.copyOf(tools);
        refresh();
    }

    /** {@code channel}'s tools from now on; the ones it registered before go. */
    void register(Channel channel, Request.Tools tools) {
        List<McpSchema.Tool> read = new ArrayList<>();
        for (String text : tools.tools()) {
            try {
                read.add(json.readValue(text, McpSchema.Tool.class));
            } catch (IOException e) {
                throw new IllegalArgumentException("A tool the client registered could not be read: " + e.getMessage());
            }
        }
        synchronized (this) {
            if (!channel.isOpen()) return;
            boolean first = !theirs.containsKey(channel);
            Map<String, SyncToolSpecification> before = theirs.getOrDefault(channel, Map.of());
            Map<String, SyncToolSpecification> specs = new LinkedHashMap<>();
            for (McpSchema.Tool tool : read) {
                // The same tool keeps its spec, so refresh() leaves it served.
                SyncToolSpecification kept = before.get(tool.name());
                specs.put(tool.name(), kept != null && kept.tool().equals(tool) ? kept
                        : SyncToolSpecification.builder().tool(tool)
                        .callHandler((exchange, request) -> forward(channel, Reply.Call.Kind.RUN, request)).build());
            }
            theirs.put(channel, specs);
            if (tools.admits()) admitting = channel;
            else if (admitting == channel) admitting = null;
            refresh();
            if (first) channel.onClose(() -> gone(channel));
        }
    }

    /** {@code channel}'s answer to the call {@code called.call()}; an answer from another channel is not taken. */
    void called(Channel channel, Request.Called called) {
        Pending asked = pending.get(called.call());
        if (asked != null && asked.channel() == channel) asked.answer().complete(called.result());
    }

    /** A channel that ended: its tools go, and the calls it was asked fail. */
    private void gone(Channel channel) {
        synchronized (this) {
            theirs.remove(channel);
            if (admitting == channel) admitting = null;
            refresh();
        }
        pending.values().stream().filter(p -> p.channel() == channel)
                .forEach(p -> p.answer().completeExceptionally(new IOException("the client went away")));
    }

    /** The core's own {@code tool}, asking the client with a screen first, and adding what it says after the answer. */
    private SyncToolSpecification admitted(SyncToolSpecification tool) {
        return SyncToolSpecification.builder().tool(tool.tool()).callHandler((exchange, request) -> {
            Channel screen = admitting;
            if (screen == null || !screen.isOpen()) return tool.callHandler().apply(exchange, request);
            CallToolResult admitted = ask(screen, Reply.Call.Kind.ADMIT, request, ADMIT_TIMEOUT);
            if (Boolean.TRUE.equals(admitted.isError())) return admitted;
            CallToolResult answer = tool.callHandler().apply(exchange, request);
            if (admitted.content() == null || admitted.content().isEmpty()) return answer;
            List<McpSchema.Content> content = new ArrayList<>(answer.content() == null ? List.of() : answer.content());
            content.addAll(admitted.content());
            return CallToolResult.builder().content(content).isError(answer.isError()).meta(answer.meta()).build();
        }).build();
    }

    private CallToolResult forward(Channel channel, Reply.Call.Kind kind, CallToolRequest request) {
        return ask(channel, kind, request, CALL_TIMEOUT);
    }

    /** {@code request} sent to {@code channel} and its answer, or an error result saying why there is none. */
    private CallToolResult ask(Channel channel, Reply.Call.Kind kind, CallToolRequest request, Duration timeout) {
        long call = calls.incrementAndGet();
        CompletableFuture<String> answer = new CompletableFuture<>();
        pending.put(call, new Pending(channel, answer));
        try {
            String arguments = json.writeValueAsString(request.arguments() == null ? Map.of() : request.arguments());
            if (!channel.send(CoreJson.write(new Reply.Call(call, kind, request.name(), arguments)))) {
                return failed("BotMaker Studio went away; call again once it is back.");
            }
            return json.readValue(answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS), CallToolResult.class);
        } catch (ExecutionException e) {
            return failed("BotMaker Studio went away during that call; call again once it is back.");
        } catch (TimeoutException e) {
            return failed("BotMaker Studio did not answer that call in " + timeout.toSeconds() + " s.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed("The call was interrupted.");
        } catch (IOException e) {
            return failed("The call's answer could not be read: " + e.getMessage());
        } finally {
            pending.remove(call);
        }
    }

    private static CallToolResult failed(String why) {
        return CallToolResult.builder().content(List.of(new McpSchema.TextContent("FAILED: " + why))).isError(true)
                .build();
    }

    /**
     * What is served made what is registered now; a name taken by an earlier tool keeps the first. The core's own are
     * served with no client registered (plan phase 11, the MCP without Studio) or while a client with a screen admits
     * them: a Studio that stopped serving stops them too. Only what changed is removed and added, so a call of a tool
     * that stays never finds it gone. Under this.
     */
    private void refresh() {
        if (mcp == null) return;
        Map<String, SyncToolSpecification> now = chosen();
        try {
            for (Map.Entry<String, SyncToolSpecification> was : List.copyOf(served.entrySet())) {
                if (now.get(was.getKey()) == was.getValue()) continue;
                mcp.removeTool(was.getKey());
                served.remove(was.getKey());
            }
            for (SyncToolSpecification tool : now.values()) {
                if (served.containsKey(tool.tool().name())) continue;
                mcp.addTool(log.logged(tool));
                served.put(tool.tool().name(), tool);
            }
            mcp.notifyToolsListChanged();
        } catch (RuntimeException e) {
            // A client that is not told lists the tools again on its next start.
            System.err.println("Warning: the core's tools could not be served anew: " + e.getMessage());
        }
    }

    /** What {@link #refresh} serves, by name, in the order a name is first taken. */
    synchronized Map<String, SyncToolSpecification> chosen() {
        Map<String, SyncToolSpecification> now = new LinkedHashMap<>();
        boolean ours = admitting != null || theirs.isEmpty();
        if (ours) own.forEach(tool -> now.putIfAbsent(tool.tool().name(), tool));
        theirs.values().forEach(specs -> specs.forEach(now::putIfAbsent));
        // The core's home and run tools stand in for a client's only while none is registered.
        if (theirs.isEmpty()) {
            plugins.forEach(tool -> now.putIfAbsent(tool.tool().name(), tool));
            fallback.forEach(tool -> now.putIfAbsent(tool.tool().name(), tool));
        }
        held.forEach(tool -> now.putIfAbsent(tool.tool().name(), tool));
        lasting.forEach(tool -> now.putIfAbsent(tool.tool().name(), tool));
        return now;
    }
}
