// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.server;

import com.botmaker.core.CoreJson;
import com.botmaker.core.dashboard.DashboardAccess;
import com.botmaker.core.edit.EditResult;
import com.botmaker.core.pilot.PilotState;
import com.botmaker.core.pilot.transport.TransportKind;
import com.botmaker.core.pilot.PilotServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The core's doors to the Remote Pilot: {@code POST /pilot}, which only the bearer opens, and a channel's asks. */
class PilotEndpointTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final CoreServer.Idle SLOW = new CoreServer.Idle(Duration.ofMinutes(10), Duration.ofSeconds(30));
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static HttpResponse<String> post(CoreLauncher.Address at, String body, String header, String value)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(at.uri(CoreServer.PILOT))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (header != null) request.header(header, value);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static CoreServer serve(Projects projects) throws Exception {
        return CoreServer.start(0, TOKEN, "1.0", new CoreServer.Hooks(SLOW, projects::busy, projects::attach,
                () -> { }, () -> null, projects.feed(), projects));
    }

    @Test
    void theBearerAloneOpensIt() throws Exception {
        try (Projects projects = new Projects(OpenProject.Libraries.MAVEN);
             CoreServer server = serve(projects)) {
            CoreLauncher.Address at = new CoreLauncher.Address(server.port(), TOKEN);
            assertEquals(401, post(at, "{\"act\":\"status\"}", null, null).statusCode());
            assertEquals(401, post(at, "{\"act\":\"status\"}", "Authorization", "Bearer wrong").statusCode());

            // A dashboard tab's cookie opens the dashboard, never the link this hands out.
            HttpResponse<String> key = HTTP.send(HttpRequest.newBuilder(at.uri(DashboardAccess.KEY_PATH))
                    .header("Authorization", "Bearer " + TOKEN).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            URI link = URI.create(new ObjectMapper().readTree(key.body()).path("url").asText());
            HttpResponse<String> traded = HTTP.send(HttpRequest.newBuilder(link).build(),
                    HttpResponse.BodyHandlers.ofString());
            String cookie = traded.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
            assertEquals(401, post(at, "{\"act\":\"status\"}", "Cookie", cookie).statusCode());

            HttpResponse<String> status = post(at, "{\"act\":\"status\"}", "Authorization", "Bearer " + TOKEN);
            assertEquals(200, status.statusCode(), status.body());
            PilotState off = CoreJson.read(status.body(), PilotState.class);
            assertEquals(PilotState.Phase.OFF, off.phase());
            assertTrue(off.url() == null && off.qr().isEmpty());
        }
    }

    @Test
    void aStartWithNoProjectOpenIsRefusedAndAnUnknownWayIsToo() throws Exception {
        try (Projects projects = new Projects(OpenProject.Libraries.MAVEN);
             CoreServer server = serve(projects)) {
            CoreLauncher.Address at = new CoreLauncher.Address(server.port(), TOKEN);
            String bearer = "Bearer " + TOKEN;
            HttpResponse<String> none = post(at, "{\"act\":\"start\"}", "Authorization", bearer);
            assertEquals(409, none.statusCode());
            assertTrue(none.body().contains("No project is open"), none.body());
            HttpResponse<String> unknown = post(at, "{\"act\":\"start\",\"transport\":\"carrier-pigeon\"}",
                    "Authorization", bearer);
            assertEquals(409, unknown.statusCode());
            assertEquals(409, post(at, "{\"act\":\"dance\"}", "Authorization", bearer).statusCode());
            assertEquals(400, post(at, "not json", "Authorization", bearer).statusCode());
            assertEquals(200, post(at, "{\"act\":\"reset\"}", "Authorization", bearer).statusCode());
            assertEquals(200, post(at, "{\"act\":\"stop\"}", "Authorization", bearer).statusCode());
        }
    }

    @Test
    void aWatchingChannelHearsTheStateAndOnlyAWatchingOne() throws Exception {
        try (Projects projects = new Projects(OpenProject.Libraries.MAVEN);
             CoreServer server = serve(projects)) {
            CoreLauncher.Address at = new CoreLauncher.Address(server.port(), TOKEN);
            BlockingQueue<PilotState> watched = new LinkedBlockingQueue<>();
            List<Object> otherEvents = new java.util.concurrent.CopyOnWriteArrayList<>();
            try (CoreClient watcher = CoreClient.connect(at, "1.0", otherEvents::add, () -> { });
                 CoreClient bystander = CoreClient.connect(at, "1.0", otherEvents::add, () -> { })) {
                assertInstanceOf(EditResult.Accepted.class, watcher.watchPilot(watched::add));
                PilotState first = watched.poll(5, TimeUnit.SECONDS);
                assertNotNull(first, "the state now is told first");
                assertEquals(PilotState.Phase.OFF, first.phase());

                // No project is open, so a start is refused with why, and nothing is told to a client not watching.
                EditResult refused = bystander.startPilot(TransportKind.LAN);
                assertInstanceOf(EditResult.Refused.class, refused);
                assertTrue(((EditResult.Refused) refused).reason().contains("No project is open"));
                assertInstanceOf(EditResult.Accepted.class, bystander.resetPilot());
                assertTrue(otherEvents.stream().noneMatch(PilotState.class::isInstance));
            }
        }
    }

    @Test
    void theStatePairsAPhoneButItsTextHoldsNoLink() {
        PilotState state = new PilotState(PilotState.Phase.ON, TransportKind.LAN, TransportKind.LAN,
                "http://10.0.0.5:4000", "http://10.0.0.5:4000/?token=SECRET", List.of("101"), null, null, null,
                List.of(), List.of(new PilotServer.Connected("ab12cd34", "10.0.0.9", 1L, false)), List.of());
        assertTrue(!state.toString().contains("SECRET") && !state.toString().contains("101"));
        assertEquals(state, CoreJson.read(CoreJson.write(state), PilotState.class));
    }
}
