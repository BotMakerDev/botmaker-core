// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.core.plugin.PluginHost;
import com.botmaker.core.run.SideRunner;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.run.ViewedRun;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * What the plugins show of each run started beside the open bot ({@link SideRunner}), one {@link RunViews} per run,
 * built when the run starts and dropped when its process ends (pilot in core phase 4b-2). Each view is told the run
 * and the run properties it was started with, so the SDK shows a sign-in run's own emulator.
 *
 * <p>The views need the services of an open project to be started with and its thread to be asked on; a run that
 * starts while no project is served gets none, and the pilot says it has no view yet. One per core:
 * {@link #ofThisCore}.
 */
public final class SideViews implements SideRunner.Told {

    private static final SideViews THIS_CORE = new SideViews(PluginHost::instances);

    private final Supplier<List<StudioPlugin>> plugins;
    private final Map<String, RunViews> byRun = new ConcurrentHashMap<>();
    private volatile StudioServices services;
    private volatile Executor asks;

    SideViews(Supplier<List<StudioPlugin>> plugins) {
        this.plugins = plugins;
    }

    /** The views of this process's side runs. */
    public static SideViews ofThisCore() {
        return THIS_CORE;
    }

    /** An open project lends what a view is started with and asked on; {@code null}s take it back. */
    public void serve(StudioServices services, Executor asks) {
        this.services = services;
        this.asks = asks;
    }

    @Override
    public void started(String runId, String bot, Map<String, String> properties, CompletionStage<?> ended) {
        StudioServices lent = services;
        Executor on = asks;
        if (runId == null || runId.isBlank() || lent == null || on == null) return;
        RunViews views = new RunViews(plugins, lent, on, () -> { });
        views.started(() -> new ViewedRun(runId, bot, properties));
        RunViews previous = byRun.put(runId, views);
        if (previous != null) previous.close();
        ended.whenComplete((done, failure) -> {
            if (byRun.remove(runId, views)) views.close();
        });
    }

    /** The views of the side run {@code runId}, while it goes. */
    public Optional<RunViews> of(String runId) {
        return Optional.ofNullable(byRun.get(runId));
    }
}
