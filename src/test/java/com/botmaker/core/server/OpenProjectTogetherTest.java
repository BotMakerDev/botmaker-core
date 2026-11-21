// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.server;

import com.botmaker.core.edit.EditResult;
import com.botmaker.core.edit.HistoryStep;
import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.plugin.PluginWrite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A client's together in the core (2026-11-21): one request, one step of the history, and the open file redrawn once
 * at its end rather than after each write; a together refused leaves the file and tells nothing of it.
 */
class OpenProjectTogetherTest {

    private static final CoreServer.Idle SLOW = new CoreServer.Idle(Duration.ofMinutes(10), Duration.ofSeconds(30));
    private static final String FILE = "com/mybot/Mybot.java";
    private static final String BOT = """
            package com.mybot;
            public class Mybot {
                void run() {
                    int a = 1;
                }
            }
            """;

    @Test
    void oneRedrawForAWholeTogether(@TempDir Path cache, @TempDir Path projects) throws Exception {
        Path project = projects.resolve("mybot");
        Path source = project.resolve("src/main/java").resolve(FILE);
        Files.createDirectories(source.getParent());
        Files.writeString(source, BOT);
        List<CoreEvent> heard = new CopyOnWriteArrayList<>();

        try (CoreMain.Running core = CoreMain.serve(cache, "1.0", SLOW, CoreServerTest.TEST_LIBRARIES, () -> { })) {
            CoreLauncher.Address at = new CoreLauncher.Address(core.server().port(), CoreLauncher.token(cache));
            try (CoreClient client = CoreClient.connect(at, "1.0", heard::add, () -> { })) {
                client.open(project);
                client.show(FILE);
                List<PluginWrite> writes = new ArrayList<>();
                for (int n = 2; n <= 6; n++) writes.add(set(source, n));
                heard.clear();

                assertInstanceOf(EditResult.Accepted.class, client.together("Tidy the map", writes));

                String written = BOT.replace("int a = 1;", "int a = 6;");
                assertEquals(written, Files.readString(source));
                assertEquals(1, redraws(heard), heard::toString);
                assertTrue(heard.stream().anyMatch(event -> event instanceof CoreEvent.MethodsChanged changed
                        && changed.source().equals(written)), heard::toString);
                assertTrue(heard.stream().anyMatch(event -> event instanceof CoreEvent.HistoryChanged history
                        && history.undoLabel().equals("Tidy the map")), heard::toString);

                heard.clear();
                assertInstanceOf(EditResult.Refused.class, client.together("Tidy again", List.of(set(source, 7),
                        new PluginWrite.SetValue("com.example:none:GONE", null))));
                assertEquals(written, Files.readString(source));
                assertEquals(0, redraws(heard), heard::toString);

                // The whole together is one step: one undo puts the file back.
                assertInstanceOf(EditResult.Accepted.class, client.step(HistoryStep.UNDO));
                assertEquals(BOT, Files.readString(source));
            }
        }
    }

    private static PluginWrite set(Path source, int n) {
        return new PluginWrite.ApplyPlan("Set a", Map.of(source.toString(), BOT.replace("int a = 1;",
                "int a = " + n + ";")), Map.of());
    }

    private static long redraws(List<CoreEvent> heard) {
        return heard.stream().filter(CoreEvent.MethodsChanged.class::isInstance).count();
    }
}
