// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.plugin.api.Runs;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.TraceLine;
import com.botmaker.shared.capture.NativeControllerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Capture-free test of {@link PilotServer}: it boots, serves the web app it finds on the classpath, and its
 * WebSocket is token-gated. The plugin view used here names nothing to show, so an authorized phone is connected
 * without any screen being captured.
 */
class PilotServerTest {

    /** The host's run, as a test drives it: it keeps the listeners and counts the starts. */
    private static final class HeldRuns implements Runs {
        final List<Consumer<Boolean>> state = new ArrayList<>();
        final List<Consumer<byte[]>> telemetry = new ArrayList<>();
        final List<Consumer<TraceLine>> trace = new ArrayList<>();
        final CountDownLatch started = new CountDownLatch(1);

        @Override public void start() { started.countDown(); }
        @Override public void stop() { }
        @Override public boolean isRunning() { return false; }
        @Override public OptionalLong pid() { return OptionalLong.empty(); }
        @Override public AutoCloseable onStateChanged(Consumer<Boolean> l) { state.add(l); return () -> { }; }
        @Override public AutoCloseable onTelemetry(Consumer<byte[]> l) { telemetry.add(l); return () -> { }; }
        @Override public AutoCloseable onTrace(Consumer<TraceLine> l) { trace.add(l); return () -> { }; }
    }

    private final HeldRuns runs = new HeldRuns();
    private PilotServer server;

    @BeforeEach
    void noRealInput() {
        NativeControllerFactory.setForTesting(new PilotFakes.RecordingController());
        RunViewsTest.Shows.open = false;
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        NativeControllerFactory.setForTesting(null);
        RunViewsTest.Shows.open = true;
    }

    private PilotServer.Endpoint start() {
        return start(PilotRuns.NONE);
    }

    private PilotServer.Endpoint start(PilotRuns listing) {
        List<StudioPlugin> bound = List.of(new RunViewsTest.Plugin());
        RunViews views = new RunViews(() -> bound, null, Runnable::run, () -> { });
        server = new PilotServer(runs, views, new PilotRoutes(() -> null, views::shown, serial -> null),
                new TargetCapture(null), new PilotControlService(runs), PairingStore.inMemory(), listing);
        return server.start("127.0.0.1");
    }

    /** Two live runs, the first of which the open project holds. */
    private static PilotRuns listed(String viewed, String... ids) {
        List<PilotRuns.Listed> live = new ArrayList<>();
        for (String id : ids) live.add(new PilotRuns.Listed(id, "Bot " + id, 1_000L * (live.size() + 1)));
        return new PilotRuns() {
            @Override public List<Listed> live() { return live; }
            @Override public Optional<String> viewed() { return Optional.ofNullable(viewed); }
        };
    }

