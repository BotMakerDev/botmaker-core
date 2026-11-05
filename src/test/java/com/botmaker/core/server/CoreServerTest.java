package com.botmaker.core.server;

import com.botmaker.core.assist.McpEndpoint;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            assertEquals(McpEndpoint.proof(TOKEN, nonce), proof);
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

    private static CoreServer.Hooks hooks(CoreServer.Idle idle, Runnable exit) {
        return new CoreServer.Hooks(idle, () -> false, channel -> { }, exit);
    }
}
