package com.botmaker.core.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StarterSourcesTest {

    /**
     * The text a blank project begins with, built as a tree and laid out by the formatter. It reads as the text block
     * it replaced (2026-11-09) but for the blank line before {@code main}, which the formatter puts before no first
     * member, as in every file BotMaker builds.
     */
    @Test
    void theBlankFileReadsAsItAlwaysHas(@TempDir Path root) {
        Map<String, String> files = StarterSources.of(ProjectConfig.forProject("MyBot", root));
        assertEquals(Map.of("src/main/java/com/mybot/MyBot.java", """
                package com.mybot;

                /**
                 * Your project.
                 *
                 * <p>BotMaker wrote this file once, when the project was created, and will never touch it
                 * again. Every line of it is yours — rename it, split it up, throw it away.
                 *
                 * <p>This is a plain Java project: it has a pom, a source folder and this main(). To make it
                 * a bot, add the BotMaker SDK from <b>Project ▸ Plugins & Libraries</b> — it is a plugin like any
                 * other, and installing it brings the palette, the pictures, the capture tools and the rest.
                 * Or start from a published bot instead, which arrives with all of that already pinned.
                 */
                public class MyBot {
                    public static void main(String[] args) {
                        System.out.println("Hello from MyBot!");
                    }
                }
                """), files);
    }
}
