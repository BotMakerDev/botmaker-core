// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.dashboard;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the core is now, as only the core sees it: its process, its clients, the open project and what runs in it. The
 * dashboard asks for one every two seconds while a page is open, and never otherwise.
 *
 * @param atMillis        when it was taken, since the epoch
 * @param uptimeMillis    how long the core's process has run
 * @param threadsByPrefix live threads by their name's first word ({@code core-idle}, {@code dashboard-watch}, …)
 * @param rebuiltJars     the jars rebuilt under the core since it started ({@code CoreJars})
 * @param project         the open project's folder name
 * @param lockHolder      the pid holding the open project's {@code .botmaker/core.lock}
 * @param running         the title of what runs now: a bot's {@code main} or a plugin's entry
 * @param companions      the class names of the companions serving it
 * @param idleLeftMillis  how long until the core ends with nothing to do; empty while something keeps it up
 */
public record Inside(long atMillis, long uptimeMillis, long pid, String version, boolean ending, long heapUsed,
                     long heapMax, int threads, Map<String, Integer> threadsByPrefix, List<String> rebuiltJars,
                     Optional<String> project, Optional<Long> lockHolder, boolean devMode, List<ClientNow> clients,
                     Optional<String> running, List<String> companions, List<PluginNow> plugins,
                     Optional<Long> idleLeftMillis) {

    public Inside {
        threadsByPrefix = Map.copyOf(threadsByPrefix);
        rebuiltJars = List.copyOf(rebuiltJars);
        clients = List.copyOf(clients);
        companions = List.copyOf(companions);
        plugins = List.copyOf(plugins);
    }

    /**
     * A client of the core: a channel ({@code kind} {@code studio}), how many requests it sent and the kind of its
     * last, and the file it has open.
     */
    public record ClientNow(String id, String kind, long openedMillis, boolean watchingCalls, long requests,
                            Optional<String> lastRequest, Optional<String> openFile) {}

    /** A plugin bound now: its coordinate, its version, whether it is a local build, and what the core said of it. */
    public record PluginNow(String coordinate, String version, boolean localBuild, String said) {}
}
