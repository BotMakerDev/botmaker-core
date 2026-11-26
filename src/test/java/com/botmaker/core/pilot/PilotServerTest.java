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
        List<StudioPlugin> bound = List.of(new RunViewsTest.Plugin());
        RunViews views = new RunViews(() -> bound, null, Runnable::run, () -> { });
        server = new PilotServer(runs, views, new PilotRoutes(() -> null, views::shown, serial -> null),
                new TargetCapture(null), new PilotControlService(runs), PairingStore.inMemory());
        return server.start("127.0.0.1");
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

        assertContains("\"type\":\"state\"", heard.poll(5, TimeUnit.SECONDS));
        assertContains("\"type\":\"input\"", heard.poll(5, TimeUnit.SECONDS));

        runs.telemetry.forEach(l -> l.accept("{\"type\":\"telemetry\"}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("{\"type\":\"telemetry\"}", heard.poll(5, TimeUnit.SECONDS), "the view writes the overlay");

        ws.sendText("{\"cmd\":\"start\"}", true).get(5, TimeUnit.SECONDS);
        assertTrue(runs.started.await(5, TimeUnit.SECONDS));
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
