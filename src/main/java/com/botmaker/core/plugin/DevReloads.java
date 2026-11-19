// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.plugin;

import com.botmaker.plugin.api.run.DevReload;
import com.botmaker.plugin.api.run.RunEntry;
import com.botmaker.plugin.host.LoadedPlugin;
import com.botmaker.plugin.host.PluginCoordinate;
import com.botmaker.plugin.host.PluginLoader;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * The host's side of {@code StudioServices.devReload} (2026-11-19): a plugin under development restarts its own run on
 * its rebuilt jar. The checks come first, on the caller's thread: the project is in dev mode, the caller is a bound
 * plugin, and its rebuilt jar, loaded on a loader of its own beside the bound plugins, constructs and declares the
 * entry. Only then is the answer {@link DevReload.Started}, and, off the caller's thread, the run is stopped, the
 * plugins bound again over the same paths (what <i>Reload plugins</i> does) and the entry started. A jar that does not
 * load is refused with the loader's words and leaves the run as it was.
 *
 * <p>Installed per open project, static, one at a time, as {@link HostRuns} is: between projects every reload is
 * {@link DevReload#REFUSED_HERE}.
 */
public final class DevReloads {

    /** How long the restart waits for the run to end before it gives up, reloading nothing. */
    static final Duration STOP_WITHIN = Duration.ofSeconds(30);

    /** What the open project does for a reload. */
    public interface Project {
        /** Whether the project is in dev mode, so its {@code -SNAPSHOT} plugin jars bind. */
        boolean devMode();

        /** The classpath the plugins are bound from now. */
        List<String> classpath();

        /** Whether a run, its compile included, has not ended. */
        boolean running();

        /** Stops the run, as Stop does. */
        void stop();

        /** Resolves the libraries and binds the plugins again, as <i>Reload plugins</i> does, and says what bound. */
        Bound rebind();

        /** Starts the entry {@code title} with main class {@code mainClass}, with no arguments, as its button does. */
        void start(String title, String mainClass);

        /** One line in the status bar. */
        void status(String message);
    }

    /** Loads {@code classpath}'s plugins beside the bound ones, says what loaded, and lets them go. */
    interface Trial {
        Bound load(List<String> classpath);
    }

    /**
     * What a load gave: each plugin constructed, with the runs it declares, and what did not load, a line each.
     *
     * @param plugins  the plugins constructed
     * @param failures what did not load, {@code <provider> — <cause>}
     */
    public record Bound(List<Served> plugins, List<String> failures) {

        public Bound {
            plugins = List.copyOf(plugins);
            failures = List.copyOf(failures);
        }

        /** {@code loaded} with its runs read, beside {@code failures} described. */
        public static Bound of(List<LoadedPlugin> loaded, List<PluginLoader.PluginFailure> failures) {
            return new Bound(loaded.stream().map(Served::of).toList(),
                    failures.stream().map(PluginLoader.PluginFailure::describe).toList());
        }
    }

    /** A plugin that loaded and the runs it declares; none when it could not list them. */
    public record Served(PluginCoordinate coordinate, String name, List<RunEntry> runs) {

        public Served {
            runs = List.copyOf(runs);
        }

        static Served of(LoadedPlugin plugin) {
            List<RunEntry> runs;
            try {
                runs = plugin.plugin().runEntries();
            } catch (RuntimeException | LinkageError e) {
                runs = List.of();
            }
            return new Served(plugin.coordinate(), plugin.name(), runs == null ? List.of() : runs);
        }
    }

    private static volatile Project current;

    private DevReloads() {}

    /** Makes {@code project} the one a reload stops, binds and starts; null takes it away. */
    public static void install(Project project) {
        current = project;
    }

    /** No project: every reload is refused here. */
    public static void clear() {
        current = null;
    }

    /** The reload {@code caller} asks for, of its run {@code entry}, on the open project; refused with none open. */
    public static DevReload reload(Optional<LoadedPlugin> caller, RunEntry entry) {
        Project project = current;
        if (project == null) return DevReload.REFUSED_HERE;
        return reload(project, caller.map(LoadedPlugin::coordinate), entry, DevReloads::trial, work -> {
            Thread thread = new Thread(work, "dev-reload");
            thread.setDaemon(true);
            thread.start();
        }, STOP_WITHIN);
    }

    static DevReload reload(Project project, Optional<PluginCoordinate> caller, RunEntry entry, Trial trial,
                            Executor restart, Duration stopWithin) {
        if (entry == null) return new DevReload.Refused("No run was named to start again.");
        if (!project.devMode()) {
            return new DevReload.Refused("Dev mode is off for this project: only a dev-mode project binds the local "
                    + "-SNAPSHOT build a reload would load. Turn it on in Plugins & Libraries.");
        }
        if (caller.isEmpty()) return new DevReload.Refused("Only a bound plugin reloads itself.");
        if (!RESTARTING.compareAndSet(false, true)) {
            return new DevReload.Refused("A reload is under way already: wait for its run.");
        }
        try {
            Optional<String> refused = refusal(trial.load(project.classpath()), caller.get(), entry);
            if (refused.isPresent()) {
                RESTARTING.set(false);
                return new DevReload.Refused(refused.get() + " Nothing was stopped.");
            }
            restart.execute(() -> {
                try {
                    restart(project, caller.get(), entry, stopWithin);
                } finally {
                    RESTARTING.set(false);
                }
            });
        } catch (RuntimeException | LinkageError e) {
            RESTARTING.set(false);
            throw e;
        }
        return new DevReload.Started();
    }

    /** One reload at a time: a second asked while the first stops, binds and starts is refused. */
    private static final AtomicBoolean RESTARTING = new AtomicBoolean();

    /** Why {@code bound} would not start {@code coordinate}'s {@code entry}; empty when it would. */
    static Optional<String> refusal(Bound bound, PluginCoordinate coordinate, RunEntry entry) {
        Optional<Served> plugin = bound.plugins().stream().filter(p -> PluginHost.same(p.coordinate(), coordinate))
                .findFirst();
        if (plugin.isEmpty()) {
            String why = bound.failures().stream().collect(Collectors.joining("; "));
            return Optional.of(coordinate.groupId() + ":" + coordinate.artifactId() + " did not load"
                    + (why.isEmpty() ? "." : ": " + why + "."));
        }
        if (!plugin.get().runs().contains(entry)) {
            return Optional.of(plugin.get().name() + " declares no run \"" + entry.title() + "\".");
        }
        return Optional.empty();
    }

    /** Stops the run, binds the plugins again and starts {@code entry}; each failure said on the status line. */
    static void restart(Project project, PluginCoordinate coordinate, RunEntry entry, Duration stopWithin) {
        try {
            if (project.running()) {
                project.stop();
                long deadline = System.nanoTime() + stopWithin.toNanos();
                while (project.running()) {
                    if (System.nanoTime() > deadline) {
                        project.status("Dev reload: the run did not stop in " + stopWithin.toSeconds()
                                + " s; nothing was reloaded.");
                        return;
                    }
                    Thread.sleep(100);
                }
            }
            Optional<String> refused = refusal(project.rebind(), coordinate, entry);
            if (refused.isPresent()) {
                project.status("Dev reload: the plugins reloaded, but " + refused.get());
                return;
            }
            project.start(entry.title(), entry.mainClass());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            project.status("Dev reload failed: " + e.getMessage());
        }
    }

    /**
     * {@code classpath}'s plugins loaded as a dev-mode bind would load them, on a loader of their own beside the bound
     * set, and closed again. The catalog's gate is not asked: a plugin that reloads itself was admitted already.
     */
    private static Bound trial(List<String> classpath) {
        ReleasedPlugins.Split released = ReleasedPlugins.split(classpath, true);
        DuplicatePlugins.Split single = DuplicatePlugins.split(released.loadable());
        PluginLoader.Loaded loaded = PluginLoader.openReporting(single.loadable());
        try (PluginLoader loader = loaded.loader()) {
            return Bound.of(loader == null ? List.of() : loader.plugins(), loaded.failures());
        }
    }
}
