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
    private static final Pattern NEWEST_JAVA =
            Pattern.compile("getJLSLatest|latestSupportedJavaVersion|ASTNode\\.copySubtree\\(");
    /** A comment may tell a class's history; only code is held to these rules. */
    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/|(?m)^\\s*//.*$", Pattern.DOTALL);

    @Test
    void nothingAboveTheCoreIsImported() throws IOException {
        List<Path> sources = sources();
        assertFalse(sources.isEmpty(), "run from the module's root");
        assertEquals(List.of(), sources.stream().filter(file -> ABOVE_THE_CORE.matcher(code(file)).find()).toList());
    }

    /** A tree takes the bot's level, or {@code JavaLevel.LATEST}; a subtree is copied with {@code JavaLevel.copy}. */
    @Test
    void nothingAsksJdtForItsNewestJava() throws IOException {
        assertEquals(List.of(), sources().stream().filter(file -> NEWEST_JAVA.matcher(code(file)).find()).toList());
    }

    /** {@code Executables.onPath} answers without a child process left undrained. */
    @Test
    void nothingSpawnsWhich() throws IOException {
        assertEquals(List.of(), sources().stream().filter(file -> code(file).contains("ProcessBuilder(\"which\""))
                .toList());
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(file -> file.toString().endsWith(".java")).toList();
        }
    }

    private static String code(Path file) {
        try {
            return COMMENT.matcher(Files.readString(file)).replaceAll("");
        } catch (IOException e) {
            throw new IllegalStateException(file.toString(), e);
        }
    }
}
