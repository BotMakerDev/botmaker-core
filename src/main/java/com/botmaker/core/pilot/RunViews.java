// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.core.plugin.PluginHost;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.run.BackgroundLaunch;
import com.botmaker.plugin.api.run.RunView;
import com.botmaker.plugin.api.run.ViewTarget;
import com.botmaker.plugin.api.run.ViewedRun;
import com.botmaker.shared.Diag;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * The views the Remote Pilot asks what to show, one set per project (pilot in core, spec
 * {@code 2026-11-26-pilot-in-core-design.md}). Each loaded plugin's {@code views(...)} gives a new {@link RunView}
 * when a run starts, started with the project's services and dropped when the run ends. While no run goes, one more
 * set shows the game as the project stands: it is built at the first ask, and again once other plugins are bound.
 *
 * <p>The core alone holds one: a view is built in the process that owns the project, however many clients watch.
 * What a view shows is asked on the project's own thread, since a view reads the project's values, and the caller
 * gets the last answer without waiting. No plugin is called while the lock is held, so a slow view holds up no run's
 * start or end.
 */
public final class RunViews {

    /** How long an answer stands: the pilot asks once a frame, and a view says when it changed. */
    private static final long SHOWN_NANOS = 500_000_000L;

    private static final Shown NOTHING = new Shown(Optional.empty(), Optional.empty());

    private final Supplier<List<StudioPlugin>> plugins;
    private final StudioServices services;
    private final Executor asks;
    private final Runnable changed;

    private boolean running;
    private boolean closed;
    private List<RunView> current = List.of();
    /** The plugins the idle set came from; {@code null} while there is no idle set. */
    private List<StudioPlugin> idleOf;
    /** Moves whenever {@link #current} is replaced, so a set built across a replacement is not kept. */
    private long sets;
    /** Moves whenever the answer may have, so one computed across a change is not kept. */
    private long generation;
    private Shown shown = NOTHING;
    private Optional<BackgroundLaunch> background = Optional.empty();
    private long shownAt;
    private boolean stale = true;
    private boolean asking;

    /**
     * Over {@code plugins}, asked for each set; views are asked what they show on {@code asks}; {@code changed} is
     * called when a view says what it shows may differ.
     */
    public RunViews(Supplier<List<StudioPlugin>> plugins, StudioServices services, Executor asks, Runnable changed) {
        this.plugins = plugins;
        this.services = services;
        this.asks = asks;
        this.changed = changed;
    }

    /** Over the plugins the core has bound. */
    public static RunViews bound(StudioServices services, Executor asks, Runnable changed) {
        return new RunViews(PluginHost::instances, services, asks, changed);
    }

    /** A run started: each plugin's view of it is built and started. Told again while it goes, it builds nothing more. */
    public void started() {
        started(() -> null);
    }

    /**
     * {@link #started()}, each view told which run it is for: {@code run} is asked once, when the views are built, and
     * may answer {@code null} when the run is not named yet (the views then get the two-argument start).
     */
    public void started(Supplier<ViewedRun> run) {
        long mine;
        synchronized (this) {
            if (closed || running) return;
            running = true;
            replace(List.of(), null);
            mine = sets;
        }
        List<RunView> built = build(plugins.get(), run.get());
        synchronized (this) {
            if (mine == sets) replace(built, null);
        }
    }

    /** The run ended: its views are dropped. */
    public synchronized void ended() {
        if (!running) return;
        running = false;
        replace(List.of(), null);
    }

    /** The project closes: the views are dropped and no more are built. */
    public synchronized void close() {
        closed = true;
        running = false;
        replace(List.of(), null);
    }

    /** The views that answer now: the going run's, else the idle ones; none once closed. */
    public List<RunView> views() {
        synchronized (this) {
            if (closed || running) return current;
        }
        List<StudioPlugin> bound = plugins.get();
        long seen;
        synchronized (this) {
            if (closed || running || bound.equals(idleOf)) return current;
            seen = sets;
        }
        List<RunView> built = build(bound, null);
        synchronized (this) {
            if (seen == sets) replace(built, List.copyOf(bound));
            return current;
        }
    }

