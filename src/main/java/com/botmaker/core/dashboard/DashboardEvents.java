// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.dashboard;

import com.botmaker.core.CoreJson;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The dashboard's event stream at {@link #PATH} (spec {@code 2026-11-21-core-dashboard-design.md}): server-sent events
 * {@code inside} (an {@link Inside}), {@code seen} (a {@link DashboardFeed.Seen}, its {@code type} {@code told},
 * {@code edit} or {@code client}) and {@code ended} (the core is ending). A page hears one {@code inside}, then every
 * kept {@code seen}, then each new one and an {@code inside} every {@link #EVERY}; the core is asked what it is only while
 * a page is open.
 *
 * <p>Each page has a writer of its own and a queue of {@link #QUEUE} frames: a page that reads too slowly is dropped, and
 * nothing it does holds up the core.
 */
public final class DashboardEvents extends HttpServlet implements AutoCloseable {

    public static final String PATH = DashboardAccess.PATH + "/events";
    public static final Duration EVERY = Duration.ofSeconds(2);
    /** How many frames wait for one page before it is dropped. */
    static final int QUEUE = 1000;

    /** An event's own JSON is {@link CoreJson}'s; the rest is plain, an empty {@link Optional} written as null. */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new SimpleModule().addSerializer(new OptionalWriter())).build();
    private static final String ENDED = frame("ended", "{}");
    /** Not a frame: tells a page's writer it has written its last. */
    private static final String LAST = "";

    private final DashboardFeed feed;
    private final Supplier<Inside> inside;
    private final Set<Page> pages = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService ticks = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread tick = new Thread(r, "dashboard-tick");
        tick.setDaemon(true);
        return tick;
    });

    /**
     * @param inside what the core is now; null from a server with no core behind it (a test's), which sends no
     *               {@code inside}
     */
    public DashboardEvents(DashboardFeed feed, Supplier<Inside> inside) {
        this(feed, inside, EVERY);
    }

    DashboardEvents(DashboardFeed feed, Supplier<Inside> inside, Duration every) {
        this.feed = feed;
        this.inside = inside;
        ticks.scheduleAtFixedRate(this::tick, every.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** The pages streaming now. */
    public int pages() {
        return pages.size();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (closed.get()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "The core is ending");
            return;
        }
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        AsyncContext async = request.startAsync();
        async.setTimeout(0);
        response.flushBuffer();
        Page page = new Page(async, response.getOutputStream());
        async.addListener(page);
        pages.add(page);
        insideFrame().ifPresent(page::offer);
        page.watching(feed.watch(seen -> seenFrame(seen).ifPresent(page::offer), QUEUE, page::drop));
        Thread.ofVirtual().name("dashboard-page").start(page::write);
        if (closed.get()) page.ended();
    }

    /** Tells every page {@code ended} and waits a moment for each to write it. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        ticks.shutdownNow();
        List<Page> open = List.copyOf(pages);
        open.forEach(Page::ended);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        for (Page page : open) {
            try {
                if (!page.done.await(Math.max(0, until - System.nanoTime()), TimeUnit.NANOSECONDS)) page.drop();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void tick() {
        if (pages.isEmpty()) return;
        insideFrame().ifPresent(frame -> pages.forEach(page -> page.offer(frame)));
    }

    private Optional<String> insideFrame() {
        try {
            return Optional.ofNullable(inside.get()).map(found -> frame("inside", write(found)));
        } catch (RuntimeException unread) {
            // What the core is could not be read or written this time: the page keeps the last it heard.
            return Optional.empty();
        }
    }

    /** {@code seen}'s frame; empty for one that cannot be written, which the page never hears of. */
    private static Optional<String> seenFrame(DashboardFeed.Seen seen) {
        try {
            return Optional.of(frame("seen", seen(seen)));
        } catch (RuntimeException unwritable) {
            return Optional.empty();
        }
    }

    /** {@code seen} as one JSON object, its {@code type} first. */
    static String seen(DashboardFeed.Seen seen) {
        ObjectNode node = JSON.createObjectNode();
        switch (seen) {
            case DashboardFeed.Told told -> {
                node.put("type", "told");
                node.put("atMillis", told.atMillis());
                node.put("source", told.source());
                node.put("dropped", told.dropped());
                try {
                    node.set("event", JSON.readTree(CoreJson.write(told.event())));
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("CoreJson wrote what it cannot read back", e);
                }
            }
            case DashboardFeed.EditSeen edit -> {
                node.put("type", "edit");
                node.setAll((ObjectNode) JSON.valueToTree(edit));
            }
            case DashboardFeed.ClientSeen client -> {
                node.put("type", "client");
                node.setAll((ObjectNode) JSON.valueToTree(client));
            }
        }
        return write(node);
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not writable: " + value.getClass().getName(), e);
        }
    }

    private static String frame(String event, String json) {
        return "event: " + event + "\ndata: " + json + "\n\n";
    }

    /** One page's stream: its frames wait in a queue its own writer empties. */
    private final class Page implements AsyncListener {
        private final AsyncContext async;
        private final ServletOutputStream out;
        private final BlockingQueue<String> frames = new ArrayBlockingQueue<>(QUEUE + 2);
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile DashboardFeed.Watch watch;
        private volatile Thread writer;

        Page(AsyncContext async, ServletOutputStream out) {
            this.async = async;
            this.out = out;
        }

        /** Queues {@code frame}; a page whose queue is full is dropped. */
        synchronized void offer(String frame) {
            if (open.get() && frames.size() >= QUEUE) {
                drop();
            } else if (open.get()) {
                frames.add(frame);
            }
        }

        /** Its last frame is {@code ended}, whatever still waited. */
        synchronized void ended() {
            if (!open.compareAndSet(true, false)) return;
            stopWatching();
            frames.clear();
            frames.add(ENDED);
            frames.add(LAST);
        }

        /** Ends the stream without a word: the page went, or read too slowly. */
        void drop() {
            synchronized (this) {
                if (open.compareAndSet(true, false)) stopWatching();
                frames.clear();
            }
            Thread thread = writer;
            if (thread != null && thread != Thread.currentThread()) thread.interrupt();
            done.countDown();
        }

        /** Keeps {@code watching}, or closes it when the page already ended while it was being made. */
        synchronized void watching(DashboardFeed.Watch watching) {
            if (open.get()) {
                watch = watching;
            } else {
                watching.close();
            }
        }

        private void stopWatching() {
            pages.remove(this);
            DashboardFeed.Watch watching = watch;
            if (watching != null) watching.close();
        }

        void write() {
            writer = Thread.currentThread();
            try {
                while (open.get() || !frames.isEmpty()) {
                    String frame = frames.take();
                    if (frame.equals(LAST)) break;
                    out.write(frame.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException | InterruptedException gone) {
                // The page went, or was dropped: its stream ends here.
            } finally {
                drop();
                try {
                    async.complete();
                } catch (IllegalStateException alreadyEnded) {
                    // The stream ended from the page's side first.
                }
            }
        }

        @Override
        public void onComplete(AsyncEvent event) {
            drop();
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            drop();
        }

        @Override
        public void onError(AsyncEvent event) {
            drop();
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            // A page's stream is started once.
        }
    }

    /** An empty {@link Optional} as null, a present one as its value. */
    @SuppressWarnings("rawtypes")
    private static final class OptionalWriter extends StdSerializer<Optional> {
        OptionalWriter() {
            super(Optional.class);
        }

        @Override
        public void serialize(Optional value, JsonGenerator out, SerializerProvider provider) throws IOException {
            if (value.isPresent()) {
                provider.defaultSerializeValue(value.get(), out);
            } else {
                out.writeNull();
            }
        }
    }
}
