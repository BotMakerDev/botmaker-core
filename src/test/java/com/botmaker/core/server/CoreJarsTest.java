package com.botmaker.core.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The core's jars rebuilt under it, named by file. */
class CoreJarsTest {

    @Test
    void aJarRewrittenOrAClassFolderTouchedIsNamed(@TempDir Path dir) throws Exception {
        Path jar = Files.writeString(dir.resolve("botmaker-shared.jar"), "a");
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path one = Files.writeString(classes.resolve("A.class"), "a");
        Path kept = Files.writeString(dir.resolve("kept.jar"), "k");
        Map<Path, Long> before = CoreJars.stamps(List.of(jar, classes, kept));

        Files.setLastModifiedTime(jar, FileTime.fromMillis(before.get(jar) + 60_000));
        Files.setLastModifiedTime(one, FileTime.fromMillis(before.get(classes) + 60_000));
        assertEquals(List.of("botmaker-shared.jar", "classes"),
                CoreJars.changed(before, CoreJars.stamps(List.of(jar, classes, kept))));
    }
}
