// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.core.plugin.PluginHost;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.run.RunView;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The views of the run that goes, one set per project (pilot in core, spec {@code 2026-11-26-pilot-in-core-design.md}):
 * each loaded plugin's {@code views(...)} gives a new {@link RunView} when a run starts, each is started with the
 * project's services, and all are dropped when the run ends. The Remote Pilot asks them what to show and turns each
 * telemetry frame into the message the phone draws.
 *
 * <p>The core alone holds one: a view is built in the process that owns the project, however many clients watch.
 */
public final class RunViews {

    private final Supplier<List<StudioPlugin>> plugins;
    private final StudioServices services;
    private final Runnable changed;
    private List<RunView> going = List.of();

    /** Over {@code plugins}, asked at each start; {@code changed} is called when a view says what it shows may differ. */
    public RunViews(Supplier<List<StudioPlugin>> plugins, StudioServices services, Runnable changed) {
        this.plugins = plugins;
        this.services = services;
        this.changed = changed;
    }

    /** Over the plugins the core has bound. */
    public static RunViews bound(StudioServices services, Runnable changed) {
        return new RunViews(PluginHost::instances, services, changed);
    }

    /** A run started: each plugin's view of it is built and started. Told again while it goes, it builds nothing more. */
    public synchronized void started() {
        if (!going.isEmpty()) return;
        List<RunView> built = new ArrayList<>();
        for (StudioPlugin plugin : plugins.get()) {
            try {
                for (RunView view : plugin.runViews()) {
                    view.start(services, changed);
                    built.add(view);
                }
            } catch (RuntimeException | LinkageError e) {
                // Includes a plugin built against a contract without views: it shows nothing.
                System.err.println("Warning: a plugin could not give its run views: " + e);
            }
        }
        going = List.copyOf(built);
    }

    /** The run ended, or the project closes: the views are dropped. */
    public synchronized void ended() {
        going = List.of();
    }

    /** The views of the run that goes; none while no run does. */
    public synchronized List<RunView> views() {
        return going;
    }

    /** What the run shows now: the first view that names something, else the first reason a view gave. */
    public synchronized Shown shown() {
        Optional<String> why = Optional.empty();
        for (RunView view : going) {
            Optional<com.botmaker.plugin.api.run.ViewTarget> target = safe(view::target);
            if (target.isPresent()) return new Shown(target, Optional.empty());
            if (why.isEmpty()) why = safe(view::reason);
        }
        return new Shown(Optional.empty(), why);
    }

    /** The message the phone draws for one telemetry frame: the first view that can read it gives it. */
    public synchronized Optional<String> overlay(byte[] frame) {
        for (RunView view : going) {
            Optional<String> message = safe(() -> view.overlay(frame));
            if (message.isPresent()) return message;
        }
        return Optional.empty();
    }

    /** What the run shows: a target, or the reason there is none (both empty when no plugin gave a view). */
    public record Shown(Optional<com.botmaker.plugin.api.run.ViewTarget> target, Optional<String> reason) {}

    private static <T> Optional<T> safe(Supplier<Optional<T>> ask) {
        try {
            return ask.get();
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }
}
