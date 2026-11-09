package com.botmaker.core.parser.refactor;

import com.botmaker.core.project.vcs.ProjectVcs;
import com.botmaker.core.project.vcs.VersionOrigin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review's Undo, moved from Studio's {@code ReviewService} (2026-11-09): a marked function put back from a version. */
class FunctionRestoreTest {

    private static final String IMPORT = "import com.botmaker.plugin.api.meta.Refactor;";

    @Test
    void theFunctionComesBackFromTheVersionBeforeTheMark(@TempDir Path root) throws IOException {
        Path file = write(root, """
                package com.refbot;

                import java.util.List;

                class Miner {
                    void mine() {
                        List.of(1);
                    }

                    void rest() {}
                }
                """);
        new ProjectVcs(root).checkpoint(VersionOrigin.SAFETY, "Before removing SDK");
        String marked = """
                package com.refbot;

                %s

                class Miner {
                    @Refactor("List.of is gone.")
                    void mine() {
                    }

                    void rest() {}
                }
                """.formatted(IMPORT);

        FunctionRestore.Outcome.Restored restored = assertInstanceOf(FunctionRestore.Outcome.Restored.class,
                FunctionRestore.restore(root, file, marked, "mine", List.of("List.of is gone.")));

        assertFalse(restored.version().isBlank(), "the version it came back from is named");
        assertTrue(restored.source().contains("List.of(1);"), restored.source());
        assertTrue(restored.source().contains("import java.util.List;"),
                "the import the old body used comes back: " + restored.source());
        assertFalse(restored.source().contains("@Refactor(\"List.of"), restored.source());
    }

    @Test
    void onlyTheImportsTheOldBodyUsesComeBack(@TempDir Path root) throws IOException {
        Path file = write(root, """
                package com.refbot;

                import java.util.List;
                import java.util.Set;

                class Miner {
                    void mine() {
                        // a List would do
                        Set.of("List");
                    }

                    Object kept = List.of();
                }
                """);
        new ProjectVcs(root).checkpoint(VersionOrigin.SAFETY, "Before removing SDK");
        String marked = """
                package com.refbot;

                %s

                class Miner {
                    @Refactor("Set.of is gone.")
                    void mine() {
                    }
                }
                """.formatted(IMPORT);

        String now = assertInstanceOf(FunctionRestore.Outcome.Restored.class,
                FunctionRestore.restore(root, file, marked, "mine", List.of("Set.of is gone."))).source();

        assertTrue(now.contains("import java.util.Set;"), now);
        assertFalse(now.contains("import java.util.List;"), "a comment and a string name no class: " + now);
    }

    @Test
    void theMarkedOverloadComesBackNotAnotherOfTheSameName(@TempDir Path root) throws IOException {
        Path file = write(root, """
                package com.refbot;

                class Miner {
                    void mine() {}

                    void mine(int depth) {
                        System.out.println(depth);
                    }
                }
                """);
        new ProjectVcs(root).checkpoint(VersionOrigin.SAFETY, "Before");
        String marked = """
                package com.refbot;

                %s

                class Miner {
                    void mine() {}

                    @Refactor("depth went")
                    void mine(int depth) {
                    }
                }
                """.formatted(IMPORT);

        String now = assertInstanceOf(FunctionRestore.Outcome.Restored.class,
                FunctionRestore.restore(root, file, marked, "mine", List.of("depth went"))).source();

        assertTrue(now.contains("void mine() {}"), now);
        assertTrue(now.contains("System.out.println(depth);"), now);
    }

    /** A parameter type written another way since, here qualified then imported, is the same overload. */
    @Test
    void aParameterTypeWrittenAnotherWayIsTheSameOverload(@TempDir Path root) throws IOException {
        Path file = write(root, """
                package com.refbot;

                class Miner {
                    void mine(java.util.List<String> veins) {
                        System.out.println(veins);
                    }
                }
                """);
        new ProjectVcs(root).checkpoint(VersionOrigin.SAFETY, "Before");
        String marked = """
                package com.refbot;

                import java.util.List;
                %s

                class Miner {
                    @Refactor("veins went")
                    void mine(List<String> veins) {
                    }
                }
                """.formatted(IMPORT);

        String now = assertInstanceOf(FunctionRestore.Outcome.Restored.class,
                FunctionRestore.restore(root, file, marked, "mine", List.of("veins went"))).source();

        assertTrue(now.contains("System.out.println(veins);"), now);
    }

    @Test
    void noVersionBeforeTheMarkIsRefused(@TempDir Path root) throws IOException {
        String marked = """
                package com.refbot;

                %s

                class Miner {
                    @Refactor("guessed")
                    void mine() {}
                }
                """.formatted(IMPORT);
        Path file = write(root, marked);

        assertInstanceOf(FunctionRestore.Outcome.Refused.class,
                FunctionRestore.restore(root, file, marked, "mine", List.of("guessed")));
    }

    @Test
    void aFunctionNoLongerMarkedSoIsRefused(@TempDir Path root) throws IOException {
        String source = """
                package com.refbot;

                class Miner {
                    void mine() {}
                }
                """;
        Path file = write(root, source);

        assertInstanceOf(FunctionRestore.Outcome.Refused.class,
                FunctionRestore.restore(root, file, source, "mine", List.of("guessed")));
    }

    private static Path write(Path root, String source) throws IOException {
        Path file = root.resolve("src/main/java/com/refbot/Miner.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }
}
