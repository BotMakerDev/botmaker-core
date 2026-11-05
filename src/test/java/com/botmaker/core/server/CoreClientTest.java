package com.botmaker.core.server;

import com.botmaker.core.edit.Edit;
import com.botmaker.core.edit.EditRequest;
import com.botmaker.core.edit.EditResult;
import com.botmaker.java.edit.BlockId;
import com.botmaker.java.types.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.botmaker.core.assist.McpEndpoint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A client's tie to the core outlives the core: {@link CoreLink}. */
class CoreClientTest {

    private static final CoreServer.Idle SLOW = new CoreServer.Idle(Duration.ofMinutes(10), Duration.ofSeconds(30));
    private static final String BOT = """
            package com.mybot;
            public class Mybot {
                void run() {
                    int a = 1;
                    int b = a;
                }
            }
            """;
    private static final String FILE = "com/mybot/Mybot.java";

    private final List<CoreMain.Running> cores = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopCores() {
        cores.forEach(CoreMain.Running::close);
    }

    /**
     * Review focus 2: the core is killed while a project is open. The client hears that the core stopped, a new core is
     * started and the project reopened on it, and the files are as the last accepted edit left them; the next edit lands
     * on the new core.
     */
    @Test
    void aKilledCoreIsRestarted(@TempDir Path cache, @TempDir Path projects) throws Exception {
        Path project = projects.resolve("mybot");
        Path source = project.resolve("src/main/java").resolve(FILE);
        Files.createDirectories(source.getParent());
        Files.writeString(source, BOT);
        CompilationUnit tree = ProjectAnalyzer.createCompilationUnit(List.of(), BOT, source.getParent());
        Block body = ((TypeDeclaration) tree.types().getFirst()).getMethods()[0].getBody();
        String second = BlockId.of((ASTNode) body.statements().get(1));

        CountDownLatch stopped = new CountDownLatch(1);
        CountDownLatch restarted = new CountDownLatch(1);
        List<IOException> lost = new CopyOnWriteArrayList<>();
        CoreLauncher.Starter starter = dir -> {
            try {
                cores.add(CoreMain.serve(dir, "1.0", SLOW, CoreServerTest.TEST_LIBRARIES, () -> { }));
            } catch (Exception e) {
                throw new IOException(e);
            }
        };
        try (CoreLink link = CoreLink.connect(cache, "1.0", starter, event -> { }, call -> { },
                new CoreLink.Listener() {
                    @Override
                    public void stopped() {
                        stopped.countDown();
                    }

                    @Override
                    public void restarted() {
                        restarted.countDown();
                    }

                    @Override
                    public void lost(IOException why) {
                        lost.add(why);
                    }
                })) {
            link.rejoin(client -> {
                if (!(client.open(project) instanceof EditResult.Accepted)) throw new IOException("not reopened");
                if (!(client.show(FILE) instanceof EditResult.Accepted)) throw new IOException("not shown");
            });
            CoreClient first = link.client();
            assertInstanceOf(EditResult.Accepted.class, first.open(project));
            assertInstanceOf(EditResult.Accepted.class, first.show(FILE));
            assertInstanceOf(EditResult.Accepted.class,
                    first.apply(new EditRequest(FILE, List.of(new Edit.Delete(second)))));
            String edited = Files.readString(source);
            assertFalse(edited.contains("int b"), edited);

            cores.getFirst().close();

            assertTrue(stopped.await(10, TimeUnit.SECONDS), "the client heard the core stop");
            assertTrue(restarted.await(30, TimeUnit.SECONDS), "a new core took over: " + lost);
            assertEquals(2, cores.size(), "a new core was started");
            CoreClient now = link.client();
            assertNotSame(first, now);
            assertEquals(edited, Files.readString(source), "the files are as the last accepted edit left them");

            CompilationUnit after = ProjectAnalyzer.createCompilationUnit(List.of(), edited, source.getParent());
            Block left = ((TypeDeclaration) after.types().getFirst()).getMethods()[0].getBody();
            String only = BlockId.of((ASTNode) left.statements().getFirst());
            assertInstanceOf(EditResult.Accepted.class,
                    now.apply(new EditRequest(FILE, List.of(new Edit.Delete(only)))));
            assertFalse(Files.readString(source).contains("int a"));
        }
    }

    /**
     * A client's tools are the core's MCP (plan phase 10d): registered over the channel, listed, and each call forwarded
     * to the client, whose answer is the call's; they go with the client.
     */
    @Test
    void aClientsToolIsServedAndItsCallsForwarded(@TempDir Path cache) throws Exception {
        cores.add(CoreMain.serve(cache, "1.0", SLOW, CoreServerTest.TEST_LIBRARIES, () -> { }));
        CoreLauncher.Address at = CoreLauncher.connect(cache, "1.0", CoreLauncher.process());
        List<Reply.Call> calls = new CopyOnWriteArrayList<>();
        CoreClient[] self = new CoreClient[1];
        self[0] = CoreClient.connect(at, "1.0", event -> { }, call -> {
            calls.add(call);
            // Answered off the channel's reader, which reads the answer.
            Thread.ofVirtual().start(() -> {
                try {
                    self[0].called(call.call(), "{\"content\":[{\"type\":\"text\",\"text\":\"echo " + call.arguments()
                            .replace("\"", "\\\"") + "\"}],\"isError\":false}");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }, () -> { });
        try (CoreClient client = self[0]) {
            String echo = "{\"name\":\"echo\",\"description\":\"Says it back.\",\"inputSchema\":{\"type\":\"object\","
                    + "\"properties\":{\"say\":{\"type\":\"string\"}}}}";
            assertInstanceOf(EditResult.Accepted.class, client.tools(List.of(echo), false));

            Mcp mcp = new Mcp(at);
            assertTrue(mcp.rpc("tools/list", "{}").contains("\"echo\""), "the client's tool is listed");
            String answer = mcp.rpc("tools/call", "{\"name\":\"echo\",\"arguments\":{\"say\":\"hi\"}}");
            assertTrue(answer.contains("echo {\\\"say\\\":\\\"hi\\\"}"), answer);
            assertEquals(Reply.Call.Kind.RUN, calls.getFirst().kind());
            assertEquals("echo", calls.getFirst().tool());

            assertInstanceOf(EditResult.Refused.class, client.tools(List.of("not a tool"), false));
        }
        Mcp after = new Mcp(at);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (after.rpc("tools/list", "{}").contains("\"echo\"") && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(after.rpc("tools/list", "{}").contains("\"echo\""), "a client's tools go with it");
    }

    /** A bare MCP client over HTTP: initialized once, each answer read out of its SSE frame when it is one. */
    private static final class Mcp {
        private final HttpClient http = HttpClient.newHttpClient();
        private final CoreLauncher.Address at;
        private String session;
        private int ids = 1;

        Mcp(CoreLauncher.Address at) throws Exception {
            this.at = at;
            rpc("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}");
            post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        }

        String rpc(String method, String params) throws Exception {
            String body = post("{\"jsonrpc\":\"2.0\",\"id\":" + (ids++) + ",\"method\":\"" + method
                    + "\",\"params\":" + params + "}");
            Matcher data = Pattern.compile("(?m)^data:\\s*(\\{.*})\\s*$").matcher(body);
            return data.find() ? data.group(1) : body;
        }

        private String post(String body) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(at.uri(McpEndpoint.PATH))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("Authorization", "Bearer " + at.token())
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (session != null) request.header("Mcp-Session-Id", session);
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            response.headers().firstValue("Mcp-Session-Id").ifPresent(id -> session = id);
            return response.body();
        }
    }
}
