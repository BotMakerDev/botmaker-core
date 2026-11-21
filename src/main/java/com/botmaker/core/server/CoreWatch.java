// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.server;

import com.botmaker.core.dashboard.DashboardFeed;
import com.botmaker.core.dashboard.Inside;
import com.botmaker.core.edit.ChangedMethod;
import com.botmaker.core.edit.EditResult;
import com.botmaker.core.plugin.PluginHost;
import com.botmaker.plugin.host.LoadedPlugin;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What {@link Projects} tells the core's dashboard: each channel as it comes and goes and what it asks, each edit with
 * how it went, and on demand an {@link Inside} of the whole core. Only counts and kinds are kept of a request, never its
 * content.
 */
final class CoreWatch {

    /** A channel as the dashboard knows it. */
    private static final class Client {
        final long openedMillis;
        final AtomicLong requests = new AtomicLong();
        volatile String lastRequest;
        volatile boolean watchingCalls;

        Client(long openedMillis) {
            this.openedMillis = openedMillis;
        }
    }

    private final DashboardFeed feed;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();

    CoreWatch(Clock clock) {
        this.feed = new DashboardFeed(clock);
    }

    DashboardFeed feed() {
        return feed;
    }

    /** The channel {@code channel} (its id) opened. */
    void attached(String channel) {
        clients.put(channel, new Client(feed.now()));
        feed.client(new DashboardFeed.ClientSeen(feed.now(), channel, "studio", true));
    }

    void left(String channel) {
        if (clients.remove(channel) != null) {
            feed.client(new DashboardFeed.ClientSeen(feed.now(), channel, "studio", false));
        }
    }

    void received(String channel, Request request) {
        Client client = clients.get(channel);
        if (client == null) return;
        client.requests.incrementAndGet();
        client.lastRequest = request.getClass().getSimpleName();
        if (request instanceof Request.WatchCalls) client.watchingCalls = true;
    }

    /** Whether {@code request} changes the project's files or history, so its answer is an edit the dashboard shows. */
    static boolean isEdit(Request request) {
        return request instanceof Request.Apply || request instanceof Request.UserEdit
                || request instanceof Request.Together || request instanceof Request.PluginWriting
                || request instanceof Request.Restore || request instanceof Request.Step;
    }

    /** {@code request}, an edit {@code channel} sent {@code startNanos} ago by {@link System#nanoTime}, answered. */
    void answered(String channel, Request request, EditResult result, long startNanos) {
        String file = switch (request) {
            case Request.Apply apply -> apply.request().file();
            case Request.Restore restore -> restore.file();
            default -> "";
        };
        String label = switch (result) {
            case EditResult.Refused refused -> refused.reason();
            case EditResult.Accepted ignored -> request instanceof Request.Together together ? together.label() : "";
        };
        List<String> methods = result instanceof EditResult.Accepted accepted
                ? accepted.methods().stream().map(ChangedMethod::name).toList() : List.of();
        long took = (System.nanoTime() - startNanos) / 1_000_000;
        feed.edit(new DashboardFeed.EditSeen(feed.now(), channel, request.getClass().getSimpleName(),
                result instanceof EditResult.Accepted, file, methods, took, label));
    }

    /** The whole core now; {@code server} null in Studio's in-process core, {@code project} null with none open. */
    Inside inside(CoreServer server, OpenProject project) {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        Map<String, Integer> byPrefix = new TreeMap<>();
        Thread.getAllStackTraces().keySet().forEach(thread -> byPrefix.merge(prefix(thread.getName()), 1, Integer::sum));
        CoreServer.Info info = server == null ? null : server.info();
        Optional<String> projectName = Optional.ofNullable(project).map(p -> p.directory().getFileName().toString());
        Optional<Long> lock = Optional.empty();
        if (project != null) {
            try {
                lock = ProjectLock.owner(project.directory()).stream().boxed().findFirst();
            } catch (IOException unread) {
                // The lock file is unreadable now: the card says no holder.
            }
        }
        List<Inside.ClientNow> now = clients.entrySet().stream()
                .map(entry -> new Inside.ClientNow(entry.getKey(), "studio", entry.getValue().openedMillis,
                        entry.getValue().watchingCalls, entry.getValue().requests.get(),
                        Optional.ofNullable(entry.getValue().lastRequest), Optional.empty()))
                .toList();
        List<Inside.PluginNow> plugins = PluginHost.plugins().stream().map(CoreWatch::plugin).toList();
        return new Inside(feed.now(), ManagementFactory.getRuntimeMXBean().getUptime(),
                ProcessHandle.current().pid(), info == null ? "" : info.version(), info != null && info.ending(),
                heap.getUsed(), heap.getMax(), byPrefix.values().stream().mapToInt(Integer::intValue).sum(), byPrefix,
                CoreJars.changed(), projectName, lock, project != null && project.devMode(), now,
                project == null ? Optional.empty() : project.runningTitle(),
                project == null ? List.of() : project.companionsGoing(), plugins,
                server == null ? Optional.empty() : server.idleLeftMillis());
    }

    /** A thread's name up to its first digit or separator: {@code core-idle}, {@code ForkJoinPool}, {@code qtp}. */
    static String prefix(String name) {
        int end = 0;
        while (end < name.length() && !Character.isDigit(name.charAt(end)) && name.charAt(end) != '#') end++;
        String cut = name.substring(0, end).replaceAll("[-_ .]+$", "");
        return cut.isEmpty() ? "(unnamed)" : cut;
    }

    /** A loaded plugin's version is its jar's folder in the local repository; a {@code -SNAPSHOT} is a local build. */
    private static Inside.PluginNow plugin(LoadedPlugin loaded) {
        Path folder = loaded.jar() == null ? null : loaded.jar().getParent();
        String version = folder == null || folder.getFileName() == null ? "" : folder.getFileName().toString();
        return new Inside.PluginNow(loaded.coordinate().groupId() + ":" + loaded.coordinate().artifactId(), version,
                version.endsWith("-SNAPSHOT"), loaded.name());
    }
}
