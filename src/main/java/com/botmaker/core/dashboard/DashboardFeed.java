// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.dashboard;

import com.botmaker.core.event.CoreEvent;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * What the core's dashboard shows happened (spec {@code 2026-11-21-core-dashboard-design.md}): the latest {@link #KEPT}
 * things seen, kept whether or not a page watches, and each watcher told them, then each new one. Nothing a watcher does
 * holds up {@link #told}: each has a bounded queue of its own, and a watcher whose queue fills is dropped.
 *
 * <p>No picture and no pooled value is kept: a frame keeps its size, a tool call's image its width and height, a start
 * of several runs its rows and run ids.
 */
public final class DashboardFeed {

    /** How many things seen are kept for a page that opens later. */
    public static final int KEPT = 500;

    /** One thing the dashboard saw, at {@code atMillis} since the epoch. */
    public sealed interface Seen permits Told, EditSeen, ClientSeen {
        long atMillis();
    }

    /**
     * An event the core told: {@code source} {@code project} or {@code mcp}; {@code dropped} the bytes of picture left
     * out.
     */
    public record Told(long atMillis, String source, CoreEvent event, int dropped) implements Seen {}

    /**
     * An edit a client asked for: its request's kind, whether it was accepted, the file it named (empty when it named
     * none), the methods it changed, how long it took, and a refusal's reason or a together's label.
     */
    public record EditSeen(long atMillis, String client, String kind, boolean accepted, String file,
                           List<String> methods, long tookMillis, String label) implements Seen {
        public EditSeen {
            file = file == null ? "" : file;
            methods = methods == null ? List.of() : List.copyOf(methods);
            label = label == null ? "" : label;
        }
    }

    /** A client came ({@code opened}) or went; {@code kind} is {@code studio} for a channel. */
    public record ClientSeen(long atMillis, String id, String kind, boolean opened) implements Seen {}

    /** One watcher's tie to the feed; closing it stops it. */
    public final class Watch implements AutoCloseable {
        private final BlockingQueue<Seen> queue;
        private final Runnable dropped;
        private final AtomicBoolean open = new AtomicBoolean(true);
        private volatile Thread drainer;

        private Watch(int size, Runnable dropped) {
            this.queue = new ArrayBlockingQueue<>(size);
            this.dropped = dropped;
        }

        public boolean isOpen() {
            return open.get();
        }

        private void offer(Seen seen) {
            if (open.get() && !queue.offer(seen)) {
                close();
                dropped.run();
            }
        }

        private void drain(List<Seen> replay, Consumer<Seen> watcher) {
            drainer = Thread.currentThread();
            try {
                for (Seen seen : replay) {
                    if (!open.get()) return;
                    watcher.accept(seen);
                }
                while (open.get()) watcher.accept(queue.take());
            } catch (InterruptedException ended) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            if (!open.compareAndSet(true, false)) return;
            watchers.remove(this);
            Thread thread = drainer;
            if (thread != null) thread.interrupt();
        }
    }

    private final Clock clock;
    private final ArrayDeque<Seen> kept = new ArrayDeque<>(KEPT);
    private final List<Watch> watchers = new CopyOnWriteArrayList<>();

    public DashboardFeed(Clock clock) {
        this.clock = clock;
    }

    /** {@code event}, told by {@code source}, with its pictures and pooled values left out. */
    public void told(CoreEvent event, String source) {
        int[] dropped = {0};
        CoreEvent kept = withoutPictures(event, dropped);
        seen(new Told(clock.millis(), source, kept, dropped[0]));
    }

    public void edit(EditSeen seen) {
        seen(seen);
    }

    public void client(ClientSeen seen) {
        seen(seen);
    }

    /** The current time of this feed's clock, for a caller building a {@link Seen}. */
    public long now() {
        return clock.millis();
    }

    /** The kept things seen, oldest first. */
    public List<Seen> kept() {
        synchronized (kept) {
            return List.copyOf(kept);
        }
    }

    /**
     * {@code watcher} hears every kept thing seen, oldest first, then each new one, on a thread of its own, until the
     * returned watch is closed; when more than {@code queue} wait for it, it is dropped and {@code dropped} runs.
     */
    public Watch watch(Consumer<Seen> watcher, int queue, Runnable dropped) {
        Watch watch = new Watch(queue, dropped);
        List<Seen> replay;
        synchronized (kept) {
            replay = List.copyOf(kept);
            watchers.add(watch);
        }
        Thread.ofVirtual().name("dashboard-watch").start(() -> watch.drain(replay, watcher));
        return watch;
    }

    private void seen(Seen seen) {
        synchronized (kept) {
            if (kept.size() == KEPT) kept.removeFirst();
            kept.addLast(seen);
            for (Watch watch : watchers) watch.offer(seen);
        }
    }

    private static CoreEvent withoutPictures(CoreEvent event, int[] dropped) {
        return switch (event) {
            case CoreEvent.RunFrame frame -> {
                dropped[0] = frame.payload().length;
                yield new CoreEvent.RunFrame(new byte[0]);
            }
            case CoreEvent.ToolCallEnded ended -> {
                List<CoreEvent.ToolCallEnded.Part> parts = new ArrayList<>();
                for (CoreEvent.ToolCallEnded.Part part : ended.reply()) {
                    if (part.kind() == CoreEvent.ToolCallEnded.Part.Kind.IMAGE) {
                        dropped[0] += part.value().length();
                        parts.add(new CoreEvent.ToolCallEnded.Part(part.kind(), "", part.width(), part.height()));
                    } else {
                        parts.add(part);
                    }
                }
                yield new CoreEvent.ToolCallEnded(ended.call(), ended.outcome(), parts, ended.millis(),
                        ended.elsewhere(), ended.extras());
            }
            case CoreEvent.RunsStarted started -> new CoreEvent.RunsStarted(started.asked(), started.words(),
                    started.runs().stream()
                            .map(run -> new CoreEvent.RunsStarted.Started(run.row(), run.runId(), Map.of(),
                                    List.of()))
                            .toList(),
                    started.warnings(), started.offers(), started.counted());
            default -> event;
        };
    }
}
