package com.botmaker.core.plugin;

import com.botmaker.plugin.api.History;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.api.source.PluginValues;
import com.botmaker.plugin.host.LoadedPlugin;
import com.botmaker.plugin.host.PluginCoordinate;
import com.botmaker.shared.tools.UserDirs;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which {@link HostValues} serves each plugin's {@code StudioServices.hostValues()} (2026-11-12, the user's call): the
 * newest version of the plugin this process has bound, home or a project's, serves the folder alone. A project that
 * pins an older version gets that copy, never one of its own, so an older version never writes the folder; one
 * {@code HostValues} per coordinate, so two bindings never write it at once.
 *
 * <p>What a version declares and the jars it compiles against are kept from the bind that brought it, so the newest
 * version still serves after a project's older one replaced it on the loader.
 */
public final class HostFolders {

    /** The served copy per plugin, by its coordinate with BotMaker's two groupIds as one. */
    private static final Map<String, Served> SERVED = new ConcurrentHashMap<>();

    /** The root the folders are under: {@code UserDirs.config()}, a test's own folder while it runs. */
    private static volatile Path root;

    private HostFolders() {}

    /** The copy that serves one plugin, and the version it is. */
    record Served(String version, HostValues values) {}

    /**
     * Takes in what a bind brought: each plugin newer than (or as new as) the copy serving it now serves instead, its
     * declared values and {@code classpath} with it.
     */
    static void bound(List<LoadedPlugin> plugins, List<String> classpath) {
        List<Path> compiled = classpath.stream().map(Path::of).toList();
        for (LoadedPlugin plugin : plugins) {
            String version = ReleasedPlugins.versionOf(plugin.jar());
            List<ManagedValue<?>> declared = declared(plugin);
            boolean keeps = declared.stream().anyMatch(ManagedValue::onHost);
            PluginCoordinate folder = canonical(plugin.coordinate());
            // The copy serving now stays on a tie: a plugin that kept its PluginValues keeps the one that serves.
            SERVED.compute(key(folder), (key, now) ->
                    now != null && HostValues.compare(version, now.version()) <= 0 ? now
                            : keeps ? new Served(version, new HostValues(root(), folder, version, declared,
                            PluginHost.grammar(), HomeServices.BARE, () -> compiled))
                            // A newer version that keeps nothing of this PC ends the older copy's serving.
                            : null);
        }
    }

    /**
     * The values of this PC {@code plugin} reads and writes: the newest version's copy, or {@link PluginValues#NONE}
     * for a plugin that declares none.
     */
    public static PluginValues of(LoadedPlugin plugin) {
        Served served = plugin == null ? null : SERVED.get(key(canonical(plugin.coordinate())));
        return served == null ? PluginValues.NONE : served.values();
    }

    /** The undo of {@code plugin}'s values of this PC, the serving copy's, or {@link History#NONE} when it keeps none. */
    public static History historyOf(LoadedPlugin plugin) {
        Served served = plugin == null ? null : SERVED.get(key(canonical(plugin.coordinate())));
        return served == null ? History.NONE : served.values().history();
    }

    /** The version serving {@code coordinate}'s values of this PC, or empty for none. */
    static String servingVersion(PluginCoordinate coordinate) {
        Served served = SERVED.get(key(canonical(coordinate)));
        return served == null ? "" : served.version();
    }

    /** Puts the folders under {@code folder}, forgetting every served copy; null puts them back. For tests. */
    static void rootAt(Path folder) {
        root = folder;
        SERVED.clear();
    }

    private static Path root() {
        Path at = root;
        return at != null ? at : UserDirs.config();
    }

    private static List<ManagedValue<?>> declared(LoadedPlugin plugin) {
        try {
            return plugin.plugin().managedValues();
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    /**
     * The coordinate a plugin's folder is named by: BotMaker's two groupIds are one plugin (DuplicatePlugins), so both
     * keep their values under the current one, whichever copy a bind brought.
     */
    static PluginCoordinate canonical(PluginCoordinate coordinate) {
        return DuplicatePlugins.isBotMakerGroup(coordinate.groupId())
                ? new PluginCoordinate(BOTMAKER_GROUP, coordinate.artifactId()) : coordinate;
    }

    private static final String BOTMAKER_GROUP = "com.github.BotMakerDev";

    private static String key(PluginCoordinate coordinate) {
        return coordinate.groupId() + ":" + coordinate.artifactId();
    }
}
