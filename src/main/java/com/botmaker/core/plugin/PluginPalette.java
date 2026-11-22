// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.plugin;

import com.botmaker.plugin.api.catalog.FacadeEntry;
import com.botmaker.plugin.api.catalog.PaletteCatalog;
import com.botmaker.plugin.host.LoadedPlugin;
import com.botmaker.plugin.host.Palettes;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** One set of plugins' merged palette, built on first ask. */
final class PluginPalette {

    private final List<LoadedPlugin> plugins;

    /** The set's merged palette, null until the first ask. */
    private volatile PaletteCatalog catalog;

    /**
     * {@link #cataloguedPackages}, with the catalog it was read from: answered again only for that catalog, so a
     * lookup that read the old one while a bind ran cannot leave its answer for the new one. The type index asks
     * it on every lookup, and a block's parameter types are looked up on every render (2026-10-18).
     */
    private record Catalogued(PaletteCatalog of, Set<String> packages) { }
    private volatile Catalogued catalogued;

    PluginPalette(List<LoadedPlugin> set) {
        this.plugins = set;
    }

    /** {@link PluginHost#catalogFor}. */
    PaletteCatalog catalog() {
        PaletteCatalog local = catalog;
        if (local == null) {
            local = merge(plugins);
            catalog = local;
        }
        return local;
    }

    /** {@link PluginHost#cataloguedPackages}. */
    Set<String> cataloguedPackages() {
        PaletteCatalog now = catalog();
        Catalogued known = catalogued;
        if (known != null && known.of() == now) return known.packages();
        Set<String> packages = new LinkedHashSet<>();
        for (FacadeEntry facade : now.facades()) {
            String qualified = facade.qualifiedName();
            int dot = qualified.lastIndexOf('.');
            if (dot > 0) packages.add(qualified.substring(0, dot));
        }
        Set<String> answer = Set.copyOf(packages);
        catalogued = new Catalogued(now, answer);
        return answer;
    }

    /** {@link PluginHost#ownerOf}. */
    Optional<FacadeEntry> ownerOf(String simpleName) {
        return simpleName == null || simpleName.isBlank()
                ? Optional.empty()
                : catalog().facadeBySimpleName(simpleName.trim());
    }

    private static PaletteCatalog merge(List<LoadedPlugin> set) {
        PaletteCatalog merged = PaletteCatalog.empty();
        for (LoadedPlugin plugin : set) {
            PaletteCatalog offered = PluginCalls.quietly(plugin, "build its palette",
                    () -> Palettes.of(plugin.plugin()));
            if (offered != null) merged = merged.mergedWith(offered);
        }
        return merged;
    }
}
