// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.botmaker.core.event.CoreEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DashboardEventsTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final DashboardFeed feed = new DashboardFeed(Clock.systemUTC());
    private final AtomicInteger asked = new AtomicInteger();
    private final Supplier<Inside> inside = () -> {
        asked.incrementAndGet();
        return inside();
    };
    private DashboardEvents events;
    private Server jetty;
    private int port;

    @AfterEach
    void stop() throws Exception {
        if (events != null) events.close();
        if (jetty != null) jetty.stop();
    }

    @Test
    void connectGivesInsideThenReplayThenLive() throws Exception {
        serve(Duration.ofMinutes(1));
        for (int i = 0; i < 3; i++) feed.told(new CoreEvent.Status("line " + i), "project");
        try (Frames frames = open()) {
            assertEquals("inside", frames.next().name());
            for (int i = 0; i < 3; i++) {
                Frame seen = frames.next();
                assertEquals("seen", seen.name());
                assertTrue(seen.data().startsWith("{\"type\":\"told\""), seen.data());
                assertTrue(seen.data().contains("\"event\":{\"event\":\"status\",\"message\":\"line " + i + "\""),
                        seen.data());
            }
            feed.client(new DashboardFeed.ClientSeen(feed.now(), "c1", "studio", true));
            Frame live = frames.next();
            assertEquals("seen", live.name());
            assertTrue(live.data().startsWith("{\"type\":\"client\""), live.data());
        }
    }

    @Test
    void insideTicksEvery2s() throws Exception {
        assertEquals(Duration.ofSeconds(2), DashboardEvents.EVERY);
        serve(Duration.ofMillis(100));
        Thread.sleep(400);
        assertEquals(0, asked.get(), "no page open, yet the core was asked what it is");
        try (Frames frames = open()) {
            Frame first = frames.next();
            assertEquals("inside", first.name());
            assertTrue(first.data().contains("\"project\":\"bot\""), first.data());
            assertTrue(first.data().contains("\"lockHolder\":null"), first.data());
            assertEquals("inside", frames.next().name());
            assertEquals("inside", frames.next().name());
        }
    }

    @Test
    void endingCoreSaysEnded() throws Exception {
        serve(Duration.ofMinutes(1));
        try (Frames frames = open()) {
            assertEquals("inside", frames.next().name());
            events.close();
            assertEquals("ended", frames.next().name());
            assertNull(frames.next(), "the stream goes on after ended");
        }
    }

    @Test
    void burstKeepsRing500() throws Exception {
        serve(Duration.ofMinutes(1));
        try (Socket stalled = new Socket("127.0.0.1", port)) {
            OutputStream out = stalled.getOutputStream();
            out.write(("GET " + DashboardAccess.PATH + "/events HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (events.pages() == 0 && System.nanoTime() < until) Thread.sleep(10);
            assertEquals(1, events.pages());
            String big = "x".repeat(2_000);
            long start = System.nanoTime();
            for (int i = 0; i < 2_000; i++) feed.told(new CoreEvent.Status(big + i), "project");
            long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(took < 2_000, "a page that reads nothing held up told for " + took + " ms");
            assertEquals(DashboardFeed.KEPT, feed.kept().size());
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (events.pages() > 0 && System.nanoTime() < until) Thread.sleep(10);
            assertEquals(0, events.pages(), "a page whose queue filled was kept");
        }
    }

    private void serve(Duration every) throws Exception {
        events = new DashboardEvents(feed, inside, every);
        jetty = new Server();
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(events), DashboardEvents.PATH);
        jetty.setHandler(context);
        ServerConnector connector = new ServerConnector(jetty);
        connector.setHost("127.0.0.1");
        jetty.addConnector(connector);
        jetty.start();
        port = connector.getLocalPort();
    }

    private Frames open() throws Exception {
        HttpResponse<InputStream> answer = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + DashboardEvents.PATH)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, answer.statusCode());
        assertEquals("text/event-stream", answer.headers().firstValue("Content-Type").orElseThrow().split(";")[0]);
        return new Frames(new BufferedReader(new InputStreamReader(answer.body(), StandardCharsets.UTF_8)));
    }

    private static Inside inside() {
        return new Inside(1, 2, 3, "1.0", false, 4, 5, 6, Map.of("core", 6), List.of(), Optional.of("bot"),
                Optional.empty(), false, List.of(), Optional.empty(), List.of(), List.of(), Optional.of(7L));
    }

    private record Frame(String name, String data) {}

    /** The frames of one event stream, read as they come. */
    private record Frames(BufferedReader reader) implements AutoCloseable {
        /** The next frame, or null at the end of the stream; fails after 5 s. */
        Frame next() throws Exception {
            var read = CompletableFuture.supplyAsync(() -> {
                try {
                    String name = null;
                    List<String> data = new ArrayList<>();
                    for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                        if (line.isEmpty()) {
                            if (name != null) return new Frame(name, String.join("\n", data));
                        } else if (line.startsWith("event: ")) {
                            name = line.substring(7);
                        } else if (line.startsWith("data: ")) {
                            data.add(line.substring(6));
                        }
                    }
                    return null;
                } catch (IOException ended) {
                    return null;
                }
            });
            return read.get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            reader.close();
        }
    }
}
