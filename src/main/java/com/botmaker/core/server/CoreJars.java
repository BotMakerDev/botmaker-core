package com.botmaker.core.server;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The core's own classpath as it was when the core started, to say which part a build replaced since (2026-11-10): a
 * dev {@code mvn install} rewrites a jar under a running core, which then fails on its first class not yet loaded
 * ({@code NoClassDefFoundError GitHubAuth} on opening a project). {@code core_status} names them; {@code quit_core}
 * restarts the core on the new ones.
 */
final class CoreJars {

    private static final Map<Path, Long> AT_START = stamps(classpath());

    private CoreJars() {}

    /** Takes the start's stamps now, if nothing took them yet. */
    static void remember() {
        // Loading the class took them.
    }

    /** The jars and class folders changed since the core started, by file name. */
    static List<String> changed() {
        return changed(AT_START, stamps(AT_START.keySet().stream().toList()));
    }

    /** What {@code now} stamps differently from {@code before}. Pure. */
    static List<String> changed(Map<Path, Long> before, Map<Path, Long> now) {
        List<String> out = new ArrayList<>();
        before.forEach((path, stamp) -> {
            if (!stamp.equals(now.get(path))) out.add(String.valueOf(path.getFileName()));
        });
        return out;
    }

    private static List<Path> classpath() {
        List<Path> out = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!entry.isBlank()) out.add(Path.of(entry));
        }
        return out;
    }

    /** Each entry's last change: a jar's own time, a class folder's newest class, -1 when missing. */
    static Map<Path, Long> stamps(List<Path> entries) {
        Map<Path, Long> out = new LinkedHashMap<>();
        for (Path entry : entries) out.put(entry, stamp(entry));
        return out;
    }

    private static long stamp(Path entry) {
        try {
            if (Files.isRegularFile(entry)) return Files.getLastModifiedTime(entry).toMillis();
            if (!Files.isDirectory(entry)) return -1;
            try (Stream<Path> walk = Files.walk(entry)) {
                return walk.filter(Files::isRegularFile).mapToLong(file -> {
                    try {
                        return Files.getLastModifiedTime(file).toMillis();
                    } catch (IOException e) {
                        return -1;
                    }
                }).max().orElse(-1);
            }
        } catch (IOException | UncheckedIOException e) {
            return -1;
        }
    }
}
