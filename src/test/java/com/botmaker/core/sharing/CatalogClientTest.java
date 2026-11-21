// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.sharing;

import com.botmaker.shared.github.GitHubClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * New Project asks the catalog again on every open (2026-10-24): a bot published since Studio started shows. Only a
 * catalog that verifies is used or kept (2026-11-21).
 */
class CatalogClientTest {

    private static final KeyPair KEY = key();

    private static KeyPair key() {
        try {
            return CatalogSignatureTest.newKey();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String catalog(String botRepo) {
        return catalog(botRepo, "2026-11-21T10:00:00Z");
    }

    private static String catalog(String botRepo, String builtAt) {
        return """
                {"builtAt": "%s",
                 "plugins": [{"repo": "BotMakerDev/botmaker-sdk",
                              "releases": [{"tag": "v2.1.0"}]}],
                 "bots": [{"repo": "%s", "releases": [{"tag": "v1.0.0",
                                                       "plugins": [{"repo": "BotMakerDev/botmaker-sdk",
                                                                    "version": "v2.1.0"}]}]}]}
                """.formatted(builtAt, botRepo);
    }

    private static String signed(String catalog) {
        try {
            return CatalogSignatureTest.signed(catalog, KEY);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static CatalogClient client(Served served, Path cache) {
        return new CatalogClient(new GitHubClient(), served.urls(), cache,
                body -> CatalogSignature.verify(body, List.of(KEY.getPublic())));
    }

    private static List<String> bots(CatalogIndex index) {
        return index.bots().stream().map(GalleryEntry::slug).toList();
    }

    private record Served(HttpServer server, AtomicReference<String> answer, AtomicInteger requests) {
        List<String> urls() {
            return List.of("http://127.0.0.1:" + server.getAddress().getPort() + "/catalog.signed.json");
        }
    }

    private static Served serve(String body) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> answer = new AtomicReference<>(body);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new Served(server, answer, requests);
    }

    @Test
    void refreshAsksAgain(@TempDir Path dir) throws Exception {
        Served served = serve(signed(catalog("a/first")));
        try {
            CatalogClient client = client(served, dir.resolve("catalog.signed.json"));
            assertEquals(List.of("a/first"), bots(client.fetch().join()));

            served.answer().set(signed(catalog("b/second")));
            assertEquals(List.of("a/first"), bots(client.fetch().join()), "fetch alone shares the session's answer");
            CatalogClient.Reply refreshed = client.refresh().join();
            assertEquals(List.of("b/second"), bots(refreshed.index()));
            assertEquals(CatalogClient.Trust.VERIFIED, refreshed.trust(), "GitHub answered");
            assertEquals(List.of("b/second"), bots(client.fetch().join()), "later readers share the new answer");
            assertEquals(2, served.requests().get(), "one more request, for the refresh only");
        } finally {
            served.server().stop(0);
        }
    }

    /** Review Focus 4: a refresh that fails after a good read answers with the kept bots, never with none. */
    @Test
    void refreshFailingKeepsKept(@TempDir Path dir) throws Exception {
        Served served = serve(signed(catalog("a/first")));
        Path cache = dir.resolve("catalog.signed.json");
        CatalogClient client = client(served, cache);
        assertTrue(client.keptAt().isEmpty(), "nothing kept yet");
        assertEquals(List.of("a/first"), bots(client.fetch().join()));
        served.server().stop(0);
        // Changed on disk, so the answer below can only have come from the kept file.
        Files.writeString(cache, signed(catalog("k/kept")));
        Instant written = Instant.parse("2026-10-20T08:00:00Z");
        Files.setLastModifiedTime(cache, FileTime.from(written));

        CatalogClient.Reply refreshed = client.refresh().join();
        assertEquals(List.of("k/kept"), bots(refreshed.index()));
        assertEquals(CatalogClient.Trust.CACHED, refreshed.trust(), "the kept copy, which New Project calls offline");
        assertFalse(refreshed.fresh());
        assertEquals(Optional.of(written), client.keptAt(), "the date the offline line names");
    }

    /** Review Focus 5: a catalog that fails verification never overwrites the verified one kept. */
    @Test
    void failedVerificationKeepsCache(@TempDir Path dir) throws Exception {
        Served served = serve(signed(catalog("a/good")));
        Path cache = dir.resolve("catalog.signed.json");
        try {
            CatalogClient client = client(served, cache);
            assertEquals(List.of("a/good"), bots(client.fetch().join()));
            String kept = Files.readString(cache);

            String tampered = signed(catalog("a/good")).replaceFirst("\"payload\": \"[^\"]+\"", "\"payload\": \""
                    + Base64.getEncoder().encodeToString(catalog("b/evil").getBytes(StandardCharsets.UTF_8))
                    + "\"");
            served.answer().set(tampered);
            CatalogClient.Reply refreshed = client.refresh().join();
            assertEquals(List.of("a/good"), bots(refreshed.index()), "the kept catalog serves");
            assertEquals(CatalogClient.Trust.UNVERIFIED, refreshed.trust());
            assertEquals(kept, Files.readString(cache), "the cache still holds the verified catalog");

            served.answer().set(catalog("c/unsigned"));
            assertEquals(CatalogClient.Trust.UNVERIFIED, client.refresh().join().trust(), "an unsigned catalog too");
            assertEquals(kept, Files.readString(cache));
        } finally {
            served.server().stop(0);
        }
    }

    /** An older signed catalog served again, or the CDN behind a commit, never replaces a newer one kept. */
    @Test
    void anOlderCatalogNeverReplacesANewerOne(@TempDir Path dir) throws Exception {
        Served served = serve(signed(catalog("n/newer", "2026-11-21T10:00:00Z")));
        Path cache = dir.resolve("catalog.signed.json");
        try {
            CatalogClient client = client(served, cache);
            assertEquals(List.of("n/newer"), bots(client.fetch().join()));
            String kept = Files.readString(cache);

            served.answer().set(signed(catalog("o/older", "2026-11-20T10:00:00Z")));
            CatalogClient.Reply refreshed = client.refresh().join();
            assertEquals(List.of("n/newer"), bots(refreshed.index()));
            assertEquals(CatalogClient.Trust.VERIFIED, refreshed.trust());
            assertEquals(kept, Files.readString(cache));
        } finally {
            served.server().stop(0);
        }
    }

    /**
     * The unsigned catalog.json kept before signing is read by nothing, and deleted once a verified catalog is kept
     * in its place; a kept file that does not verify serves nothing and names no date.
     */
    @Test
    void unsignedCacheDropped(@TempDir Path dir) throws Exception {
        Path unsigned = dir.resolve(CatalogClient.UNSIGNED_CACHE);
        Files.writeString(unsigned, catalog("u/unsigned"));
        Path cache = dir.resolve("catalog.signed.json");
        Files.writeString(cache, catalog("u/unsigned"));
        Served served = serve(catalog("u/unsigned"));
        try {
            CatalogClient client = client(served, cache);
            assertTrue(client.kept().isEmpty(), "an unsigned file where the signed one goes serves nothing");
            assertTrue(client.keptAt().isEmpty(), "and names no date");
            assertEquals(CatalogClient.Trust.UNVERIFIED, client.refresh().join().trust());
            assertTrue(Files.exists(unsigned), "kept until a verified catalog replaces it");

            served.answer().set(signed(catalog("s/signed")));
            assertEquals(List.of("s/signed"), bots(client.refresh().join().index()));
            assertFalse(Files.exists(unsigned), "deleted");
        } finally {
            served.server().stop(0);
        }
    }

    /** A signed catalog naming no build time never replaces a kept one that names one. */
    @Test
    void aCatalogWithNoBuildTimeIsOlder(@TempDir Path dir) throws Exception {
        Served served = serve(signed(catalog("n/newer")));
        Path cache = dir.resolve("catalog.signed.json");
        try {
            CatalogClient client = client(served, cache);
            assertEquals(List.of("n/newer"), bots(client.fetch().join()));
            served.answer().set(signed(catalog("o/older").replaceFirst("\"builtAt\": \"[^\"]+\",", "")));
            assertEquals(List.of("n/newer"), bots(client.refresh().join().index()));
        } finally {
            served.server().stop(0);
        }
    }
}
