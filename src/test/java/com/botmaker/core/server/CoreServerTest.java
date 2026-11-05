package com.botmaker.core.server;

import com.botmaker.core.TestSources;
import com.botmaker.core.assist.McpEndpoint;
import com.botmaker.core.edit.Edit;
import com.botmaker.core.edit.EditRequest;
import com.botmaker.core.edit.EditResult;
import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectState;
import com.botmaker.java.edit.BlockId;
import com.botmaker.java.types.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreServerTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final CoreServer.Idle SLOW = new CoreServer.Idle(Duration.ofMinutes(10), Duration.ofSeconds(30));

    @Test
    void everyPathButTheHelloAsksForTheToken() throws Exception {
        try (CoreServer server = CoreServer.start(0, TOKEN, "1.0", hooks(SLOW, () -> { }))) {
            CoreLauncher.Address at = new CoreLauncher.Address(server.port(), TOKEN);
            HttpClient http = HttpClient.newHttpClient();
            assertEquals(401, http.send(HttpRequest.newBuilder(at.uri(CoreServer.INFO)).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            String nonce = "00112233445566778899aabbccddeeff";
            String proof = http.send(HttpRequest.newBuilder(at.uri(McpEndpoint.HELLO + "?nonce=" + nonce)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertEquals(McpEndpoint.proof(TOKEN, server.port(), nonce), proof);
            assertEquals("1.0", CoreLauncher.info(at).orElseThrow().version());
        }
    }

    @Test
    void aChannelCarriesMessagesBothWaysAndCountsAsAClient() throws Exception {
        CoreServer.Hooks echo = new CoreServer.Hooks(SLOW, () -> false,
                channel -> channel.onMessage(message -> channel.send("{\"echo\":" + message + "}")), () -> { });
        try (CoreServer server = CoreServer.start(0, TOKEN, "1.0", echo)) {
            BlockingQueue<String> heard = new LinkedBlockingQueue<>();
            CountDownLatch ended = new CountDownLatch(1);
            ChannelClient client = ChannelClient.open(new CoreLauncher.Address(server.port(), TOKEN), "1.0",
                    heard::add, ended::countDown);
            assertEquals(1, server.clients());
            client.send("1");
            client.send("2");
            assertEquals("{\"echo\":1}", heard.poll(5, TimeUnit.SECONDS));
            assertEquals("{\"echo\":2}", heard.poll(5, TimeUnit.SECONDS));
            client.close();
            assertTrue(ended.await(5, TimeUnit.SECONDS));
            assertEquals(0, server.clients());
        }
    }

    @Test
    void aClientOfAnotherVersionIsRefusedNamingBoth() throws Exception {
        try (CoreServer server = CoreServer.start(0, TOKEN, "1.0", hooks(SLOW, () -> { }))) {
            CoreLauncher.Refused refused = assertThrows(CoreLauncher.Refused.class, () -> ChannelClient.open(
                    new CoreLauncher.Address(server.port(), TOKEN), "2.0", m -> { }, () -> { }));
            assertTrue(refused.getMessage().contains("1.0") && refused.getMessage().contains("2.0"),
                    refused.getMessage());
            assertEquals(0, server.clients());
        }
    }

    @Test
    void itEndsWhenIdleButNotWhileAClientIsAttached() throws Exception {
        CountDownLatch exited = new CountDownLatch(1);
        CoreServer.Idle quick = new CoreServer.Idle(Duration.ofMillis(300), Duration.ofMillis(50));
        try (CoreServer server = CoreServer.start(0, TOKEN, "1.0", hooks(quick, exited::countDown))) {
            ChannelClient client = ChannelClient.open(new CoreLauncher.Address(server.port(), TOKEN), "1.0",
                    m -> { }, () -> { });
            assertFalse(exited.await(800, TimeUnit.MILLISECONDS));
            client.close();
            assertTrue(exited.await(5, TimeUnit.SECONDS));
        }
    }

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

    /** The project's libraries are this test's classpath; nothing is resolved or bound. */
    private static final OpenProject.Libraries TEST_LIBRARIES = new OpenProject.Libraries() {
        @Override
        public List<String> classpath(ProjectConfig config) {
            return TestSources.runtimeClassPath();
        }

        @Override
        public ProjectAnalyzer analyzer(List<String> classpath, ProjectState state) {
            return new ProjectAnalyzer(null, state);
        }

        @Override
        public void bind(ProjectConfig config, List<String> classpath) {
        }

        @Override
        public void unbind() {
        }
    };

    /**
     * Review focus 1: two clients delete a line of one method at once. The second runs on what the first left, so it
     * lands or is refused saying why; every accepted edit reaches both clients in the same order, and the disk holds
     * the last one.
     */
    @Test
    void twoClientsEditOneMethod(@TempDir Path cache, @TempDir Path projects) throws Exception {
        Path project = projects.resolve("mybot");
        Path source = project.resolve("src/main/java").resolve(FILE);
        Files.createDirectories(source.getParent());
        Files.writeString(source, BOT);
        CompilationUnit tree = ProjectAnalyzer.createCompilationUnit(List.of(), BOT, source.getParent());
        Block body = ((TypeDeclaration) tree.types().getFirst()).getMethods()[0].getBody();
        String first = BlockId.of((ASTNode) body.statements().get(0));
        String second = BlockId.of((ASTNode) body.statements().get(1));

        try (CoreMain.Running core = CoreMain.serve(cache, "1.0", SLOW, TEST_LIBRARIES, () -> { })) {
            CoreLauncher.Address at = new CoreLauncher.Address(core.server().port(), CoreLauncher.token(cache));
            List<CoreEvent> heardByA = new CopyOnWriteArrayList<>();
            List<CoreEvent> heardByB = new CopyOnWriteArrayList<>();
            try (CoreClient a = CoreClient.connect(at, "1.0", heardByA::add, () -> { });
                 CoreClient b = CoreClient.connect(at, "1.0", heardByB::add, () -> { })) {
                assertInstanceOf(EditResult.Accepted.class, a.open(project));
                assertInstanceOf(EditResult.Accepted.class, b.open(project));
                assertInstanceOf(EditResult.Accepted.class, a.show(FILE));

                CyclicBarrier together = new CyclicBarrier(2);
                CompletableFuture<EditResult> byA = CompletableFuture.supplyAsync(
                        () -> deleteTogether(a, second, together));
                CompletableFuture<EditResult> byB = CompletableFuture.supplyAsync(
                        () -> deleteTogether(b, first, together));
                List<EditResult> results = List.of(byA.get(30, TimeUnit.SECONDS), byB.get(30, TimeUnit.SECONDS));

                long accepted = results.stream().filter(EditResult.Accepted.class::isInstance).count();
                assertTrue(accepted >= 1, "the first edit to arrive lands: " + results);
                results.stream().filter(EditResult.Refused.class::isInstance)
                        .forEach(no -> assertFalse(((EditResult.Refused) no).reason().isBlank()));
                List<String> toA = changes(heardByA, accepted);
                List<String> toB = changes(heardByB, accepted);
                assertEquals(toA, toB, "both clients hear every change, in one order");
                assertEquals(toA.getLast(), Files.readString(source), "the disk holds the last accepted edit");
            }
        }
    }

    private static EditResult deleteTogether(CoreClient client, String line, CyclicBarrier together) {
        try {
            together.await(10, TimeUnit.SECONDS);
            return client.apply(new EditRequest(FILE, List.of(new Edit.Delete(line))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The sources of the first {@code count} changes {@code heard}, once they have all arrived. */
    private static List<String> changes(List<CoreEvent> heard, long count) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        List<String> sources;
        do {
            sources = heard.stream().filter(CoreEvent.MethodsChanged.class::isInstance)
                    .map(event -> ((CoreEvent.MethodsChanged) event).source()).toList();
            if (sources.size() >= count) return sources;
            Thread.sleep(20);
        } while (System.nanoTime() < until);
        throw new AssertionError("heard " + sources.size() + " of " + count + " changes: " + heard);
    }

    private static CoreServer.Hooks hooks(CoreServer.Idle idle, Runnable exit) {
        return new CoreServer.Hooks(idle, () -> false, channel -> { }, exit);
    }
}
