// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.plugin;

import com.botmaker.core.plugin.PluginHost.LoadedJar;
import com.botmaker.plugin.host.LoadedPlugin;

import java.net.URISyntaxException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The jars one set of plugins was loaded from, and which plugin of the set ships a class. */
final class PluginClasses {

    private final List<LoadedPlugin> plugins;
    private final ClassLoader loader;

    /**
     * {@link #ships}' answers for this set. A palette's curation asks it of every name it offers, each time a menu is
     * built, and a name no plugin ships failed its {@code Class.forName} anew every time: the most thrown exception
     * in a profile of ordinary editing.
     */
    private final Map<String, Boolean> shipped = new ConcurrentHashMap<>();

    /** {@code set}'s classes, a name looked up by {@code loader}. */
    PluginClasses(List<LoadedPlugin> set, ClassLoader loader) {
        this.plugins = set;
        this.loader = loader;
    }

    /** {@link PluginHost#pluginOwning}. */
    Optional<LoadedPlugin> owning(Class<?> type) {
        Path where = jarOf(type);
        if (where == null) return Optional.empty();
        for (LoadedPlugin plugin : plugins) {
            if (where.equals(plugin.jar())) return Optional.of(plugin);
        }
        return Optional.empty();
    }

    /** {@link PluginHost#pluginNameOwning}. */
    Optional<String> nameOwning(String binaryName) {
        if (binaryName == null || binaryName.isBlank()) return Optional.empty();
        try {
            return owning(Class.forName(binaryName, false, loader)).map(LoadedPlugin::name);
        } catch (ClassNotFoundException | LinkageError e) {
            return Optional.empty();
        }
    }

    /** {@link PluginHost#shipsInPlugin}. */
    boolean ships(String binaryName) {
        if (binaryName == null || binaryName.indexOf('.') < 0) return false;
        return shipped.computeIfAbsent(binaryName, name -> {
            try {
                return owning(Class.forName(name, false, loader)).isPresent();
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        });
    }

    /** {@link PluginHost#pluginJars}. */
    Set<Path> jars() {
        Set<Path> jars = new LinkedHashSet<>();
        for (LoadedPlugin plugin : plugins) jars.add(plugin.jar());
        return jars;
    }

    /** {@link PluginHost#loaded}. */
    List<LoadedJar> loadedJars() {
        return plugins.stream()
                .map(plugin -> new LoadedJar(plugin.coordinate(), plugin.name(), plugin.jar().toString()))
                .filter(LoadedJar::inRepository)
                .toList();
    }

    /**
     * The dev-build jars among {@code devJars} that a plugin of the set came from, described: a dev build that
     * failed to load, or that the composition rejected, is a failure and not a loaded build.
     */
    List<String> bound(List<String> devJars) {
        if (devJars.isEmpty()) return List.of();
        Set<Path> sources = jars();
        return devJars.stream()
                .filter(jar -> sources.contains(Path.of(jar).toAbsolutePath().normalize()))
                .map(ReleasedPlugins::describe)
                .toList();
    }

    /** The classpath entry {@code type} was loaded from, absolute and normalised, or null for none. */
    private static Path jarOf(Class<?> type) {
        try {
            CodeSource source = type.getProtectionDomain().getCodeSource();
            return source == null || source.getLocation() == null ? null
                    : Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (SecurityException | URISyntaxException | IllegalArgumentException
                 | FileSystemNotFoundException e) {
            return null;
        }
    }
}
