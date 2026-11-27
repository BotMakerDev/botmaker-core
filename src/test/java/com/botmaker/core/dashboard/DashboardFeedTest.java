// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.botmaker.core.CoreJson;
import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.server.Reply;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DashboardFeedTest {

    private final DashboardFeed feed = new DashboardFeed(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    private static CoreEvent status(int n) {
        return new CoreEvent.Status("event " + n);
    }

    private static String said(DashboardFeed.Seen seen) {
        return ((CoreEvent.Status) ((DashboardFeed.Told) seen).event()).message();
    }

    @Test
    void keepsTheLatest500() {
        for (int n = 1; n <= 600; n++) feed.told(status(n), "project");
        List<DashboardFeed.Seen> kept = feed.kept();
        assertEquals(DashboardFeed.KEPT, kept.size());
        assertEquals("event 101", said(kept.getFirst()));
        assertEquals("event 600", said(kept.getLast()));
    }

    @Test
    void watchReplaysThenLive() throws Exception {
        for (int n = 1; n <= 3; n++) feed.told(status(n), "project");
        List<String> heard = new CopyOnWriteArrayList<>();
        CountDownLatch four = new CountDownLatch(4);
        try (DashboardFeed.Watch ignored = feed.watch(seen -> {
            heard.add(said(seen));
            four.countDown();
        }, 8, () -> { })) {
            feed.told(status(4), "project");
            assertTrue(four.await(5, TimeUnit.SECONDS));
        }
        assertEquals(List.of("event 1", "event 2", "event 3", "event 4"), heard);
    }

    @Test
    void stalledWatcherIsDropped() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        CountDownLatch dropped = new CountDownLatch(1);
        DashboardFeed.Watch watch = feed.watch(seen -> {
            try {
                never.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 8, dropped::countDown);
        long start = System.nanoTime();
        for (int n = 1; n <= 50; n++) feed.told(status(n), "project");
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(1)) < 0);
        assertTrue(dropped.await(5, TimeUnit.SECONDS));
        assertFalse(watch.isOpen());
        never.countDown();
    }

    @Test
    void framesCarryNoPicture() {
        feed.told(new CoreEvent.RunFrame(new byte[100_000]), "project");
        DashboardFeed.Told told = (DashboardFeed.Told) feed.kept().getFirst();
        assertEquals(100_000, told.dropped());
        assertTrue(CoreJson.write(new Reply.Told(told.event())).length() < 200);
    }

    @Test
    void toolCallImagesAndPoolPicksAreDropped() {
        String image = "x".repeat(10_000);
        feed.told(new CoreEvent.ToolCallEnded(1, CoreEvent.ToolCallEnded.Outcome.OK,
                List.of(new CoreEvent.ToolCallEnded.Part(CoreEvent.ToolCallEnded.Part.Kind.IMAGE, image, 640, 480)),
                10, 0), "mcp");
        feed.told(new CoreEvent.RunsStarted("asked", "words",
                List.of(new CoreEvent.RunsStarted.Started(0, "run-1", Map.of("account", List.of("secret@example")),
                        List.of("plays at secret@example's place"))),
                List.of(), List.of(), false), "project");
        List<DashboardFeed.Seen> kept = feed.kept();
        String call = CoreJson.write(new Reply.Told(((DashboardFeed.Told) kept.get(0)).event()));
        String runs = CoreJson.write(new Reply.Told(((DashboardFeed.Told) kept.get(1)).event()));
        assertFalse(call.contains(image));
        assertTrue(call.contains("640"));
        assertFalse(runs.contains("secret@example"));
        assertTrue(runs.contains("run-1"));
    }
}