    /**
     * What is shown now: the first view that names something, else the first reason a view gave. An answer that is
     * due is asked for and the last one given meanwhile; after a change nothing is shown until the new one is in.
     */
    public Shown shown() {
        List<StudioPlugin> bound = plugins.get();
        boolean due;
        synchronized (this) {
            if (!running && idleOf != null && !bound.equals(idleOf)) replace(List.of(), null);
            due = !closed && !asking && (stale || System.nanoTime() - shownAt >= SHOWN_NANOS);
            if (due) asking = true;
        }
        if (due) {
            try {
                asks.execute(this::refresh);
            } catch (RejectedExecutionException e) {
                // The project's thread has stopped: it is closing.
                synchronized (this) {
                    asking = false;
                }
            }
        }
        synchronized (this) {
            return shown;
        }
    }

    /**
     * What the first view that has one would have started in a private display, as last asked: an answer that is due
     * is asked for on {@code asks}, and the last one given meanwhile, as {@link #shown()}.
     */
    public Optional<BackgroundLaunch> background() {
        shown();
        synchronized (this) {
            return background;
        }
    }

    /** The message the phone draws for one telemetry frame: the first view that can read it gives it. */
    public Optional<String> overlay(byte[] frame) {
        for (RunView view : views()) {
            Optional<String> message = safe(() -> view.overlay(frame));
            if (message.isPresent()) return message;
        }
        return Optional.empty();
    }

    /** What is shown: a target, or the reason there is none (both empty when no plugin gave a view). */
    public record Shown(Optional<ViewTarget> target, Optional<String> reason) {}

    private void refresh() {
        try {
            views(); // builds the idle set when one is owed
            List<RunView> asked;
            long at;
            synchronized (this) {
                asked = current;
                at = generation;
            }
            Shown answer = ask(asked);
            Optional<BackgroundLaunch> wished = asked.stream().map(view -> safe(view::background))
                    .flatMap(Optional::stream).findFirst();
            synchronized (this) {
                if (at == generation) {
                    shown = answer;
                    background = wished;
                    shownAt = System.nanoTime();
                    stale = false;
                }
            }
        } finally {
            synchronized (this) {
                asking = false;
            }
        }
    }

    private static Shown ask(List<RunView> views) {
        Optional<String> why = Optional.empty();
        for (RunView view : views) {
            Optional<ViewTarget> target = safe(view::target);
            if (target.isPresent()) return new Shown(target, Optional.empty());
            if (why.isEmpty()) why = safe(view::reason);
        }
        return why.isEmpty() ? NOTHING : new Shown(Optional.empty(), why);
    }

    private List<RunView> build(List<StudioPlugin> bound, ViewedRun run) {
        List<RunView> built = new ArrayList<>();
        for (StudioPlugin plugin : bound) {
            try {
                for (RunView view : plugin.runViews()) {
                    if (run == null) view.start(services, this::changed);
                    else view.start(services, run, this::changed);
                    built.add(view);
                }
            } catch (RuntimeException | LinkageError e) {
                // Includes a plugin built against a contract without views: it shows nothing.
                Diag.warn("Pilot", "a plugin could not give its run views: " + e);
            }
        }
        return List.copyOf(built);
    }

    /** A view said what it shows may differ. */
    private void changed() {
        synchronized (this) {
            forget();
        }
        changed.run();
    }

    /** Another set answers from now on; called with the lock held. */
    private void replace(List<RunView> views, List<StudioPlugin> idle) {
        current = views;
        idleOf = idle;
        sets++;
        forget();
    }

    /** Called with the lock held. */
    private void forget() {
        shown = NOTHING;
        stale = true;
        generation++;
    }

    private static <T> Optional<T> safe(Supplier<Optional<T>> ask) {
        try {
            return ask.get();
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }
}
