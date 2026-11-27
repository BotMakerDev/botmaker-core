// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.run.RunView;
import com.botmaker.plugin.api.run.ViewTarget;
import com.botmaker.plugin.api.run.ViewedRun;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SideViewsTest {

    private static final StudioServices SERVICES = (StudioServices) Proxy.newProxyInstance(
            StudioServices.class.getClassLoader(), new Class<?>[] {StudioServices.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException(method.getName());
            });

    private final List<ViewedRun> told = new ArrayList<>();

    private SideViews over() {
        RunViewsTest.Recording.view = new RunView() {
            @Override
            public Optional<ViewTarget> target() {
                return Optional.empty();
            }

            @Override
            public void start(StudioServices services, ViewedRun run, Runnable changed) {
                told.add(run);
            }
        };
        List<StudioPlugin> bound = List.of(new RunViewsTest.RecordingPlugin());
        return new SideViews(() -> bound);
    }

    @Test
    void aRunStartedBesideTheOpenBotHasViewsToldItsRunAndProperties() {
        SideViews side = over();
        side.serve(SERVICES, Runnable::run);

        side.started("run-1", "SignIn", Map.of("k", "v"), new CompletableFuture<>());
        side.of("run-1").orElseThrow().views();

        assertEquals(List.of(new ViewedRun("run-1", "SignIn", Map.of("k", "v"))), told);
    }

    @Test
    void theViewsGoWhenTheRunsProcessEnds() {
        SideViews side = over();
        side.serve(SERVICES, Runnable::run);
        CompletableFuture<Object> ended = new CompletableFuture<>();

        side.started("run-1", "SignIn", Map.of(), ended);
        assertTrue(side.of("run-1").isPresent());
        ended.complete(null);

        assertTrue(side.of("run-1").isEmpty());
    }

    @Test
    void aRunStartedWithNoProjectServedHasNoViews() {
        SideViews side = over();

        side.started("run-1", "SignIn", Map.of(), new CompletableFuture<>());

        assertTrue(side.of("run-1").isEmpty());
    }
}
