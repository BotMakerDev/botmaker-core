package com.botmaker.core.server;

import com.botmaker.cli.mcp.McpBridge;
import com.botmaker.core.assist.McpEndpoint;
import com.botmaker.core.edit.EditResult;
import com.botmaker.core.event.CoreEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan phase 11: an MCP client reaches the bot through {@code botmaker mcp-bridge} and BotMaker's core with no Studio,
 * and a run the user kept going outlives its Studio until a Studio comes back to it.
 */
class McpWithoutStudioTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CoreServer.Idle SLOW = new CoreServer.Idle(Duration.ofMinutes(10), Duration.ofSeconds(30));
    private static final String FILE = "com/mybot/Mybot.java";
    private static final String BOT = """
            package com.mybot;
            public class Mybot {
                public static void main(String[] args) throws Exception {
                    System.out.println("hello from the bot");
                    if (java.nio.file.Files.exists(java.nio.file.Path.of("loop"))) {
                        Thread.sleep(600_000);
                    }
                }
            }
            """;

    private final BlockingQueue<JsonNode> fromBridge = new LinkedBlockingQueue<>();
    private final AtomicLong ids = new AtomicLong();

    @Test
    void theBridgeOpensEditsAndRunsABotWithNoStudio(@TempDir Path cache, @TempDir Path projects) throws Exception {
        Path project = bot(projects);
        try (CoreMain.Running core = CoreMain.serve(cache, "1.0", SLOW, CoreServerTest.TEST_LIBRARIES, () -> { })) {
            McpBridge.Upstream at = new McpBridge.Upstream(
                    URI.create("http://127.0.0.1:" + core.server().port() + McpEndpoint.PATH), CoreLauncher.token(cache));
            try (PipedOutputStream toBridge = bridge(at)) {
                request(toBridge, "initialize", JSON.createObjectNode().put("protocolVersion", "2025-06-18")
                        .<ObjectNode>set("capabilities", JSON.createObjectNode())
                        .set("clientInfo", JSON.createObjectNode().put("name", "test")));
                notify(toBridge, "notifications/initialized");

                List<String> before = toolNames(toBridge);
                assertTrue(before.containsAll(List.of("list_projects", "open_project", "run_bot", "stop")), before::toString);
                assertFalse(before.contains("edit_code"), "no project open: no file tools");

                assertEquals(project.getFileName() + " is open in BotMaker's core: the file and run tools act on it.",
                        call(toBridge, "open_project", JSON.createObjectNode().put("path", project.toString())));
                assertTrue(toolNames(toBridge).contains("edit_code"), "the open project's file tools");

                String added = call(toBridge, "add_method", JSON.createObjectNode().put("file", FILE)
                        .put("name", "helper"));
                assertFalse(added.startsWith("REFUSED"), added);
                assertTrue(Files.readString(project.resolve("src/main/java").resolve(FILE)).contains("void helper()"),
                        "the edit is on disk");

                String ran = call(toBridge, "run_bot", JSON.createObjectNode().put("wait", 50));
                assertTrue(ran.contains("hello from the bot"), ran);
            }
        }
    }

    @Test
    void aKeptRunOutlivesItsStudioAndIsSeenAgain(@TempDir Path cache, @TempDir Path projects) throws Exception {
        Path project = bot(projects);
        Files.writeString(project.resolve("loop"), "");
        try (CoreMain.Running core = CoreMain.serve(cache, "1.0", SLOW, CoreServerTest.TEST_LIBRARIES, () -> { })) {
            CoreLauncher.Address at = new CoreLauncher.Address(core.server().port(), CoreLauncher.token(cache));
            List<CoreEvent> first = new CopyOnWriteArrayList<>();
            long pid;
            try (CoreClient studio = CoreClient.connect(at, "1.0", first::add, () -> { })) {
                studio.open(project);
                assertInstanceOf(EditResult.Accepted.class, studio.run());
                await(first, CoreEvent.RunProcess.class::isInstance);
                pid = first.stream().filter(CoreEvent.RunProcess.class::isInstance)
                        .map(CoreEvent.RunProcess.class::cast).findFirst().orElseThrow().pid();
                assertInstanceOf(EditResult.Accepted.class, studio.leave(true));
            }
            Thread.sleep(500);
            assertTrue(ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isPresent(), "the kept bot runs on");
            assertTrue(core.projects().busy(), "it keeps the core up");

            List<CoreEvent> again = new CopyOnWriteArrayList<>();
            try (CoreClient studio = CoreClient.connect(at, "1.0", again::add, () -> { })) {
                assertInstanceOf(EditResult.Accepted.class, studio.open(project));
                await(again, event -> event instanceof CoreEvent.RunState state
                        && state.phase() == CoreEvent.RunState.Phase.RUNNING);
                assertTrue(again.contains(new CoreEvent.RunProcess(pid)), again::toString);
                assertInstanceOf(EditResult.Accepted.class, studio.stop());
                await(again, event -> event instanceof CoreEvent.RunState state
                        && state.phase() == CoreEvent.RunState.Phase.ENDED);
            }
        }
    }

    private static Path bot(Path projects) throws IOException {
        Path project = projects.resolve("mybot");
        Path source = project.resolve("src/main/java").resolve(FILE);
        Files.createDirectories(source.getParent());
        Files.writeString(source, BOT);
        Files.writeString(project.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>com.mybot</groupId>\
                <artifactId>mybot</artifactId><version>1.0</version></project>
                """);
        return project;
    }

    private PipedOutputStream bridge(McpBridge.Upstream at) throws IOException {
        PipedInputStream in = new PipedInputStream(1 << 16);
        PipedOutputStream toBridge = new PipedOutputStream(in);
        OutputStream out = new OutputStream() {
            private final StringBuilder line = new StringBuilder();

            @Override
            public void write(int b) {
                if (b != '\n') {
                    line.append((char) b);
                    return;
                }
                try {
                    fromBridge.add(JSON.readTree(line.toString()));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                line.setLength(0);
            }
        };
        Thread.ofVirtual().start(() -> {
            try {
                new McpBridge(() -> Optional.of(at), Optional.empty(), in, out, Duration.ofMillis(200)).run();
            } catch (IOException ended) {
                // stdin closed by the test
            }
        });
        return toBridge;
    }

    private List<String> toolNames(PipedOutputStream toBridge) throws Exception {
        JsonNode listed = request(toBridge, "tools/list", JSON.createObjectNode());
        List<String> names = new ArrayList<>();
        listed.path("result").path("tools").forEach(tool -> names.add(tool.path("name").asText()));
        return names;
    }

    private String call(PipedOutputStream toBridge, String tool, ObjectNode arguments) throws Exception {
        JsonNode answer = request(toBridge, "tools/call", JSON.createObjectNode().put("name", tool)
                .set("arguments", arguments));
        return answer.path("result").path("content").path(0).path("text").asText(answer.toString());
    }

    private JsonNode request(PipedOutputStream toBridge, String method, ObjectNode params) throws Exception {
        long id = ids.incrementAndGet();
        send(toBridge, JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method)
                .set("params", params));
        long end = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        while (System.nanoTime() < end) {
            JsonNode next = fromBridge.poll(100, TimeUnit.MILLISECONDS);
            if (next != null && next.path("id").asLong(-1) == id) return next;
        }
        throw new AssertionError("no answer to " + method);
    }

    private static void notify(PipedOutputStream toBridge, String method) throws IOException {
        send(toBridge, JSON.createObjectNode().put("jsonrpc", "2.0").put("method", method));
    }

    private static void send(PipedOutputStream toBridge, JsonNode message) throws IOException {
        toBridge.write((JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8));
        toBridge.flush();
    }

    private static void await(List<CoreEvent> heard, Predicate<CoreEvent> wanted) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        while (heard.stream().noneMatch(wanted)) {
            assertTrue(System.nanoTime() < until, () -> "never heard it: " + heard);
            Thread.sleep(20);
        }
    }
}
