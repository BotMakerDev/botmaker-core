package com.botmaker.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** What keeps the core headless and below its clients: no UI, no Studio, no bot runtime. */
class CoreLayersTest {

    private static final Path MAIN = Path.of("src/main/java");
    /**
     * JavaFX and Swing, Studio, the SDK and the toolkit a plugin draws with, imported or written out in full. A run's
     * frames are AWT images.
     */
    private static final Pattern ABOVE_THE_CORE = Pattern.compile(
            "(?<![\\w.])(javafx|javax\\.swing|com\\.botmaker\\.studio|com\\.botmaker\\.sdk"
                    + "|com\\.botmaker\\.plugin\\.toolkit)\\.");

    @Test
    void nothingAboveTheCoreIsImported() throws IOException {
        List<Path> sources = sources();
        assertFalse(sources.isEmpty(), "run from the module's root");
        assertEquals(List.of(), sources.stream().filter(file -> ABOVE_THE_CORE.matcher(read(file)).find()).toList());
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(file -> file.toString().endsWith(".java")).toList();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException(file.toString(), e);
        }
    }
}
