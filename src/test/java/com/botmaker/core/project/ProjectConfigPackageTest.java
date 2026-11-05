package com.botmaker.core.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bot cloned from the gallery keeps its author's package in a folder its user named (2026-10-23): the package is
 * read off the class holding {@code main}, never derived from the folder.
 */
class ProjectConfigPackageTest {

    /** A cloned bot in {@code MyFarm}: its main in {@code com.farm}, a helper beside it. */
    private static Path clone(Path root) throws IOException {
        Path dir = root.resolve("MyFarm");
        Path sources = dir.resolve("src/main/java/com/farm");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("FarmBot.java"), """
                package com.farm;

                public class FarmBot {
                    public static void main(String[] args) {}
                }
                """);
        Files.writeString(sources.resolve("Helper.java"), "package com.farm;\nfinal class Helper {}\n");
        return dir;
    }

    @Test
    void aClonedBotKeepsItsAuthorsPackageAndEntryClass(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forDirectory(clone(root));

        assertEquals("MyFarm", config.projectName());
        assertEquals("farm", config.packageName());
        assertEquals("com.farm.FarmBot", config.entryClassName());
    }

    @Test
    void aMainFurtherDownDoesNotMoveThePackage(@TempDir Path root) throws IOException {
        Path dir = clone(root);
        Path tools = dir.resolve("src/main/java/com/farm/tools");
        Files.createDirectories(tools);
        Files.writeString(tools.resolve("Probe.java"), """
                package com.farm.tools;
                public class Probe { public static void main(String[] a) {} }
                """);

        assertEquals(Optional.of("com.farm"), ProjectConfig.mainPackage(dir));
    }

    @Test
    void aMainInACommentOrAStringIsNoMain(@TempDir Path root) throws IOException {
        Path sources = root.resolve("src/main/java/com/farm");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("Notes.java"), """
                package com.farm;

                // public static void main(String[] args) {}
                class Notes {
                    String how = "public static void main(String[] args)";
                }
                """);

        assertFalse(ProjectConfig.hasMain(root));
        assertEquals(Optional.empty(), ProjectConfig.mainPackage(root));
    }

    @Test
    void aPackageInACommentIsNotThePackage(@TempDir Path root) throws IOException {
        Path sources = root.resolve("src/main/java/com/farm");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("FarmBot.java"), """
                /*
                 package com.old;
                 */
                package com.farm;

                public class FarmBot {
                    public static void main(String[] args) {}
                }
                """);

        assertEquals(Optional.of("com.farm"), ProjectConfig.mainPackage(root));
    }

    @Test
    void aMainWithABrokenBodyIsStillAMain(@TempDir Path root) throws IOException {
        Path sources = root.resolve("src/main/java/com/farm");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("FarmBot.java"), """
                package com.farm;

                public class FarmBot {
                    public static void main(String[] args) {
                        int a = ;
                    }
                }
                """);

        assertTrue(ProjectConfig.hasMain(root));
        assertEquals(Optional.of("com.farm"), ProjectConfig.mainPackage(root));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "public clas F { public static void main(String[] args) {} }",
            "public class { public static void main(String[] args) {} }",
            "class F { void f( { static public void main(String[] args) {} }"})
    void aMainInAClassThatDoesNotParseIsStillAMain(String body, @TempDir Path root) throws IOException {
        Path sources = root.resolve("src/main/java/com/farm");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("F.java"), "package com.farm;\n" + body + "\n");

        assertTrue(ProjectConfig.hasMain(root));
        assertEquals(Optional.of("com.farm"), ProjectConfig.mainPackage(root));
    }

    @Test
    void whatTheJvmCannotStartIsNoMain(@TempDir Path root) throws IOException {
        Path sources = root.resolve("src/main/java/com/farm");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("F.java"), """
                package com.farm;
                public class F {
                    public static int main(String[] args) { return 0; }
                    public static void main(int count) {}
                    void f() {
                        class Local { public static void main(String[] args) {} }
                    }
                }
                """);

        assertFalse(ProjectConfig.hasMain(root));
    }

    @Test
    void aMainWithNoParametersIsAMain(@TempDir Path root) throws IOException {
        Path sources = root.resolve("src/main/java/com/farm");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("F.java"), "package com.farm;\npublic class F { public static void main() {} }\n");

        assertTrue(ProjectConfig.hasMain(root));
    }

    @Test
    void noMainOrMainsInUnrelatedPackagesFallBackToTheFolder(@TempDir Path root) throws IOException {
        Path dir = clone(root);
        Path other = dir.resolve("src/main/java/org/other");
        Files.createDirectories(other);
        Files.writeString(other.resolve("Other.java"), """
                package org.other;
                public class Other { public static void main(String[] a) {} }
                """);
        assertEquals(Optional.empty(), ProjectConfig.mainPackage(dir));
        assertEquals("myfarm", ProjectConfig.forDirectory(dir).packageName());

        Files.delete(other.resolve("Other.java"));
        Files.delete(dir.resolve("src/main/java/com/farm/FarmBot.java"));
        assertEquals(Optional.empty(), ProjectConfig.mainPackage(dir));
    }
}
