// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.plugin;

import com.botmaker.core.plugin.PluginHost.OwnedItem;
import com.botmaker.core.plugin.PluginHost.PluginBar;
import com.botmaker.plugin.api.toolbar.ToolbarItem;
import com.botmaker.plugin.host.LoadedPlugin;
import com.botmaker.plugin.host.PluginCoordinate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One set of plugins' toolbar items, in the order the bar draws them. Merged once, for the set's whole binding. */
final class PluginBars {

    private final List<OwnedItem> owned;
    private final List<ToolbarItem> items;

    PluginBars(List<LoadedPlugin> set) {
        owned = mergeOwnedToolbarItems(set);
        items = strip(owned);
    }

    /** {@link PluginHost#ownedToolbarItems}. */
    List<OwnedItem> owned() {
        return owned;
    }

    /** {@link PluginHost#toolbarItems}. */
    List<ToolbarItem> items() {
        return items;
    }

    /** {@link PluginHost#barItemsByPlugin}. */
    List<PluginBar> byPlugin() {
        return barItemsByPlugin(owned);
    }

    /** {@link PluginHost#overlayItems}. */
    List<ToolbarItem> overlayItems() {
        return overlayItems(items);
    }

    private static List<ToolbarItem> strip(List<OwnedItem> owned) {
        return owned.stream().map(OwnedItem::item).toList();
    }

    /** The same grouping over an explicit list — the seam the merge test uses. */
    static List<PluginBar> barItemsByPlugin(List<OwnedItem> owned) {
        Map<PluginCoordinate, PluginBar> bars = new LinkedHashMap<>();
        for (OwnedItem o : owned) {
            if (o.item().place() != ToolbarItem.Place.BAR) continue;
            PluginBar bar = bars.get(o.plugin());
            List<ToolbarItem> items = new ArrayList<>(bar == null ? List.of() : bar.items());
            items.add(o.item());
            bars.put(o.plugin(), new PluginBar(o.plugin(), o.pluginName(), o.jar(), items));
        }
        return List.copyOf(bars.values());
    }

    /** The same filter over an explicit list — the seam the merge test uses. */
    static List<ToolbarItem> overlayItems(List<ToolbarItem> items) {
        return items.stream().filter(item -> item.place() == ToolbarItem.Place.OVERLAY).toList();
    }

    // Package-private rather than private: the two rules below — the sort's tie-break on the plugin's
    // coordinate, and a throwing plugin costing only itself — have no visible symptom when they are wrong. A
    // bar in a slightly different order looks like somebody's preference, not like a bug.
    static List<ToolbarItem> mergeToolbarItems(List<LoadedPlugin> set) {
        return strip(mergeOwnedToolbarItems(set));
    }

    /** {@link #mergeToolbarItems}, keeping the owner. */
    static List<OwnedItem> mergeOwnedToolbarItems(List<LoadedPlugin> set) {
        List<OwnedItem> merged = new ArrayList<>();
        for (LoadedPlugin plugin : set) {
            List<ToolbarItem> offered;
            try {
                offered = plugin.plugin().toolbarItems();
            } catch (RuntimeException | Error e) {
                // A plugin that cannot list its buttons must not cost the ones that can, nor the project.
                System.err.println("Warning: " + plugin.coordinate() + " could not offer toolbar items: " + e);
                continue;
            }
            if (offered == null) continue;
            for (ToolbarItem item : offered) {
                if (item == null || item.label() == null || item.onClick() == null) continue;
                merged.add(new OwnedItem(plugin.coordinate(), plugin.name(), plugin.jar(), item));
            }
        }
        merged.sort(Comparator.comparing((OwnedItem o) -> o.pluginName() == null ? ""
                        : o.pluginName().toLowerCase(Locale.ROOT))
                .thenComparing(o -> o.plugin().toString())
                .thenComparingInt(o -> o.item().order())
                .thenComparing(o -> o.item().id()));
        return List.copyOf(merged);
    }
}
