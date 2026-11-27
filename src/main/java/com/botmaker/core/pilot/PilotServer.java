// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.core.pilot.PilotMessages.RunState;
import com.botmaker.plugin.api.Runs;
import com.botmaker.plugin.api.TraceLine;
import com.botmaker.session.DesktopSession;
import com.botmaker.session.Preview;
import com.botmaker.session.video.VideoPacket;
import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.GenericWindow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;

import java.awt.Rectangle;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The remote <b>BotPilot</b> server: one embedded Jetty instance that serves the pilot web app as static files and
 * speaks a WebSocket protocol at {@code /ws?token=…} carrying, over a single connection:
 *
 * <ul>
 *   <li><b>binary</b> server→client: a live JPEG frame of the bot's target surface, prefixed with a 16-byte
 *       header ({@code sx,sy,sw,sh} as big-endian int32) so the client can map overlays. Loss-tolerant —
 *       a frame is <em>dropped</em> for any client whose previous send is still in flight (backpressure).</li>
 *   <li><b>binary</b> server→client, <em>instead of</em> the above for a client that asked: one H.264 access
 *       unit behind a {@link #BINARY_H264} tag byte, at the encoder's own pace rather than the frame loop's —
 *       see {@link #followVideo}.</li>
 *   <li><b>text</b> server→client: what the run's views draw over the picture ({@code {"type":"telemetry",…}},
 *       written by {@link RunViews#overlay}), run-state
 *       ({@code {"type":"state","run":"running|stopped|paused","backgroundInput":true}}, plus a
 *       {@code "reason"} sentence while the stream has no frames to send — see {@link #reportEmpty}) and the
 *       video stream's start/stop ({@code {"type":"video","codec":"avc1.42E01E","sx":…}}), the run's trace
 *       lines ({@code {"type":"trace","line":{"ts":…,"level":"debug","source":"Vision","text":…,"count":1}}},
 *       the last {@link #TRACE_BACKLOG} of them replayed to a phone that connects mid-run), on connect the
 *       input kinds this host takes ({@code {"type":"input","kinds":["tap",…,"key","text"]}}), and a
 *       {@code {"type":"notice","text":…}} when a key or text was not sent.</li>
 *   <li><b>text</b> client→server: control commands ({@code {"cmd":"start|stop|pause|resume"}}), the
 *       Interact arm/disarm ({@code {"cmd":"interact","on":true}}), once armed, manual gestures
 *       ({@code {"cmd":"input","kind":"tap|down|move|up|scroll","x":…,"y":…,"button":1,"amount":-3}} in
 *       absolute screen coordinates — see {@link PilotInputService}), keys and text
 *       ({@code {"cmd":"input","kind":"key","key":"ENTER"}}, {@code {"cmd":"input","kind":"text","text":"hi"}};
 *       an unknown kind is ignored and logged) and the codec negotiation
 *       ({@code {"cmd":"hello","accept":["h264"]}} — see {@link #handleHello}).</li>
 * </ul>
 *
 * <p><b>Two frame formats, and only one of them is negotiated.</b> JPEG is what every client gets and what
 * every client got before there was anything to negotiate; H.264 is offered only to a client that asks for it
 * by name, only on a session route, and only while an encoder is actually producing. Every step of that can
 * fail — no {@code ffmpeg}, no working encoder, no {@code VideoDecoder} in the phone's WebView, a route that is
 * an emulator — and every failure lands on the JPEG path, which is why it is not a fallback so much as the
 * floor.
 *
 * <p>Interact is <b>armed per connection</b> and starts disarmed: a passive viewer must never poke the game
 * because someone brushed the screen, and a leaked URL must not become a remote desktop.
 *
 * <p>It is meant to be reachable remotely over a tunnel, so the WS handshake is token-gated. Which surface is shown
 * comes from {@link PilotRoutes}, its pixels from {@link TargetCapture}, its text messages from
 * {@link PilotMessages}.
 */
public final class PilotServer implements AutoCloseable {

    /**
     * The frame rate the loop <em>aims</em> for. Raised from 12 once a session frame stopped costing three codec
     * passes ({@link Preview}); a route that can't sustain it simply runs slower, because
     * {@link #tick} never queues a frame behind an unfinished one.
     */
    private static final int FRAME_FPS = 24;

    private static final long FRAME_PERIOD_MS = 1000L / FRAME_FPS;

    /** The floor between frames, so a route slower than the period yields the thread rather than spinning. */
    private static final long MIN_FRAME_GAP_MS = 5;

    /** Where the phone's web app sits on the classpath. */
    private static final String WEB = "com/botmaker/core/pilot/web/";

    /** The open project's bot, as the host exposes it: start, stop, and what it reports while it runs. */
    private final Runs runs;
    /** What the run shows, and what it draws over the picture. */
    private final RunViews views;
    /** The subscriptions taken on {@link #runs}, closed with the server so a rebound pilot is not fed twice. */
    private final List<AutoCloseable> subscriptions = new ArrayList<>();
    /** Decides which surface is streamed and driven ({@code :0} / {@code :N} / an emulator) and owns its connection. */
    private final PilotRoutes routes;
    private final TargetCapture capture;
    /** The at-most-one H.264 encode of the current session route; see {@link #followVideo}. */
    private final PilotVideo video = new PilotVideo(Preview.MAX_EDGE, FRAME_FPS);
    /** Whether clients have been told a stream is running, so start/stop are each announced exactly once. */
    private boolean videoAnnounced;
    private final PilotControlService control;
    private final PairingStore pairing;
    private final PilotInputService input = new PilotInputService();
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Per-connection state: its outgoing {@link SendQueue}, and whether this client has armed Interact. Both are
     * per-client on purpose — one phone driving the game must not arm a second viewer, and one slow client must
     * not stall anyone else's messages.
     */
    private static final class Client {
        final SendQueue out;
        final AtomicBoolean interact = new AtomicBoolean(false);
        /** What the computer names this connection by, to disconnect it; no secret and no use to the phone. */
        final String id = UUID.randomUUID().toString().substring(0, 8);
        final String address;
        final long sinceMillis = System.currentTimeMillis();
        /** The last JPEG message queued for this client, so an unchanged picture is not sent again. */
        volatile byte[] lastFrame;

        Client(SendQueue out, String address) {
            this.out = out;
            this.address = address;
        }

        /** Whether this client said hello and named H.264 — false for every build that predates {@code hello}. */
        volatile boolean h264;
        /**
         * Whether this client has been given a decodable entry point and may therefore be sent inter-coded
         * pictures. Cleared whenever a packet is dropped for it: a decoder fed a picture whose reference is
         * missing does not recover on its own, so a dropped frame has to cost a resync to the next keyframe
         * rather than a stream that stays broken until the route changes.
         */
        final AtomicBoolean decoding = new AtomicBoolean(false);
    }

    /**
     * The first byte of an H.264 binary message. There is no such byte on a JPEG frame, and deliberately so:
     * the JPEG framing is already deployed on phones this host cannot update, so the new payload is the one
     * that carries a tag. A client only ever sees this after the {@code video} text message that announced the
     * stream, on the same ordered socket, so the tag is a check rather than the discriminator.
     */
    private static final byte BINARY_H264 = 2;

    /**
     * The same tag for an access unit a decoder can start on. It is a separate value rather than a second byte
     * because the client needs the fact anyway — WebCodecs' {@code EncodedVideoChunk} is constructed with
     * {@code type: "key" | "delta"} and has no way to work it out from the bytes — and the tag byte had 254
     * spare values.
     */
    private static final byte BINARY_H264_KEY = 3;

    /**
     * The close code for a socket whose token is wrong or was reset (policy violation), so a phone can tell "scan
     * again" from a link that dropped.
     */
    static final int BAD_TOKEN = 1008;

    private static final String BAD_TOKEN_REASON = "pairing token rejected";

    private static final String DISCONNECTED_REASON = "disconnected on the computer";

    /**
     * How many H.264 pictures may wait for one client. Dropping one costs a wait for the next keyframe, so a
     * link that stutters is given a quarter of a second before that.
     */
    private static final int H264_QUEUE = 6;

    /** How often each phone is sent a heartbeat; it gives up on the link after three that did not arrive. */
    private static final int PING_SECONDS = 5;

    private static final String COVERED_NOTICE =
            "Something covers the game on the computer and it could not be brought forward.";

    /** Connected, authorized clients. */
    private final Map<Session, Client> clients = new ConcurrentHashMap<>();

    /** How many trace lines a phone that connects mid-run is sent first. */
    static final int TRACE_BACKLOG = 200;
    /** The current run's last {@link #TRACE_BACKLOG} trace messages, oldest first; emptied when a run starts. */
    private final ArrayDeque<String> traceBacklog = new ArrayDeque<>();

    private Server server;
    private ServerConnector connector;
    private ScheduledExecutorService frameExec;
    /**
     * What the most recently pushed frame showed: the route that produced it, the window when it was one window on
     * {@code :0} (a tap raises it first), and its rect, the only region Interact gestures may land in ({@code null}
     * while no frame stands). A gesture belongs to the frame the user touched, not to whatever the route resolver
     * would answer by the time it arrives, so it reads all three at once.
     */
    private record Pushed(PilotRoute route, GenericWindow window, PilotInputService.Bounds bounds) {

        /** The same route with no frame to touch. */
        Pushed withoutFrame() {
            return new Pushed(route, null, null);
        }
    }

    private volatile Pushed pushed = new Pushed(PilotRoute.DESKTOP, null, null);
    /** Guards who is paired: a phone let in and a token revoked must not pass each other. */
    private final Object pairingLock = new Object();
    private volatile Runnable phonesChanged = () -> { };
    private volatile String token;
    private volatile RunState runState = RunState.STOPPED;
    /** Guards {@link #runState}'s changes; not {@code this}, which {@link #close} holds while ticks finish. */
    private final Object runStateLock = new Object();
    /** Ticks since the host was last asked whether the run is paused. Frame thread only. */
    private int ticksSincePauseCheck;
    /** When the last heartbeat went, by {@link System#nanoTime}. Frame thread only. */
    private long pingedAt = System.nanoTime();
    /**
     * How many ticks in a row produced no frame, touched only from the single {@code pilot-frame} thread;
     * {@link #emptyReason} is volatile because {@link #stateJson()} is also called from the WS threads.
     */
    private int emptyFrames;
    /** The sentence sent with the {@code state} message while frames are missing, or {@code null} when they aren't. */
    private volatile String emptyReason;

    PilotServer(Runs runs, RunViews views, PilotRoutes routes, TargetCapture capture, PilotControlService control,
                PairingStore pairing) {
        this.runs = runs == null ? Runs.NONE : runs;
        this.views = views;
        this.routes = routes;
        this.capture = capture;
        this.control = control;
        this.pairing = pairing;
    }

    /**
     * The pilot of an open project: {@code runs} its bot, {@code views} what its plugins show of a run, and the
     * background session the one held in this process for {@code resourcesRoot}.
     */
    public static PilotServer forProject(Runs runs, RunViews views, Path resourcesRoot, PairingStore pairing) {
        return new PilotServer(runs, views, PilotRoutes.of(resourcesRoot, views), TargetCapture.of(resourcesRoot),
                new PilotControlService(runs), pairing);
    }

    /**
     * Where the server is bound. The address a phone opens is the transport's: the same {@code http://host:port} for
     * a direct bind, a tunnel's public address otherwise.
     */
    public record Endpoint(String host, int port, String token) {}

    /**
     * What makes this server reachable when that is more than its own bind — a Funnel, a tunnel process — so it
     * is torn down with the server in {@link #close()}.
     */
    private volatile AutoCloseable front;

    /** Guards the one-time run subscriptions so a stop()+start() doesn't double-register handlers. */
    private boolean subscribed;

    /**
     * Starts the server bound to {@code host} (e.g. {@code 127.0.0.1}, a Tailscale IP, or {@code 0.0.0.0}).
     * Idempotent — returns the existing endpoint if already running.
     *
     * @throws IllegalStateException when no port could be bound on {@code host}
     */
    public synchronized Endpoint start(String host) {
        if (server != null) return new Endpoint(host, connector.getLocalPort(), token);

        // Reuse a kept token so the pairing URL is stable across restarts — a phone that paired
        // once reconnects without rescanning. Only mint (and keep) a fresh one the very first time.
        token = pairing.token();
        if (token == null || token.isBlank()) {
            token = newToken();
            pairing.token(token);
        }
        // Reuse the last bound port when it's free, so a direct address is stable across restarts as the token is.
        // Fall back to an OS-assigned ephemeral port if it's taken, then keep whatever we actually got.
        int desired = pairing.port();
        try {
            listen(host, desired); // desired 0 → OS-assigned ephemeral port
        } catch (Exception bindFailed) {
            try {
                listen(host, 0);
            } catch (Exception e) {
                throw new IllegalStateException("The Remote Pilot could not listen on " + host + ": " + e.getMessage(), e);
            }
        }
        pairing.port(connector.getLocalPort());

        // Subscribe once per instance — a stop()+start() (e.g. a tunnel-failure rebind) must not double-register
        // these handlers. The host's subscriptions are AutoCloseable, but this server outlives a rebind and
        // is closed once, so registering twice would deliver every frame twice.
        if (!subscribed) {
            subscribed = true;
            subscriptions.add(runs.onTelemetry(this::onTelemetryFrame));
            subscriptions.add(runs.onTrace(this::onTrace));
            subscriptions.add(runs.onStateChanged(running -> {
                if (running) {
                    clearTraceBacklog();
                    setRunState(RunState.RUNNING);
                } else {
                    control.onRunStopped();
                    setRunState(RunState.STOPPED);
                }
            }));
            // A run already going when the pilot opens announces nothing, so it is asked for.
            // Under the lock, so a run ending meanwhile writes its STOPPED after this and not before.
            synchronized (runStateLock) {
                if (runs.isRunning()) setRunState(control.isPaused() ? RunState.PAUSED : RunState.RUNNING);
            }
        }

        startFrameLoop();
        return new Endpoint(host, connector.getLocalPort(), token);
    }

    /** Builds and starts a server on {@code host:port}; keeps nothing when it cannot. */
    private void listen(String host, int port) throws Exception {
        Server built = new Server();
        ServerConnector bound = new ServerConnector(built);
        bound.setHost(host);
        bound.setPort(port);
        built.addConnector(bound);
        WebSocketUpgradeHandler sockets = WebSocketUpgradeHandler.from(built, container ->
                container.addMapping("/ws", (request, response, callback) ->
                        new Phone(Request.extractQueryParameters(request, StandardCharsets.UTF_8).getValue("token"))));
        ResourceHandler files = new ResourceHandler();
        files.setDirAllowed(false);
        // A build that carries no web app serves the socket alone.
        if (PilotServer.class.getClassLoader().getResource(WEB + "index.html") != null) {
            files.setBaseResource(ResourceFactory.of(files).newClassLoaderResource(WEB, false));
        }
        sockets.setHandler(files);
        built.setHandler(sockets);
        try {
            built.start();
        } catch (Exception e) {
            stop(built);
            throw e;
        }
        server = built;
        connector = bound;
    }

    private static void stop(Server running) {
        try {
            running.stop();
        } catch (Exception e) {
            Diag.error("[Pilot] the server did not stop cleanly: " + e.getMessage());
        }
    }

    /**
     * Records what fronts this already-started server — a transport that opened a tunnel or a Funnel onto it —
     * so {@link #close()} closes it with the server.
     *
     * @return false when the server was closed in the meantime: {@code front} is then closed at once, since
     *         nothing else would close it
     */
    public synchronized boolean attach(AutoCloseable front) {
        if (server == null) {
            closeFront(front);
            return false;
        }
        this.front = front;
        return true;
    }

    private static void closeFront(AutoCloseable front) {
        try {
            front.close();
        } catch (Exception e) {
            Diag.error("[Pilot] Couldn't close what made the Remote Pilot reachable: " + e.getMessage());
        }
    }

    /**
     * Stops the frame loop and waits for a tick already running: one left going could start an encoder or open
     * an emulator connection after they were closed, with nothing left to close it.
     */
    private void stopFrameLoop() {
        if (frameExec == null) return;
        frameExec.shutdownNow();
        try {
            if (!frameExec.awaitTermination(5, TimeUnit.SECONDS)) {
                Diag.error("[Pilot] the frame loop did not stop within 5 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        frameExec = null;
    }

    @Override
    public synchronized void close() {
        stopFrameLoop();
        video.close(); // the ffmpeg is reaped with its session anyway, but not until the session goes
        input.close();  // never hand the desktop back with a mouse button still down
        routes.close(); // drop any held emulator connection with the loop that was using it
        clients.clear();
        pushed = pushed.withoutFrame();
        for (AutoCloseable subscription : subscriptions) {
            try {
                subscription.close();
            } catch (Exception ignored) {
                // A host that cannot unsubscribe us has nothing left to deliver to either.
            }
        }
        subscriptions.clear();
        subscribed = false;
        if (server != null) {
            stop(server);
            server = null;
            connector = null;
        }
        if (front != null) {
            closeFront(front);
            front = null;
        }
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    private void drop(Session session) {
        boolean was = clients.remove(session) != null;
        input.releaseHeldBy(session);
        if (was) phonesChanged.run();
    }

    /** One phone's socket, with the token it asked with. Public because Jetty calls it. */
    public final class Phone implements Session.Listener.AutoDemanding {

        private final String provided;
        private volatile Session session;

        private Phone(String provided) {
            this.provided = provided;
        }

        @Override
        public void onWebSocketOpen(Session opened) {
            session = opened;
            Client client = new Client(new SendQueue(linkTo(opened)), addressOf(opened));
            synchronized (pairingLock) {
                if (!authorized(provided)) {
                    opened.close(BAD_TOKEN, BAD_TOKEN_REASON, Callback.NOOP);
                    return;
                }
                clients.put(opened, client);
            }
            phonesChanged.run();
            client.out.text(stateJson()); // let a fresh client render the current run state immediately
            client.out.text(PilotMessages.inputJson(PilotInputService.Kind.wireNames()));
            // …and the run's recent trace, so a phone that connects mid-run opens on context, not blank.
            for (String line : traceBacklog()) client.out.text(line);
        }

        @Override
        public void onWebSocketText(String message) {
            Session open = session;
            Client client = open == null ? null : clients.get(open);
            if (client != null) handleCommand(client, open, message);
        }

        // A phone that vanishes mid-drag never sends its UP, so the release happens here instead. Only its
        // own: another client leaving, or one refused for a bad token, must not cut this one's drag.
        @Override
        public void onWebSocketClose(int status, String reason, Callback callback) {
            if (session != null) drop(session);
            callback.succeed();
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            if (session != null) drop(session);
        }
    }

    private static String addressOf(Session session) {
        return session.getRemoteSocketAddress() instanceof InetSocketAddress from ? from.getHostString() : "unknown";
    }

    private SendQueue.Link linkTo(Session session) {
        return new SendQueue.Link() {
            @Override
            public void text(String text, Callback done) {
                session.sendText(text, done);
            }

            @Override
            public void binary(ByteBuffer bytes, Callback done) {
                session.sendBinary(bytes, done);
            }

            @Override
            public void giveUp() {
                drop(session);
                try {
                    session.close();
                } catch (Exception ignored) {
                    // Already gone.
                }
            }
        };
    }

    // --- Auth ---

    private boolean authorized(String provided) {
        if (token == null || provided == null) return false;
        // Constant-time compare: behind a tunnel this handshake is on the public internet, so avoid leaking the
        // token length/prefix through String.equals's early-out timing.
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static String newToken() {
        byte[] b = new byte[24]; // 192 bits — the sole guard once a tunnel exposes this publicly.
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /**
     * Revokes the current pairing token: generates + keeps a fresh one so previously-paired phones can no
     * longer authorize (they must rescan), and disconnects every phone connected now. A revoke that left an
     * open socket connected would leave exactly the phone it was pressed against still watching and driving
     * the bot. Returns the new token.
     */
    public synchronized String resetToken() {
        synchronized (pairingLock) {
            token = newToken();
            pairing.token(token);
            for (Session session : clients.keySet()) {
                clients.remove(session);
                try {
                    session.close(BAD_TOKEN, BAD_TOKEN_REASON, Callback.NOOP);
                } catch (Exception ignored) {
                    // Already gone; removing it was the part that mattered.
                }
            }
        }
        input.releaseHeld();
        phonesChanged.run();
        return token;
    }

    /** The token a phone pairs with now; null before the first start. */
    String token() {
        return token;
    }

    // --- Connected phones ---

    /**
     * One connected phone.
     *
     * @param id          what {@link #disconnect} takes
     * @param address     where its connection comes from; this computer's own, behind a tunnel
     * @param sinceMillis when it connected, in epoch milliseconds
     * @param interact    whether it armed Interact, so its gestures reach the game
     */
    public record Connected(String id, String address, long sinceMillis, boolean interact) {}

    /** The phones connected now, the longest first. */
    public List<Connected> connected() {
        return clients.values().stream()
                .map(c -> new Connected(c.id, c.address, c.sinceMillis, c.interact.get()))
                .sorted(Comparator.comparingLong(Connected::sinceMillis).thenComparing(Connected::id))
                .toList();
    }

    /**
     * Closes the connection of the phone {@code id} of {@link #connected}, with the code that makes a phone stop
     * retrying. Its token still stands: it may pair again, and only {@link #resetToken} keeps it out.
     */
    public void disconnect(String id) {
        for (Map.Entry<Session, Client> phone : clients.entrySet()) {
            if (!phone.getValue().id.equals(id)) continue;
            Session session = phone.getKey();
            drop(session);
            try {
                session.close(BAD_TOKEN, DISCONNECTED_REASON, Callback.NOOP);
            } catch (Exception ignored) {
                // Already gone; dropping it was the part that mattered.
            }
        }
    }

    /**
     * What to run when a phone connects, leaves or changes. Set before {@link #start}; it runs on the connection's
     * own thread, so it must not wait for anything this server does.
     */
    void onPhonesChanged(Runnable heard) {
        phonesChanged = heard;
    }

    // --- Inbound control commands ---

    private void handleCommand(Client client, Session session, String message) {
        JsonNode node;
        try {
            node = json.readTree(message);
        } catch (Exception e) {
            return;
        }
        PilotCommand cmd = PilotCommand.from(node.path("cmd").asText(null)).orElse(null);
        if (cmd == null) return;   // an older or newer phone build; ignore, never drop the connection
        switch (cmd) {
            case START -> runs.start();
            case STOP -> runs.stop();
            case PAUSE -> { control.pause().ifPresent(why -> notice(client, why)); refreshPausedState(); }
            case RESUME -> { control.resume().ifPresent(why -> notice(client, why)); refreshPausedState(); }
            case INTERACT -> {
                boolean on = node.path("on").asBoolean(false);
                boolean was = client.interact.getAndSet(on);
                if (!on) input.releaseHeldBy(session);
                if (was != on) phonesChanged.run();
            }
            case INPUT -> handleInput(client, session, node);
            case HELLO -> handleHello(client, node);
        }
    }

    private static void notice(Client client, String text) {
        client.out.text(PilotMessages.noticeJson(text));
    }

    /**
     * The client naming what it can decode. Only {@code h264} means anything today, and saying nothing means
     * JPEG — so an older phone, or one whose WebView has no {@code VideoDecoder}, needs no special case
     * anywhere: it simply never appears in the set of clients the video path sends to.
     *
     * <p>A client may say hello again at any time, and the pilot client does exactly that when configuring its
     * decoder throws — a hardware decoder can refuse a stream it advertised support for, and the only honest
     * recovery is to stop being an H.264 client. Re-sending hello with an empty {@code accept} is that.
     */
    private void handleHello(Client client, JsonNode node) {
        boolean wantsH264 = false;
        for (JsonNode codec : node.path("accept")) {
            wantsH264 |= "h264".equals(codec.asText(null));
        }
        client.h264 = wantsH264;
        client.decoding.set(false);
        client.lastFrame = null; // a phone that gave up on its decoder needs the picture again, changed or not
        if (!wantsH264) {
            return;
        }
        // A client that joins a running stream is told about it now rather than at the next route change,
        // which on a stable session would be never.
        if (video.live()) {
            sendVideoStart(client);
        }
    }

    /**
     * One manual Interact gesture. Dropped silently unless this connection armed Interact and we have a
     * frame surface to bound it by — an unarmed client's pointer events must never reach the desktop.
     */
    private void handleInput(Client client, Session session, JsonNode node) {
        if (!client.interact.get()) return;
        PilotInputService.Kind kind;
        String named = node.path("kind").asText("");
        try {
            kind = PilotInputService.Kind.valueOf(named.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknownKind) {
            // A newer phone's gesture: ignored, never a dropped connection, and said once in the log.
            Diag.warn("Pilot", "Ignoring input of unknown kind \"" + named + "\"");
            return;
        }
        Pushed seen = pushed;
        if (kind == PilotInputService.Kind.KEY || kind == PilotInputService.Kind.TEXT) {
            PilotInputService.Typed typed = input.type(seen.route(), kind, node.path("key").asText(""),
                    node.path("text").asText(""), seen.window(), seen.bounds());
            if (typed != PilotInputService.Typed.SENT) notice(client, typed.notice());
            return;
        }
        boolean landsOnThePointer = kind == PilotInputService.Kind.TAP || kind == PilotInputService.Kind.DOWN
                || kind == PilotInputService.Kind.SCROLL;
        if (landsOnThePointer && !input.bringForward(seen.route(), seen.window(), seen.bounds())) {
            if (kind == PilotInputService.Kind.DOWN) input.cancelGesture(session);
            notice(client, COVERED_NOTICE);
            return;
        }
        input.apply(session, seen.route(), kind, node.path("x").asInt(), node.path("y").asInt(),
                node.path("button").asInt(1), node.path("amount").asInt(0), seen.bounds());
    }

    /**
     * Brings the run-state in line with whether the run is paused, here or in the host (only meaningful while a
     * run is active).
     */
    private void refreshPausedState() {
        synchronized (runStateLock) {
            if (runState == RunState.STOPPED) return;
            RunState now = control.isPaused() ? RunState.PAUSED : RunState.RUNNING;
            if (now != runState) setRunState(now);
        }
    }

    /** The host announces no pause, so it is asked about once a second while somebody watches. */
    private void followHostPause() {
        if (clients.isEmpty() || ++ticksSincePauseCheck < FRAME_FPS) return;
        ticksSincePauseCheck = 0;
        refreshPausedState();
    }

    private void heartbeat() {
        // By the clock, not by ticks: a slow grab stretches a tick far past a frame's length.
        long now = System.nanoTime();
        if (clients.isEmpty() || now - pingedAt < TimeUnit.SECONDS.toNanos(PING_SECONDS)) return;
        pingedAt = now;
        broadcastText(PilotMessages.pingJson());
    }

    // --- Telemetry + state fan-out (text messages) ---

    /**
     * One telemetry frame as the host delivered it — the bytes the bot wrote. Only the run's views can read it; one
     * that draws nothing, or that none can read, is dropped.
     */
    private void onTelemetryFrame(byte[] frame) {
        if (frame == null || frame.length == 0 || clients.isEmpty()) return;
        views.overlay(frame).ifPresent(this::broadcastText);
    }

    /**
     * One line of the run's trace, as the host read it, sent to every phone for its log drawer and kept for the
     * next phone to connect (docs/refactor/40-run-trace.md).
     */
    private void onTrace(TraceLine line) {
        if (line == null) return;
        String message = PilotMessages.traceJson(line);
        synchronized (traceBacklog) {
            traceBacklog.addLast(message);
            if (traceBacklog.size() > TRACE_BACKLOG) traceBacklog.removeFirst();
        }
        broadcastText(message);
    }

    /** What a phone connecting now is sent after the run state, oldest first. */
    List<String> traceBacklog() {
        synchronized (traceBacklog) {
            return List.copyOf(traceBacklog);
        }
    }

    private void clearTraceBacklog() {
        synchronized (traceBacklog) {
            traceBacklog.clear();
        }
    }

    private void setRunState(RunState state) {
        synchronized (runStateLock) {
            runState = state;
            broadcastText(stateJson());
        }
    }

    private String stateJson() {
        return PilotMessages.stateJson(runState, input.supportsBackgroundInput(pushed.route()), emptyReason);
    }

    /**
     * How many consecutive frameless ticks are worth mentioning. A grab that misses once is normal — an
     * emulator between screens, a window mid-swap — and saying so would flicker a warning on every route. Two
     * seconds of nothing at {@link #FRAME_FPS} is not a hiccup.
     */
    private static final int EMPTY_FRAMES_BEFORE_SAYING_SO = FRAME_FPS * 2;

    /**
     * A tick produced no frame. Count it, and once the run is long enough to mean something, tell the client
     * <em>why</em> instead of leaving it staring at a canvas that simply stopped updating.
     *
     * <p>The reason is derived from the route, because that is what determines the answer: a session route with
     * nothing on its {@code :N} root is the Wayland-only case ({@code PilotRoutes} rung 1 and
     * {@link DesktopSession#x11Capturable()}); an emulator route is a device that stopped answering ADB. Said once,
     * and again only when the sentence changes during the same outage.
     */
    private void reportEmpty(PilotRoute route) {
        if (++emptyFrames < EMPTY_FRAMES_BEFORE_SAYING_SO) return;
        String why = switch (route) {
            case PilotRoute.Missing(String missing) -> missing;
            case PilotRoute.Session ignored ->
                    "The background session is not showing any pixels on its X display. A Wayland-only app "
                            + "(such as Waydroid) renders where an X11 grab cannot see it.";
            case PilotRoute.Emulator ignored ->
                    "The emulator stopped answering over ADB — check that the instance is still running.";
            case PilotRoute.Screen(var shown) -> TargetCapture.whyNothing(shown);
        };
        if (why.equals(emptyReason)) return;
        emptyReason = why;
        // Forget what was last logged, so the frames coming back is a line too and not just their stopping.
        loggedSurface = null;
        Diag.log("[Pilot] no frame for " + emptyFrames + " ticks on " + route);
        // A phone may blank its picture on the reason, so the frame that comes back is sent even if unchanged.
        clients.values().forEach(c -> c.lastFrame = null);
        broadcastText(stateJson());
    }

    /**
     * A tick on {@code asked} produced no frame. On the user's own screen, and on a change of route (another emulator
     * included), the last frame's rect is no longer what a tap would land on, so it is dropped: gestures are refused
     * until a frame arrives. The same session or emulator that merely missed a grab keeps it, since its rect is its
     * own.
     */
    private void forgetSurface(PilotRoute asked) {
        Pushed last = pushed;
        if (last.bounds() == null) return;
        if (!(last.route() instanceof PilotRoute.Screen) && last.route().equals(asked)) return;
        pushed = last.withoutFrame();
        input.releaseHeld();
    }

    /** The last surface logged, so {@link #logSurface} can print on change instead of 24 times a second. */
    private String loggedSurface;

    /**
     * Say what is being streamed, and from where, <b>whenever that changes</b>.
     *
     * <p>This exists because its absence was expensive. "The pilot is black" was indistinguishable from the
     * outside between a route that had been demoted, a grab that returned nothing, and a grab that returned a
     * frame of a display nothing was painting — and the answer turned out to be the third, which no log line
     * anywhere would have shown. One line per change costs nothing on a stable session and names the surface
     * the next report is about.
     */
    private void logSurface(PilotRoute route, int sx, int sy, int sw, int sh) {
        String line = switch (route) {
            case PilotRoute.Session ignored -> "session";
            case PilotRoute.Emulator ignored -> "emulator";
            case PilotRoute.Screen ignored -> "screen";
            case PilotRoute.Missing ignored -> "nothing";
        } + " " + sw + "x" + sh + "+" + sx + "+" + sy;
        if (line.equals(loggedSurface)) return;
        loggedSurface = line;
        Diag.log("[Pilot] streaming " + line);
    }

    /** A frame arrived — by either path. Retract the warning, or the client explains a problem it no longer has. */
    private void clearEmptyReport() {
        emptyFrames = 0;
        if (emptyReason != null) {
            emptyReason = null;
            broadcastText(stateJson());
        }
    }

    private void broadcastText(String text) {
        for (Client client : clients.values()) client.out.text(text);
    }

    // --- H.264 (the fast path, when both ends can take it) ---

    /**
     * Brings the H.264 encode in line with {@code asked} and reports whether it now carries <em>every</em>
     * client — in which case this tick has no JPEG to grab at all, which is the saving that makes the video
     * path worth having on the computer's side as well as the phone's.
     *
     * <p>Video is only ever offered on a {@link PilotRoute.Session}: it grabs an X display directly, so the
     * emulator route (pixels that arrive over ADB, never on a display) and the {@code :0} route (the user's own
     * screen, which this must not hand a GPU encoder) have nothing for it to read. Both keep the JPEG path,
     * which is what they had.
     */
    private boolean followVideo(PilotRoute asked) {
        DesktopSession wanted = asked instanceof PilotRoute.Session(DesktopSession s) && anyClientWantsVideo()
                ? s : null;
        boolean live = video.follow(wanted, this::onVideoPacket);
        if (live != videoAnnounced) {
            videoAnnounced = live;
            announceVideo(live);
        }
        if (!live) return false;

        // The video is the frame source now, so it owes the same two facts the JPEG path publishes: an Interact
        // gesture is clamped to the surface the client was shown and replayed on the route that produced it.
        Rectangle surface = video.rect();
        pushed = new Pushed(asked, null,
                new PilotInputService.Bounds(surface.x, surface.y, surface.width, surface.height));
        clearEmptyReport();
        logSurface(asked, surface.x, surface.y, surface.width, surface.height);
        return clients.values().stream().allMatch(c -> c.h264);
    }

    private boolean anyClientWantsVideo() {
        return clients.values().stream().anyMatch(c -> c.h264);
    }

    /** Tells the H.264 clients that a stream started (with its codec and rect) or ended. */
    private void announceVideo(boolean started) {
        for (Client client : clients.values()) {
            if (!client.h264) continue;
            client.decoding.set(false);
            client.lastFrame = null;
            if (started) {
                sendVideoStart(client);
            } else {
                client.out.text(PilotMessages.videoStoppedJson());
            }
        }
    }

    private void sendVideoStart(Client client) {
        Rectangle r = video.rect();
        client.out.text(PilotMessages.videoJson(video.codec(), r.x, r.y, r.width, r.height));
    }

    /**
     * One encoded picture, on the encoder's reader thread. Queued for each H.264 client that has an entry point,
     * up to {@link #H264_QUEUE} pictures behind — and past that a dropped picture puts that client back to
     * waiting for a keyframe, because a decoder handed a picture whose reference never arrived produces garbage
     * indefinitely rather than one bad frame.
     */
    private void onVideoPacket(VideoPacket packet) {
        if (clients.isEmpty()) return;
        byte[] payload = new byte[1 + packet.annexB().length];
        payload[0] = packet.keyframe() ? BINARY_H264_KEY : BINARY_H264;
        System.arraycopy(packet.annexB(), 0, payload, 1, packet.annexB().length);

        for (Client client : clients.values()) {
            if (!client.h264) continue;
            // A client that has not been given a decodable entry point yet waits for the next keyframe; the
            // encoder's GOP is what bounds that wait.
            if (!client.decoding.get() && !packet.keyframe()) continue;
            client.decoding.set(client.out.frame(payload, H264_QUEUE));
        }
    }

    // --- Frame loop (binary messages, per-client backpressure) ---

    private void startFrameLoop() {
        frameExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pilot-frame");
            t.setDaemon(true);
            return t;
        });
        frameExec.schedule(this::tick, 300, TimeUnit.MILLISECONDS);
    }

    /**
     * One frame, then reschedule for what is <em>left</em> of the period.
     *
     * <p>This replaced a fixed delay, and it is not the same thing as a fixed rate. A fixed rate queues ticks
     * back-to-back on this single thread the moment a grab runs long — an emulator frame pulled over ADB
     * routinely does — which is a backlog, not a frame rate, and the reason the loop was a fixed *delay* to
     * begin with. But a fixed delay charges the full period <em>on top of</em> the work, so a route that
     * captures in 5 ms still ran at {@link #FRAME_FPS} rather than at what it could sustain. Subtracting the
     * work keeps the no-backlog property (the next tick is only ever scheduled once this one has returned)
     * while letting a fast route reach the target rate. The floor is there so a route that cannot keep up still
     * yields the thread instead of spinning.
     */
    private void tick() {
        long started = System.nanoTime();
        try {
            input.releaseStale();
            followHostPause();
            heartbeat();
            pushFrame();
        } catch (Throwable ex) {
            // A frame that threw must not silently end the loop — that is a pilot that goes dark forever.
            Diag.error("[Pilot] frame failed: " + ex);
        }
        long workMs = (System.nanoTime() - started) / 1_000_000L;
        long delay = Math.max(MIN_FRAME_GAP_MS, Math.min(FRAME_PERIOD_MS, FRAME_PERIOD_MS - workMs));
        ScheduledExecutorService exec = frameExec;
        if (exec == null || exec.isShutdown()) return;
        try {
            exec.schedule(this::tick, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) {
            // close() won the race; there is nothing left to serve frames to.
        }
    }

    private void pushFrame() {
        if (clients.isEmpty()) {
            followVideo(null);   // nobody to encode for; let the ffmpeg go rather than heat a GPU for no one
            pushed = pushed.withoutFrame(); // the next phone was shown nothing yet, so it may touch nothing yet
            return;
        }
        PilotRoute asked = routes.current();
        if (followVideo(asked)) return;   // every client is on video; there is no JPEG left to grab one for
        TargetCapture.Resolved resolved = capture.resolve(asked);
        // Already-encoded on the session route: the agent that holds :N produced these bytes, so nothing here
        // decoded a PNG only to re-encode it.
        byte[] jpeg = resolved == null ? null : resolved.bytes();
        if (jpeg == null) {
            forgetSurface(asked);
            reportEmpty(asked);
            return;
        }
        TargetCapture.Capture cap = resolved.cap();
        clearEmptyReport();
        logSurface(resolved.route(), cap.sx(), cap.sy(), cap.sw(), cap.sh());

        // Interact gestures are replayed on the route that produced this frame and clamped to what the client
        // was actually shown, so both must be published from here — the one place that knows what went over the
        // wire — and both must come from the *same* resolution. The route is therefore the one the capture
        // reports, never the one we asked for: those differed whenever a grab failed, and the client was then
        // told it was touching a surface it had not been shown. A route change also changes whether input is
        // background-safe, which the client renders as a warning, so tell it rather than leaving a stale one up.
        PilotRoute route = resolved.route();
        boolean routeChanged = !pushed.route().getClass().equals(route.getClass());
        pushed = new Pushed(route, cap.window(),
                new PilotInputService.Bounds(cap.sx(), cap.sy(), cap.sw(), cap.sh()));
        if (routeChanged) broadcastText(stateJson());

        byte[] payload = new byte[16 + jpeg.length];
        ByteBuffer.wrap(payload)
                .putInt(cap.sx()).putInt(cap.sy()).putInt(cap.sw()).putInt(cap.sh())
                .put(jpeg);

        for (Client client : clients.values()) {
            // A client being fed H.264 must not also be fed JPEG: it would draw the two interleaved, at two
            // different latencies, which looks exactly like a stutter in the video.
            if (client.h264 && video.live()) continue;
            if (Arrays.equals(payload, client.lastFrame)) continue;
            // One frame at a time: a client still flushing the previous one skips this one — keeps the
            // real-time feel on a slow link.
            if (client.out.frame(payload, 1)) client.lastFrame = payload;
        }
    }
}
