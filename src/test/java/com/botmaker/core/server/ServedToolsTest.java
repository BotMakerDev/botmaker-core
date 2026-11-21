// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.server;

import com.botmaker.core.assist.CoreHome;
import com.botmaker.core.assist.RunLog;
import com.botmaker.core.edit.EditResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServedToolsTest {

    private static final CoreServer.Idle SLOW = new CoreServer.Idle(Duration.ofMinutes(10), Duration.ofSeconds(30));

    private final McpJsonMapper json = new JacksonMcpJsonMapper(new ObjectMapper());

    /**
     * Studio registers its own tools on attaching, which stand in for the core's home and run tools; it has no
     * {@code device}, so the core's must stay (click-test, 2026-11-11: the Assistant window's session had none).
     */
    @Test
    void theDeviceToolStaysServedWithAClientAttached() throws Exception {
        ServedTools tools = new ServedTools();
        CoreHome.Host host = (CoreHome.Host) Proxy.newProxyInstance(CoreHome.Host.class.getClassLoader(),
                new Class<?>[]{CoreHome.Host.class}, (proxy, method, args) -> null);
        try (RunLog log = new RunLog(Clock.systemUTC(), () -> false)) {
            tools.fallback(CoreHome.served(json, host, log));
            tools.held(CoreHome.device(json));
            tools.lasting(CoreHome.lasting(json, host));
            assertTrue(tools.chosen().containsKey("device"), "with no client");

            Channel studio = new Channel("studio", null, null);
            tools.register(studio, new Request.Tools(1, List.of(tool("run_bot"), tool("ui")), true));
            Set<String> served = tools.chosen().keySet();
            assertTrue(served.contains("device"), served::toString);
            assertTrue(served.containsAll(List.of("run_bot", "ui", "core_status", "quit_core", "close_project",
                    "reload_plugins")), served::toString);
            assertFalse(served.contains("list_projects"), "a home tool still gives way to the client's: " + served);
        }
    }

    /** {@code core_status} hands out a fresh link to the core's dashboard, which a browser opens without the token. */
    @Test
    void coreStatusGivesADashboardLink(@TempDir Path cache) throws Exception {
        try (CoreMain.Running core = CoreMain.serve(cache, "1.0", SLOW, CoreServerTest.TEST_LIBRARIES, () -> { })) {
            String prefix = "http://127.0.0.1:" + core.server().port() + "/dashboard?key=";
            String status = core.projects().coreStatus();
            assertTrue(status.contains(prefix), status);
            Matcher link = Pattern.compile(Pattern.quote(prefix) + "[0-9a-f]+").matcher(status);
            assertTrue(link.find(), status);

            HttpResponse<Void> traded = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(link.group())).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(303, traded.statusCode(), "the key is traded for the page's cookie");
            assertFalse(core.projects().coreStatus().contains(link.group()), "each status gives a new key");
        }
    }

    /** {@code botmaker core} reads the page's feed with the token it holds, no key and no cookie (plan phase 6). */
    @Test
    void theBearerReadsTheDashboardFeed(@TempDir Path cache) throws Exception {
        try (CoreMain.Running core = CoreMain.serve(cache, "1.0", SLOW, CoreServerTest.TEST_LIBRARIES, () -> { })) {
            URI events = URI.create("http://127.0.0.1:" + core.server().port() + "/dashboard/events");
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<InputStream> feed = http.send(HttpRequest.newBuilder(events)
                    .header("Authorization", "Bearer " + CoreLauncher.token(cache)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, feed.statusCode());
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(feed.body(), StandardCharsets.UTF_8))) {
                assertEquals("event: inside", lines.readLine(), "the snapshot comes first");
            }
            HttpResponse<Void> forged = http.send(HttpRequest.newBuilder(events)
                    .header("Authorization", "Bearer forged").GET().build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(401, forged.statusCode());
        }
    }

    /** {@code close_project} closes what the MCP opened, and says so when nothing is open. */
    @Test
    void closeProjectClosesWhatNoClientHolds(@TempDir Path cache, @TempDir Path projects) throws Exception {
        Path project = bot(projects);
        try (CoreMain.Running core = CoreMain.serve(cache, "1.0", SLOW, CoreServerTest.TEST_LIBRARIES, () -> { })) {
            core.projects().open(project);
            assertTrue(core.projects().reloadPlugins().startsWith("mybot"), "reloaded with it open");
            assertEquals("mybot is closed.", core.projects().closeProject(false));
            assertTrue(core.projects().projectDir().isEmpty(), "nothing is open after");
            IllegalArgumentException none = assertThrows(IllegalArgumentException.class,
                    () -> core.projects().closeProject(false));
            assertEquals("No project is open.", none.getMessage());
        }
    }

    /** A project Studio holds is Studio's to close: {@code close_project} refuses and leaves it open. */
    @Test
    void closeProjectLeavesWhatStudioHolds(@TempDir Path cache, @TempDir Path projects) throws Exception {
        Path project = bot(projects);
        try (CoreMain.Running core = CoreMain.serve(cache, "1.0", SLOW, CoreServerTest.TEST_LIBRARIES, () -> { })) {
            CoreLauncher.Address at = new CoreLauncher.Address(core.server().port(), CoreLauncher.token(cache));
            try (CoreClient studio = CoreClient.connect(at, "1.0", event -> { }, () -> { })) {
                assertInstanceOf(EditResult.Accepted.class, studio.open(project));
                IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                        () -> core.projects().closeProject(false));
                assertEquals("Studio holds mybot: close it there.", refused.getMessage());
                assertTrue(core.projects().projectDir().isPresent(), "still open");
            }
        }
    }

    @Test
    void reloadPluginsWithNothingOpenIsRefused(@TempDir Path cache) throws Exception {
        try (CoreMain.Running core = CoreMain.serve(cache, "1.0", SLOW, CoreServerTest.TEST_LIBRARIES, () -> { })) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> core.projects().reloadPlugins());
            assertTrue(refused.getMessage().startsWith("No project is open"), refused.getMessage());
        }
    }

    private static Path bot(Path projects) throws IOException {
        Path project = projects.resolve("mybot");
        Path source = project.resolve("src/main/java/com/mybot/Mybot.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package com.mybot;\npublic class Mybot {\n    public static void main(String[] args) {\n"
                + "    }\n}\n");
        Files.writeString(project.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>com.mybot</groupId>\
                <artifactId>mybot</artifactId><version>1.0</version></project>
                """);
        return project;
    }

    private String tool(String name) throws Exception {
        return json.writeValueAsString(McpSchema.Tool.builder().name(name).description(name)
                .inputSchema(new McpSchema.JsonSchema("object", null, null, null, null, null)).build());
    }
}
