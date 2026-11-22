// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.plugin;

import com.botmaker.core.plugin.PluginHost.OwnedOverlay;
import com.botmaker.core.plugin.PluginHost.OwnedPart;
import com.botmaker.plugin.api.overlay.OverlayPart;
import com.botmaker.plugin.api.run.Readiness;
import com.botmaker.plugin.api.run.ReportView;
import com.botmaker.plugin.api.run.RunOverlayPart;
import com.botmaker.plugin.host.LoadedPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What one set of plugins adds beside a run and beside the game's window: the parts of the two overlays, whether the
 * bot can start, and the report views. Each is asked of the plugins when a UI first wants it, never at bind.
 */
final class PluginParts {

    private final List<LoadedPlugin> plugins;

    /** Built on first ask after each bind, since listing parts loads the plugins' JavaFX classes. */
    private volatile List<OwnedPart> runOverlayParts;

    /** Built on first ask after each bind, since a part's supplier loads the plugin's JavaFX classes. */
    private volatile List<OwnedOverlay> overlayParts;

    PluginParts(List<LoadedPlugin> set) {
        this.plugins = set;
    }

    /** {@link PluginHost#runOverlayParts}. */
    List<OwnedPart> runOverlayParts() {
        List<OwnedPart> parts = runOverlayParts;
        if (parts == null) {
            parts = mergeRunOverlayParts(plugins);
            runOverlayParts = parts;
        }
        return parts;
    }

    /** {@link PluginHost#overlayParts}. */
    List<OwnedOverlay> overlayParts() {
        List<OwnedOverlay> parts = overlayParts;
        if (parts == null) {
            parts = mergeOverlayParts(plugins);
            overlayParts = parts;
        }
        return parts;
    }

    /** {@link PluginHost#readiness}. */
    Optional<Readiness> readiness() {
        return leastReady(plugins);
    }

    /** {@link PluginHost#reportViews}. */
    List<ReportView> reportViews() {
        return mergeReportViews(plugins);
    }

    static Optional<Readiness> leastReady(List<LoadedPlugin> set) {
        Readiness least = null;
        for (LoadedPlugin plugin : set) {
            Optional<Readiness> said;
            try {
                said = plugin.plugin().readiness();
            } catch (RuntimeException | LinkageError e) {
                // Includes a plugin built against a contract without this method: it has nothing to say.
                System.err.println("Warning: " + plugin.coordinate() + " could not say whether the bot can start: " + e);
                continue;
            }
            if (said == null || said.isEmpty()) continue;
            if (least == null || said.get().level().compareTo(least.level()) > 0) least = said.get();
        }
        return Optional.ofNullable(least);
    }

    static List<ReportView> mergeReportViews(List<LoadedPlugin> set) {
        Map<String, ReportView> byTitle = new LinkedHashMap<>();
        for (LoadedPlugin plugin : set) {
            List<ReportView> offered;
            try {
                offered = plugin.plugin().reportViews();
            } catch (RuntimeException | LinkageError e) {
                System.err.println("Warning: " + plugin.coordinate() + " could not offer report views: " + e);
                continue;
            }
            if (offered == null) continue;
            offered.stream().filter(Objects::nonNull).forEach(v -> byTitle.putIfAbsent(v.title(), v));
        }
        return List.copyOf(byTitle.values());
    }

    static List<OwnedPart> mergeRunOverlayParts(List<LoadedPlugin> set) {
        List<OwnedPart> merged = new ArrayList<>();
        for (LoadedPlugin plugin : set) {
            List<RunOverlayPart> offered;
            try {
                offered = plugin.plugin().runOverlayParts();
            } catch (RuntimeException | LinkageError e) {
                // Includes a plugin built against a contract without this method: it simply draws nothing.
                System.err.println("Warning: " + plugin.coordinate() + " could not offer run overlay parts: " + e);
                continue;
            }
            if (offered == null) continue;
            Set<String> ids = new LinkedHashSet<>();
            for (RunOverlayPart part : offered) {
                if (part != null && ids.add(part.id())) merged.add(new OwnedPart(plugin.coordinate(), part));
            }
        }
        return List.copyOf(merged);
    }

    static List<OwnedOverlay> mergeOverlayParts(List<LoadedPlugin> set) {
        List<OwnedOverlay> merged = new ArrayList<>();
        for (LoadedPlugin plugin : set) {
            Optional<OverlayPart> offered;
            try {
                offered = plugin.plugin().overlayPart();
            } catch (RuntimeException | LinkageError e) {
                // Includes a plugin built against a contract without this method: it simply adds nothing.
                System.err.println("Warning: " + plugin.coordinate() + " could not offer an overlay part: " + e);
                continue;
            }
            if (offered == null || offered.isEmpty() || offered.get().isEmpty()) continue;
            merged.add(new OwnedOverlay(plugin.coordinate(), plugin.name(), offered.get()));
        }
        return List.copyOf(merged);
    }
}
