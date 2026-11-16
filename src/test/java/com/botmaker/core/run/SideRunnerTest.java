// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.run;

import com.botmaker.core.project.ProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SideRunnerTest {

    @TempDir
    Path dir;

    @Test
    void aFolderWithNoBotIsRefused() {
        IOException refused = assertThrows(IOException.class, () -> new SideRunner().start(dir, Map.of(), Map.of()));
        assertTrue(refused.getMessage().startsWith("There is no bot in"), refused.getMessage());
    }

    @Test
    void aParameterTheRunnerLacksIsCompiledFromItsStartingCopy() throws IOException {
        Path main = write("src/main/java/com/demo/Demo.java", "package com.demo; public class Demo { public static void main(String[] args) {} }");
        Path kept = write("src/main/java/com/demo/params/bot/Kept.java", "package com.demo.params.bot; class Kept {}");
        write("starting-params/bot/Kept.java", "package com.demo.params.bot; class Kept {}");
        Path missing = write("starting-params/bot/Missing.java", "package com.demo.params.bot; class Missing {}");
        write("pom.xml", "<project/>");

        List<Path> sources = SideRunner.sources(ProjectConfig.forDirectory(dir)).stream()
                .map(source -> Path.of(source).toAbsolutePath().normalize()).sorted().toList();

        assertEquals(List.of(main, kept, missing).stream().map(path -> path.toAbsolutePath().normalize()).sorted()
                .toList(), sources);
    }

    private Path write(String relative, String text) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }
}