    private static WebSocket connect(PilotServer.Endpoint ep, BlockingQueue<String> heard) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + ep.port() + "/ws?token=" + ep.token()),
                        new WebSocket.Listener() {
                            private final StringBuilder part = new StringBuilder();

                            @Override public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                                part.append(data);
                                if (last) {
                                    heard.add(part.toString());
                                    part.setLength(0);
                                }
                                w.request(1);
                                return null;
                            }
                        })
                .get(5, TimeUnit.SECONDS);
    }

    /** The next message of {@code type}, skipping the others. */
    private static String next(BlockingQueue<String> heard, String type) throws InterruptedException {
        return next(heard, type, "");
    }

    /**
     * The next message of {@code type} that holds {@code part}: a list or a state may be told twice while a phone
     * connects, and a test waits for the one it is about.
     */
    private static String next(BlockingQueue<String> heard, String type, String part) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) {
            String message = heard.poll(1, TimeUnit.SECONDS);
            if (message != null && message.contains("\"type\":\"" + type + "\"") && message.contains(part)) {
                return message;
            }
        }
        throw new AssertionError("no " + type + " with " + part + " heard");
    }

    private static TraceLine line(int number) {
        return new TraceLine(Instant.EPOCH, TraceLine.Level.INFO, "Bot", "line " + number, 1, "", "", "",
                OptionalInt.empty(), Optional.empty());
    }

    @Test
    void servesStaticUiAndGatesWebSocketByToken() throws Exception {
        PilotServer.Endpoint ep = start();
        HttpClient http = HttpClient.newHttpClient();

        HttpResponse<String> page = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ep.port() + "/")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("BotPilot"), "served page should be the pilot UI");

        // A bad token must be rejected: the server closes the session right after connect.
        CountDownLatch closed = new CountDownLatch(1);
        AtomicInteger closeCode = new AtomicInteger();
        WebSocket ws = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + ep.port() + "/ws?token=WRONG"),
                        new WebSocket.Listener() {
                            @Override public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
                                closeCode.set(code);
                                closed.countDown();
                                return null;
                            }
                            @Override public void onError(WebSocket w, Throwable error) {
                                closed.countDown();
                            }
                        })
                .get(5, TimeUnit.SECONDS);
        assertTrue(closed.await(5, TimeUnit.SECONDS), "unauthorized WS should be closed by the server");
        assertEquals(PilotServer.BAD_TOKEN, closeCode.get(), "the phone must be able to tell it from a lost link");
        ws.abort();
    }

    @Test
    void aPairedPhoneHearsTheRunAndStartsIt() throws Exception {
        PilotServer.Endpoint ep = start();
        BlockingQueue<String> heard = new LinkedBlockingQueue<>();
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + ep.port() + "/ws?token=" + ep.token()),
                        new WebSocket.Listener() {
                            private final StringBuilder part = new StringBuilder();

                            @Override public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                                part.append(data);
                                if (last) {
                                    heard.add(part.toString());
                                    part.setLength(0);
                                }
                                w.request(1);
                                return null;
                            }
                        })
                .get(5, TimeUnit.SECONDS);

        assertContains("\"type\":\"input\"", heard.poll(5, TimeUnit.SECONDS));
        assertContains("\"type\":\"runs\"", heard.poll(5, TimeUnit.SECONDS));
        String state = heard.poll(5, TimeUnit.SECONDS);
        assertContains("\"type\":\"state\"", state);
        assertFalse(state.contains("runId"), "no run is listed, so the message is what the released app was sent");

        runs.telemetry.forEach(l -> l.accept("{\"type\":\"telemetry\"}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("{\"type\":\"telemetry\"}", heard.poll(5, TimeUnit.SECONDS), "the view writes the overlay");

        ws.sendText("{\"cmd\":\"start\"}", true).get(5, TimeUnit.SECONDS);
        assertTrue(runs.started.await(5, TimeUnit.SECONDS));
        ws.abort();
    }

    @Test
    void aPhoneIsShownTheRunTheProjectHoldsAndMayPickAnotherThatHasNoViewAndNoControl() throws Exception {
        PilotServer.Endpoint ep = start(listed("run-b", "run-a", "run-b"));
        BlockingQueue<String> heard = new LinkedBlockingQueue<>();
        WebSocket ws = connect(ep, heard);

        assertContains("\"id\":\"run-a\"", next(heard, "runs", "\"selected\":\"run-b\""));
        next(heard, "state", "\"runId\":\"run-b\"");

        ws.sendText("{\"cmd\":\"select\",\"run\":\"run-a\"}", true).get(5, TimeUnit.SECONDS);
        next(heard, "runs", "\"selected\":\"run-a\"");
        String noView = next(heard, "state", "no view");
        assertContains("no view of this run yet", noView);
        assertContains("\"runId\":\"run-a\"", noView);

        // Its commands are refused, and so are its gestures: nothing of it is driven.
        ws.sendText("{\"cmd\":\"interact\",\"on\":true}", true).get(5, TimeUnit.SECONDS);
        ws.sendText("{\"cmd\":\"start\"}", true).get(5, TimeUnit.SECONDS);
        assertContains("cannot be controlled", next(heard, "notice"));
        assertEquals(1, runs.started.getCount());
        assertEquals(List.of(), server.connected().stream().filter(PilotServer.Connected::interact).toList());

        // Disarming is never refused: the first notice heard after it is the next command's.
        heard.clear();
        ws.sendText("{\"cmd\":\"interact\",\"on\":false}", true).get(5, TimeUnit.SECONDS);
        ws.sendText("{\"cmd\":\"select\",\"run\":\"gone\"}", true).get(5, TimeUnit.SECONDS);
        assertContains("not running any more", next(heard, "notice"));

        // A command names its run, and the one shown by the other phone's pick is not this one's.
        ws.sendText("{\"cmd\":\"select\",\"run\":\"run-b\"}", true).get(5, TimeUnit.SECONDS);
        next(heard, "runs", "\"selected\":\"run-b\"");
        ws.sendText("{\"cmd\":\"start\",\"run\":\"run-a\"}", true).get(5, TimeUnit.SECONDS);
        assertContains("cannot be controlled", next(heard, "notice"));
        assertEquals(1, runs.started.getCount());
        ws.sendText("{\"cmd\":\"start\",\"run\":\"run-b\"}", true).get(5, TimeUnit.SECONDS);
        assertTrue(runs.started.await(5, TimeUnit.SECONDS));
        ws.abort();
    }

    @Test
    void aRunThatIsNotListedCannotBePicked() throws Exception {
        PilotServer.Endpoint ep = start(listed("run-a", "run-a"));
        BlockingQueue<String> heard = new LinkedBlockingQueue<>();
        WebSocket ws = connect(ep, heard);
        next(heard, "state");

        ws.sendText("{\"cmd\":\"select\",\"run\":\"gone\"}", true).get(5, TimeUnit.SECONDS);
        assertContains("not running any more", next(heard, "notice"));
        next(heard, "runs", "\"selected\":\"run-a\"");
        ws.abort();
    }

    @Test
    void anOtherProcessRunIsShownWithNoViewButTheOpenProjectsBotCanStillStart() throws Exception {
        PilotServer.Endpoint ep = start(listed(null, "run-x"));
        BlockingQueue<String> heard = new LinkedBlockingQueue<>();
        WebSocket ws = connect(ep, heard);

        // A phone that picked nothing keeps the open project's idle views, as the released app always had.
        String list = next(heard, "runs", "run-x");
        assertFalse(list.contains("\"selected\":\"run-x\""), list);
        assertFalse(next(heard, "state").contains("no view"));

        // Picking the run shows its missing view.
        ws.sendText("{\"cmd\":\"select\",\"run\":\"run-x\"}", true).get(5, TimeUnit.SECONDS);
        next(heard, "runs", "\"selected\":\"run-x\"");
        next(heard, "state", "no view of this run yet");

        // Starting names no run and starts the project's own bot, which no run holds.
        ws.sendText("{\"cmd\":\"start\"}", true).get(5, TimeUnit.SECONDS);
        assertTrue(runs.started.await(5, TimeUnit.SECONDS));
        ws.abort();
    }

    /** A run this core started beside the bot has views of its own: its phone is told that run's state, not "no view". */
    @Test
    void aSideRunsPhoneIsServedByTheRunsOwnStage() throws Exception {
        List<StudioPlugin> bound = List.of(new RunViewsTest.Plugin());
        RunViews sideViews = new RunViews(() -> bound, null, Runnable::run, () -> { });
        PilotRuns listing = new PilotRuns() {
            @Override public List<Listed> live() { return List.of(new Listed("run-s", "Sign in", 1_000L)); }
            @Override public Optional<String> viewed() { return Optional.empty(); }
            @Override public Optional<RunViews> viewsOf(String id) {
                return "run-s".equals(id) ? Optional.of(sideViews) : Optional.empty();
            }
        };
        PilotServer.Endpoint ep = start(listing);
        BlockingQueue<String> heard = new LinkedBlockingQueue<>();
        WebSocket ws = connect(ep, heard);
        next(heard, "runs", "run-s");

        ws.sendText("{\"cmd\":\"select\",\"run\":\"run-s\"}", true).get(5, TimeUnit.SECONDS);
        // The stage's own frame loop finds nothing to show and says why, which "no view of this run yet" never would.
        String state = next(heard, "state", "The game window is closed.");
        assertContains("\"runId\":\"run-s\"", state);
        ws.abort();
    }

    /** A listing of one side run, "run-s" of the instance "Pixel 5", with the views its stage is built from. */
    private PilotServer.Endpoint startWithPicturedSideRun(AtomicInteger grabs) {
        List<StudioPlugin> bound = List.of(new RunViewsTest.Plugin());
        RunViews sideViews = new RunViews(() -> bound, null, Runnable::run, () -> { });
        PilotRuns listing = new PilotRuns() {
            @Override public List<Listed> live() {
                return List.of(new Listed("run-s", "Sign in", "Pixel 5", 1_000L));
            }
            @Override public Optional<String> viewed() { return Optional.empty(); }
            @Override public Optional<RunViews> viewsOf(String id) {
                return "run-s".equals(id) ? Optional.of(sideViews) : Optional.empty();
            }
        };
        TargetCapture picture = new TargetCapture(null) {
            @Override Resolved resolve(PilotRoute route) {
                grabs.incrementAndGet();
                return new Resolved(route, new Capture(new java.awt.image.BufferedImage(320, 200,
                        java.awt.image.BufferedImage.TYPE_INT_RGB), 0, 0, 320, 200));
            }
        };
        List<StudioPlugin> idleBound = List.of(new RunViewsTest.Plugin());
        RunViews views = new RunViews(() -> idleBound, null, Runnable::run, () -> { });
        server = new PilotServer(runs, views, new PilotRoutes(() -> null, views::shown, serial -> null),
                new TargetCapture(null), new PilotControlService(runs), PairingStore.inMemory(), listing,
                (run, shown) -> new PilotStage(run, shown, PilotRoutes.sideOf(shown), picture,
                        new PilotVideo(160, 24)));
        return server.start("127.0.0.1");
    }

    @Test
    void theRunsListNamesTheInstanceOfEachRun() throws Exception {
        PilotServer.Endpoint ep = startWithPicturedSideRun(new AtomicInteger());
        BlockingQueue<String> heard = new LinkedBlockingQueue<>();
        WebSocket ws = connect(ep, heard);
        assertContains("\"instance\":\"Pixel 5\"", next(heard, "runs", "run-s"));
        ws.abort();
    }

    @Test
    void aPhoneWithItsOverviewOpenIsSentAThumbnailOfARunNoPhoneIsShown() throws Exception {
        AtomicInteger grabs = new AtomicInteger();
        PilotServer.Endpoint ep = startWithPicturedSideRun(grabs);
        BlockingQueue<String> heard = new LinkedBlockingQueue<>();
        WebSocket ws = connect(ep, heard);
        next(heard, "runs", "run-s");

        ws.sendText("{\"cmd\":\"overview\",\"on\":true}", true).get(5, TimeUnit.SECONDS);
        String thumb = next(heard, "thumb", "run-s");
        assertContains("\"jpeg\":\"/9j/", thumb);   // a JPEG, base64
        assertTrue(grabs.get() > 0);
        ws.abort();
    }

    @Test
    void aPhoneThatNeverOpenedItsOverviewIsSentNoThumbnailAndNothingIsGrabbed() throws Exception {
        AtomicInteger grabs = new AtomicInteger();
        PilotServer.Endpoint ep = startWithPicturedSideRun(grabs);
        BlockingQueue<String> heard = new LinkedBlockingQueue<>();
        WebSocket ws = connect(ep, heard);
        next(heard, "runs", "run-s");

        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
        while (System.nanoTime() < until) {
            String message = heard.poll(200, TimeUnit.MILLISECONDS);
            assertTrue(message == null || !message.contains("\"type\":\"thumb\""), message);
        }
        assertEquals(0, grabs.get());
        ws.abort();
    }

    /** A run the pilot holds no view for is stopped, paused and resumed by its id, and only while the phone is shown it. */
    @Test
    void aRunWithNoViewCanBePausedResumedAndStoppedByItsId() throws Exception {
        List<String> signalled = new ArrayList<>();
        java.util.Set<String> paused = java.util.concurrent.ConcurrentHashMap.newKeySet();
        PilotRuns listing = new PilotRuns() {
            @Override public List<Listed> live() { return List.of(new Listed("run-x", "Bot x", 1_000L)); }
            @Override public Optional<String> viewed() { return Optional.empty(); }
            @Override public java.util.Set<String> paused() { return paused; }
            @Override public Optional<String> pause(String id) { signalled.add("pause " + id); paused.add(id); return Optional.empty(); }
            @Override public Optional<String> resume(String id) { signalled.add("resume " + id); paused.remove(id); return Optional.empty(); }
            @Override public Optional<String> stop(String id) { signalled.add("stop " + id); return Optional.of("denied"); }
        };
        PilotServer.Endpoint ep = start(listing);
        BlockingQueue<String> heard = new LinkedBlockingQueue<>();
        WebSocket ws = connect(ep, heard);
        next(heard, "runs", "run-x");

        // A phone shown no run (the idle views) cannot signal a run it is not shown.
        ws.sendText("{\"cmd\":\"pause\",\"run\":\"run-x\"}", true).get(5, TimeUnit.SECONDS);
        assertContains("no view", next(heard, "notice"));
        assertEquals(List.of(), signalled);

        ws.sendText("{\"cmd\":\"select\",\"run\":\"run-x\"}", true).get(5, TimeUnit.SECONDS);
        next(heard, "runs", "\"selected\":\"run-x\"");
        ws.sendText("{\"cmd\":\"pause\",\"run\":\"run-x\"}", true).get(5, TimeUnit.SECONDS);
        next(heard, "runs", "\"state\":\"paused\"");
        ws.sendText("{\"cmd\":\"resume\"}", true).get(5, TimeUnit.SECONDS);
        next(heard, "runs", "\"state\":\"running\"");
        ws.sendText("{\"cmd\":\"stop\",\"run\":\"run-x\"}", true).get(5, TimeUnit.SECONDS);
        assertContains("denied", next(heard, "notice"));
        assertEquals(List.of("pause run-x", "resume run-x", "stop run-x"), signalled);
        ws.abort();
    }

    private static void assertContains(String part, String message) {
        assertNotNull(message, "nothing heard, waiting for " + part);
        assertTrue(message.contains(part), message);
    }

    /** A tunnel that came up for a server closed meanwhile has nothing else to close it. */
    @Test
    void aFrontAttachedAfterTheServerClosedIsClosedAtOnce() {
        start();
        server.close();

        AtomicBoolean frontClosed = new AtomicBoolean();
        assertFalse(server.attach(() -> frontClosed.set(true)));
        assertTrue(frontClosed.get());
    }

    /**
     * The log drawer's backlog: the run's last lines, kept for a phone that connects mid-run, and emptied when
     * the next run starts. Driven through the host's listeners, with no phone connected (so nothing captures).
     */
    @Test
    void keepsTheRunsLastTraceLinesForTheNextPhoneAndForgetsThemWhenARunStarts() {
        start();

        for (int i = 0; i < PilotServer.TRACE_BACKLOG + 5; i++) {
            TraceLine line = line(i);
            runs.trace.forEach(l -> l.accept(line));
        }

        List<String> kept = server.traceBacklog();
        assertEquals(PilotServer.TRACE_BACKLOG, kept.size());
        assertTrue(kept.getFirst().contains("\"line 5\""), kept.getFirst());
        assertTrue(kept.getLast().contains("\"line " + (PilotServer.TRACE_BACKLOG + 4) + "\""));

        runs.state.forEach(l -> l.accept(true));
        assertEquals(List.of(), server.traceBacklog(), "a new run starts the drawer empty");
    }
}
