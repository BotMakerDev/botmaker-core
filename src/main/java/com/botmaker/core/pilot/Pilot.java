// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.core.pilot.transport.FunnelTransport;
import com.botmaker.core.pilot.transport.PilotTransport;
import com.botmaker.core.pilot.transport.TailnetPhones;
import com.botmaker.core.pilot.transport.TransportKind;
import com.botmaker.plugin.api.Runs;
import com.botmaker.shared.Diag;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The core's Remote Pilot: one per process, off until a client starts it. It brings a {@link PilotServer} up over the
 * way the user picked, takes it down, and says what it is as a {@link PilotState}.
 *
 * <p>Bringing it up asks other programs and can take half a minute, so it runs on a thread of its own, and a
 * {@link #stop} never waits for it: every start and stop takes a new <em>generation</em>, and a bring-up that finds
 * itself of an old one closes what it made. No server is closed under {@link #lock}, which its phones' callbacks
 * must never wait on.
 *
 * <p>A way that fails is replaced by a direct one, never by a public one: putting the pilot on the internet is
 * something only the user picks.
 */
public final class Pilot implements AutoCloseable {

    /** The open project the pilot shows and drives. */
    public record Served(Runs runs, RunViews views, Path resourcesRoot, PilotRuns runList) {

        /** A project that lists no registry runs. */
        public Served(Runs runs, RunViews views, Path resourcesRoot) {
            this(runs, views, resourcesRoot, PilotRuns.NONE);
        }
    }

    /** What reaches outside this process, so a test brings the pilot up with none of it. */
    record Outside(Function<TransportKind, PilotTransport> transports,
                   Function<Served, PilotServer> servers,
                   Supplier<List<TailnetPhones.Phone>> tailnet,
                   Runnable leftover) {}

    /** No generation in particular. */
    private static final long ANY = -1;

    private final PilotPreferences preferences;
    private final Outside outside;
    private final ExecutorService bringUps = daemon("pilot-bringup");
    private final ExecutorService publisher = daemon("pilot-state");
    private final List<Consumer<PilotState>> watchers = new CopyOnWriteArrayList<>();

    private final Object lock = new Object();
    /** Which start or stop is the current one. Guarded by {@link #lock}, as every field below. */
    private long generation;
    /** The state without what is read at each look: the pairing address, its code and the connected phones. */
    private PilotState base;
    /** The server of a pilot that is on. */
    private PilotServer server;
    /** The server of a bring-up whose way to it is still opening. */
    private PilotServer pending;
    /** Servers no longer the pilot's that are not closed yet, which a stop closes itself rather than leave open. */
    private final Set<PilotServer> retiring = new LinkedHashSet<>();
    /** Who waits for the start under way to end. */
    private final List<CompletableFuture<PilotState>> waiting = new ArrayList<>();
    private boolean closed;
    private volatile PilotBackground background;

    public Pilot(PilotPreferences preferences) {
        this(preferences, new Outside(
                kind -> PilotTransport.of(kind, preferences),
                served -> PilotServer.forProject(served.runs(), served.views(), served.resourcesRoot(), served.runList(),
                        preferences),
                TailnetPhones::probe,
                () -> FunnelTransport.clearLeftover(preferences)));
    }

    Pilot(PilotPreferences preferences, Outside outside) {
        this.preferences = preferences;
        this.outside = outside;
        this.base = off(preferences.transport(), null, null, null, List.of(), List.of());
    }

    /** The pilot now. */
    public PilotState state() {
        PilotState now;
        PilotServer on;
        synchronized (lock) {
            now = base;
            on = server;
        }
        PilotBackground modes = background;
        PilotState.Background mode = modes == null ? PilotState.Background.NONE : modes.status();
        if (on == null || now.phase() != PilotState.Phase.ON) return now.with(mode);
        String url = now.baseUrl() + "/?token=" + on.token();
        return new PilotState(now.phase(), now.asked(), now.kind(), now.baseUrl(), url, PilotQr.rows(url),
                now.problem(), now.fix(), now.funnel(), now.options(), on.connected(), now.tailnet(), mode);
    }

    /**
     * Background mode of the open project, which {@link #state()} tells; {@code null} when no project is open. The
     * mode calls {@link #refresh()} when what it shows differs.
     */
    public void background(PilotBackground background) {
        this.background = background;
        changed();
    }

    /** Starts the open project's Background mode; the reason when it cannot, empty when it is under way. */
    public Optional<String> startBackground() {
        PilotBackground modes = background;
        return modes == null ? Optional.of("No project is open: open_project opens one.") : modes.start();
    }

    /** Stops Background mode. */
    public void stopBackground() {
        PilotBackground modes = background;
        if (modes != null) modes.stop();
    }

    /** Tells the watchers the state again, after something outside the pilot changed what it shows. */
    public void refresh() {
        changed();
    }

    /** Whether the pilot is on or coming up, and so must keep the core running. */
    public boolean busy() {
        synchronized (lock) {
            return base.phase() != PilotState.Phase.OFF;
        }
    }

    /** Tells {@code watcher} the state now and after each change, on one thread, until closed. */
    public AutoCloseable watch(Consumer<PilotState> watcher) {
        watchers.add(watcher);
        PilotState now = state();
        later(publisher, () -> tell(watcher, now));
        return () -> watchers.remove(watcher);
    }

    /**
     * Brings the pilot up over {@code kind}, remembered from now on, or over the remembered way when null. Already on
     * that way, it stays as it is: a rebind would drop the paired phones.
     *
     * @return the state once this start, or one that replaced it, ended: on, or off with why
     */
    public CompletableFuture<PilotState> start(TransportKind kind, Served served) {
        CompletableFuture<PilotState> ended = new CompletableFuture<>();
        synchronized (lock) {
            if (closed) {
                ended.complete(base);
                return ended;
            }
            TransportKind asked = kind == null || kind == TransportKind.UNKNOWN ? preferences.transport() : kind;
            boolean same = base.asked() == asked;
            if (same && base.phase() == PilotState.Phase.STARTING) {
                waiting.add(ended);
                return ended;
            }
            if (!(same && base.phase() == PilotState.Phase.ON)) {
                if (kind != null && kind != TransportKind.UNKNOWN) preferences.transport(kind);
                long mine = ++generation;
                List<PilotServer> old = take();
                base = new PilotState(PilotState.Phase.STARTING, asked, null, null, null, null, null, null, null,
                        base.options(), null, null);
                waiting.add(ended);
                bringUps.execute(() -> bringUp(mine, asked, served, old));
                changed();
                return ended;
            }
        }
        ended.complete(state());
        return ended;
    }

    /** Takes the pilot down. When this returns nothing listens for a phone. */
    public void stop() {
        halt(null, ANY);
    }

    /** Disconnects the phone {@code id} of {@link PilotState#phones}; it may pair again. */
    public void disconnect(String id) {
        PilotServer on = current();
        if (on != null) on.disconnect(id);
    }

    /** Revokes the pairing token: every phone is disconnected and has to scan the new code. */
    public void resetToken() {
        PilotServer on = current();
        if (on == null) {
            preferences.token(null); // minted afresh by the next start
        } else {
            on.resetToken();
        }
        changed();
    }

    /** Turns off, in the background, a Funnel that a process now gone left up. */
    public void clearLeftover() {
        later(bringUps, outside.leftover());
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
        }
        stop();
        bringUps.shutdown();
        publisher.shutdown();
        try {
            // A bring-up still opening a tunnel closes it when it returns; the transports' hooks cover an exit first.
            if (!bringUps.awaitTermination(5, TimeUnit.SECONDS)) {
                Diag.warn("Pilot", "The Remote Pilot's start did not end within 5 s.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The ways tried for {@code asked}, in order: the one asked for, then the direct ones. A public way is never a
     * fallback.
     */
    static List<TransportKind> attempts(TransportKind asked) {
        Set<TransportKind> order = new LinkedHashSet<>();
        order.add(asked == TransportKind.UNKNOWN ? TransportKind.TAILNET : asked);
        order.add(TransportKind.TAILNET);
        order.add(TransportKind.LAN);
        return List.copyOf(order);
    }

    private void bringUp(long mine, TransportKind asked, Served served, List<PilotServer> old) {
        try {
            retire(old);
            if (stale(mine)) return;
            outside.leftover().run();
            Map<TransportKind, PilotTransport> transports = new EnumMap<>(TransportKind.class);
            Map<TransportKind, PilotTransport.Availability> usable = new EnumMap<>(TransportKind.class);
            List<PilotState.Option> options = new ArrayList<>();
            for (TransportKind kind : TransportKind.offered()) {
                PilotTransport transport = outside.transports().apply(kind);
                PilotTransport.Availability availability = transport.available();
                transports.put(kind, transport);
                usable.put(kind, availability);
                options.add(new PilotState.Option(kind, availability.ok(), availability.reason(), availability.fix()));
            }
            List<PilotState.TailnetPhone> tailnet = asked == TransportKind.TAILNET ? tailnetPhones() : List.of();

            String problem = null;
            String fix = null;
            for (TransportKind kind : attempts(asked)) {
                if (stale(mine)) return;
                PilotTransport.Availability availability = usable.get(kind);
                if (!availability.ok()) {
                    if (kind == asked) {
                        problem = availability.reason();
                        fix = availability.fix();
                    }
                    continue;
                }
                String failed = attempt(mine, asked, kind, transports.get(kind), served,
                        new Findings(problem, fix, funnelDiag(asked, transports), options, tailnet));
                if (failed == null) return;
                if (kind == asked) {
                    problem = failed;
                    fix = null;
                }
            }
            settle(mine, off(asked, problem != null ? problem : "This computer has no network a phone can reach.",
                    fix, funnelDiag(asked, transports), options, tailnet));
        } catch (RuntimeException e) {
            Diag.error("[Pilot] the Remote Pilot could not start: " + e);
            settle(mine, off(asked, "The Remote Pilot could not start: " + e.getMessage(), null, null, List.of(),
                    List.of()));
        }
    }

    /** What a bring-up found before the way that worked. */
    private record Findings(String problem, String fix, FunnelTransport.Diag funnel, List<PilotState.Option> options,
                            List<PilotState.TailnetPhone> tailnet) {}

    /**
     * One way tried: a server bound for it, then the way opened onto it.
     *
     * @return why it failed, or null when there is nothing more to try: the pilot is on, or this start is stale
     */
    private String attempt(long mine, TransportKind asked, TransportKind kind, PilotTransport transport,
                           Served served, Findings found) {
        PilotServer built = outside.servers().apply(served);
        built.onPhonesChanged(this::changed);
        PilotServer.Endpoint endpoint = null;
        PilotTransport.Opened opened = null;
        boolean live = false;
        try {
            synchronized (lock) {
                // Under the lock, so a stop either finds this server and closes it, or it is never started.
                if (generation != mine) return null;
                pending = built;
                endpoint = built.start(transport.bindHost());
            }
            opened = transport.open(endpoint.port());
        } catch (IllegalStateException cannotListen) {
            opened = PilotTransport.Opened.failed(cannotListen.getMessage());
        } finally {
            List<CompletableFuture<PilotState>> done = List.of();
            synchronized (lock) {
                if (pending == built) pending = null;
                if (generation == mine && opened != null && opened.ok() && built.attach(transport)) {
                    live = true;
                    server = built;
                    base = new PilotState(PilotState.Phase.ON, asked, kind, opened.baseUrl(), null, null,
                            found.problem(), found.fix(), found.funnel(), found.options(), null, found.tailnet());
                    done = drain();
                }
            }
            if (!live) {
                transport.close();
                retire(List.of(built));
            } else {
                // A way that stops by itself leaves an address that is dead.
                transport.lost().thenRun(() -> later(bringUps,
                        () -> halt(kind.displayName() + " stopped. Start the Remote Pilot again.", mine)));
                changed();
                complete(done);
            }
        }
        if (live || stale(mine)) return null;
        return opened.error() != null ? opened.error() : kind.displayName() + " did not start.";
    }

    /** Takes the pilot down, saying {@code why}; only if {@code only} is the current generation, or {@link #ANY}. */
    private void halt(String why, long only) {
        List<PilotServer> old;
        List<CompletableFuture<PilotState>> done;
        synchronized (lock) {
            if (only != ANY && generation != only) return;
            generation++;
            old = take();
            base = off(base.asked(), why, null, null, base.options(), List.of());
            done = drain();
        }
        retire(old);
        changed();
        complete(done);
    }

    private void settle(long mine, PilotState ended) {
        List<CompletableFuture<PilotState>> done;
        synchronized (lock) {
            if (generation != mine) return;
            base = ended;
            done = drain();
        }
        changed();
        complete(done);
    }

    /**
     * Makes the servers up or coming up no longer the pilot's. Called under {@link #lock}; the caller hands what is
     * returned, every server still to close, to {@link #retire} outside it.
     */
    private List<PilotServer> take() {
        if (server != null) retiring.add(server);
        if (pending != null) retiring.add(pending);
        server = null;
        pending = null;
        return List.copyOf(retiring);
    }

    /** Closes {@code servers}: a second closer of one waits for the first. */
    private void retire(List<PilotServer> servers) {
        for (PilotServer closing : servers) {
            closing.close();
            synchronized (lock) {
                retiring.remove(closing);
            }
        }
    }

    private List<CompletableFuture<PilotState>> drain() {
        List<CompletableFuture<PilotState>> done = List.copyOf(waiting);
        waiting.clear();
        return done;
    }

    private void complete(List<CompletableFuture<PilotState>> done) {
        if (done.isEmpty()) return;
        PilotState now = state();
        done.forEach(future -> future.complete(now));
    }

    private boolean stale(long mine) {
        synchronized (lock) {
            return generation != mine;
        }
    }

    private PilotServer current() {
        synchronized (lock) {
            return server != null ? server : pending;
        }
    }

    private List<PilotState.TailnetPhone> tailnetPhones() {
        return outside.tailnet().get().stream()
                .map(phone -> new PilotState.TailnetPhone(phone.name(), phone.online(),
                        phone.online() ? 0 : phone.lastSeen().map(seen -> Math.max(0, seen.toEpochMilli())).orElse(0L)))
                .toList();
    }

    private static FunnelTransport.Diag funnelDiag(TransportKind asked,
                                                   Map<TransportKind, PilotTransport> transports) {
        return asked == TransportKind.FUNNEL && transports.get(TransportKind.FUNNEL) instanceof FunnelTransport funnel
                ? funnel.diag() : null;
    }

    private static PilotState off(TransportKind asked, String problem, String fix, FunnelTransport.Diag funnel,
                                  List<PilotState.Option> options, List<PilotState.TailnetPhone> tailnet) {
        return new PilotState(PilotState.Phase.OFF, asked, null, null, null, null, problem, fix, funnel, options, null,
                tailnet);
    }

    private void changed() {
        later(publisher, () -> {
            if (watchers.isEmpty()) return;
            PilotState now = state();
            watchers.forEach(watcher -> tell(watcher, now));
        });
    }

    private static void tell(Consumer<PilotState> watcher, PilotState state) {
        try {
            watcher.accept(state);
        } catch (RuntimeException e) {
            Diag.error("[Pilot] a client could not be told the Remote Pilot's state: " + e.getMessage());
        }
    }

    private static void later(ExecutorService on, Runnable work) {
        try {
            on.execute(work);
        } catch (RejectedExecutionException closing) {
            // The pilot is closed: nothing is left to do or to tell.
        }
    }

    private static ExecutorService daemon(String name) {
        return Executors.newSingleThreadExecutor(work -> {
            Thread thread = new Thread(work, name);
            thread.setDaemon(true);
            return thread;
        });
    }
}
