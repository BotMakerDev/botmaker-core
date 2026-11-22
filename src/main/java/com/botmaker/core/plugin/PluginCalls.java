// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.host.LoadedPlugin;

import java.util.List;
import java.util.function.Supplier;

/**
 * A call into each plugin of a set, contained: one that throws costs its own plugin's answer and nobody else's.
 */
final class PluginCalls {

    private PluginCalls() {}

    /**
     * Runs one plugin call, answering {@code null} when it throws.
     *
     * <p>The same containment every other pass over plugin code here uses: a plugin's failure costs that
     * plugin's contribution and nobody else's. It is a printed line rather than a dialog because the window
     * that asked has a section to draw and a user in front of it.
     */
    static <T> T quietly(LoadedPlugin plugin, String what, Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException | LinkageError e) {
            System.err.println("Warning: plugin " + plugin.coordinate() + " failed to " + what + ": " + e);
            return null;
        }
    }

    /** {@link PluginHost#openIncoming}. */
    static void openIncoming(List<LoadedPlugin> incoming, StudioServices services) {
        if (services == null) return;
        for (LoadedPlugin plugin : incoming) {
            try {
                plugin.plugin().projectOpened(services);
            } catch (RuntimeException | Error e) {
                PluginLogs.of(plugin).error("failed to take the opening project", e);
            }
        }
    }

    /**
     * Tells the plugins that were serving the outgoing project that it is over, by
     * {@link StudioPlugin#projectClosing()}.
     *
     * <p>Called from {@link PluginHost}'s swap once the binding is known to succeed, and deliberately at two points
     * in that method at once: <b>after</b> the merge that can still refuse the new set — a project that fails to
     * bind never displaced anything, so nothing has closed — and <b>before</b> the outgoing loader is closed,
     * because a plugin's release code is that plugin's own class and cannot run on a dead classloader.
     *
     * <p>Total, like every other pass over plugin code here: one plugin that throws on the way out must not
     * stop the next plugin from being told, and must not stop the project that is opening.
     */
    static void closeOutgoing(List<LoadedPlugin> outgoing) {
        for (LoadedPlugin plugin : outgoing) {
            try {
                plugin.plugin().projectClosing();
            } catch (RuntimeException | Error e) {
                PluginLogs.of(plugin).error("failed to release what it held for the closing project", e);
            }
        }
    }
}
