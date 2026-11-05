package com.botmaker.core.server;

import com.botmaker.core.assist.McpEndpoint;
import com.botmaker.core.assist.McpTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The core's one server: Jetty on {@code 127.0.0.1}, every path behind {@link McpEndpoint}'s token but its hello.
 * It serves the MCP at {@link McpEndpoint#PATH}, what it is at {@link #INFO}, a quit at {@link #QUIT}, and the
 * clients' channels at {@link #STUDIO} ({@link Channel}). It ends itself, through {@link Hooks#exit}, after
 * {@link Idle#after} with no channel, no request and nothing {@link Hooks#busy}, or on a quit while nothing uses it.
 */
public final class CoreServer implements AutoCloseable {

    public static final String INFO = "/core";
    public static final String QUIT = "/core/quit";
    public static final String STUDIO = "/studio";
    /** The version a client is; a channel opens only for a client of the core's own version. */
    public static final String VERSION_HEADER = "X-BotMaker-Version";

    /** How long the core waits with nothing to do before it ends, and how often it looks. */
    public record Idle(Duration after, Duration every) {
        /** Ten minutes (the spec's, Claude's call), looked at every 30 seconds, which also beats each channel. */
        public static final Idle DEFAULT = new Idle(Duration.ofMinutes(10), Duration.ofSeconds(30));
    }

    /**
     * What the server asks of its process.
     *
     * @param busy   true while something must keep the core up with no client (a run, from phase 10b)
     * @param opened hears each channel as it opens, before its first message; it sets the channel's listener
     * @param exit   ends the core; called once, off the server's threads
     */
    public record Hooks(Idle idle, BooleanSupplier busy, Consumer<Channel> opened, Runnable exit) {}

    /** What {@link #INFO} answers; {@code ending} once the core has begun to end, when it opens no channel. */
    public record Info(String version, long pid, int clients, boolean busy, boolean ending) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Server jetty;
    private final McpSyncServer mcp;
    private final String version;
    private final Hooks hooks;
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final AtomicLong lastActive = new AtomicLong(System.nanoTime());
    private final AtomicBoolean ending = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService ticks = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread tick = new Thread(r, "core-idle");
        tick.setDaemon(true);
        return tick;
    });
    private int port;

    private CoreServer(String token, String version, Hooks hooks) {
        this.version = version;
        this.hooks = hooks;
        HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(new ObjectMapper())).mcpEndpoint(McpEndpoint.PATH).build();
        this.mcp = McpServer.sync(transport)
                .serverInfo("botmaker-core", version)
                .instructions(McpTools.instructions(List.of()))
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(List.of())
                .build();
        this.jetty = new Server();
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(transport), McpEndpoint.PATH);
        context.addServlet(new ServletHolder(new InfoServlet()), INFO);
        context.addServlet(new ServletHolder(new QuitServlet()), QUIT);
        context.addServlet(new ServletHolder(new StudioServlet()), STUDIO);
        context.addServlet(new ServletHolder(new StudioServlet()), STUDIO + "/*");
        McpEndpoint.guard(context, token);
        // After the guard: a request refused for its token keeps no core up.
        context.addFilter(new FilterHolder(activity()), "/*", EnumSet.of(DispatcherType.REQUEST));
        jetty.setHandler(context);
    }

    /**
     * Starts serving on {@code port} ({@code 0} for any free one) behind {@code token}.
     *
     * @throws Exception when the port is taken or Jetty will not start; nothing is left running
     */
    public static CoreServer start(int port, String token, String version, Hooks hooks) throws Exception {
        Objects.requireNonNull(token, "token");
        CoreServer server = new CoreServer(token, version, hooks);
        server.listen(port);
        return server;
    }

    private void listen(int wanted) throws Exception {
        ServerConnector connector = new ServerConnector(jetty);
        connector.setHost("127.0.0.1");
        connector.setPort(wanted);
        // Well above the heartbeat: Jetty's own 30 s would cut a quiet channel before its next beat.
        connector.setIdleTimeout(hooks.idle().every().multipliedBy(4).toMillis());
        jetty.addConnector(connector);
        try {
            jetty.start();
        } catch (Exception e) {
            mcp.close();
            jetty.stop();
            throw e;
        }
        port = connector.getLocalPort();
        long every = hooks.idle().every().toMillis();
        ticks.scheduleAtFixedRate(this::tick, every, every, TimeUnit.MILLISECONDS);
    }

    public int port() {
        return port;
    }

    /** The MCP server, whose tools {@link ServedTools} keeps; a test serves its own on it. */
    public McpSyncServer mcp() {
        return mcp;
    }

    /** The clients whose channel is open now. */
    public int clients() {
        return channels.size();
    }

    public Info info() {
        return new Info(version, ProcessHandle.current().pid(), clients(), hooks.busy().getAsBoolean(), ending.get());
    }

    /** Stops serving; every channel's stream ends. Does not call {@link Hooks#exit}. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        ticks.shutdownNow();
        List.copyOf(channels.values()).forEach(Channel::close);
        try {
            mcp.close();
        } finally {
            try {
                jetty.stop();
            } catch (Exception ignored) {
                // Stopping is best-effort: the port is released with the process in any case.
            }
        }
    }

    private void tick() {
        List.copyOf(channels.values()).forEach(Channel::heartbeat);
        long quiet = System.nanoTime() - lastActive.get();
        if (idle() && quiet >= hooks.idle().after().toNanos()) end();
    }

    private boolean idle() {
        return channels.isEmpty() && !hooks.busy().getAsBoolean();
    }

    /** Calls {@link Hooks#exit} once, on a thread of its own, so a request that asked for it gets its answer. */
    private void end() {
        if (!ending.compareAndSet(false, true)) return;
        Thread exit = new Thread(hooks.exit(), "core-exit");
        exit.start();
    }

    private Filter activity() {
        return (request, response, chain) -> {
            lastActive.set(System.nanoTime());
            chain.doFilter(request, response);
        };
    }

    private final class InfoServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setContentType("application/json");
            response.getOutputStream().write(JSON.writeValueAsBytes(info()));
        }
    }

    /** Ends the core when nothing uses it; else 409, and it keeps running. */
    private final class QuitServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (!idle()) {
                response.sendError(HttpServletResponse.SC_CONFLICT, "The core is in use");
                return;
            }
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            response.flushBuffer();
            end();
        }
    }

    /**
     * {@code GET /studio} opens a channel: its first line is {@code {"channel":"<id>"}}, then the core's messages.
     * {@code POST /studio/<id>} is one message from the client, {@code DELETE /studio/<id>} closes it.
     */
    private final class StudioServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            String theirs = request.getHeader(VERSION_HEADER);
            if (ending.get()) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "The core is ending");
                return;
            }
            if (!version.equals(theirs)) {
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                response.setContentType("text/plain");
                response.getOutputStream().write(otherVersion(version, theirs).getBytes(StandardCharsets.UTF_8));
                return;
            }
            response.setContentType("application/x-ndjson");
            AsyncContext async = request.startAsync();
            async.setTimeout(0);
            Channel channel = new Channel(UUID.randomUUID().toString(), async, response.getOutputStream());
            async.addListener(new Ends(channel));
            channel.onClose(() -> {
                channels.remove(channel.id());
                lastActive.set(System.nanoTime());
            });
            hooks.opened().accept(channel);
            channels.put(channel.id(), channel);
            channel.send(JSON.writeValueAsString(Map.of("channel", channel.id())));
        }

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
            Channel channel = channelOf(request);
            if (channel == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "No such channel");
                return;
            }
            String message = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!message.isEmpty()) channel.received(message);
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        }

        @Override
        protected void doDelete(HttpServletRequest request, HttpServletResponse response) {
            Channel channel = channelOf(request);
            if (channel != null) channel.close();
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        }

        private Channel channelOf(HttpServletRequest request) {
            String path = request.getPathInfo();
            return path == null || path.length() < 2 ? null : channels.get(path.substring(1));
        }
    }

    /** The sentence a client of another version is refused with, naming both. */
    static String otherVersion(String core, String client) {
        return "BotMaker's core is version " + core + " and this client is " + (client == null ? "unnamed" : client)
                + "; both must be the same version.";
    }

    /** Closes the channel when its stream ends from the client's side or fails. */
    private record Ends(Channel channel) implements AsyncListener {
        @Override
        public void onComplete(AsyncEvent event) {
            channel.close();
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            channel.close();
        }

        @Override
        public void onError(AsyncEvent event) {
            channel.close();
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            // A channel's stream is started once.
        }
    }
}
