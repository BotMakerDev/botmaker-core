// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.sharing;

import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.botmaker.shared.tools.UserDirs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Reads the catalog (2026-10-23): one unauthenticated request to the raw CDN — no API rate limit, no account —
 * answered once per session, unless a reader {@link #refresh refreshes} it.
 *
 * <p>Since 2026-11-21 it reads {@code catalog.signed.json} and uses only a catalog whose signature
 * {@link CatalogSignature} verifies. The last verified catalog is kept on disk, as the signed file, and answers when
 * GitHub does not or answers a catalog that does not verify, so Studio offline still knows which plugins are listed
 * and how they rank. A verified catalog built before the kept one never replaces it: an old signed catalog served
 * again cannot take back a newer one. The unsigned {@code catalog.json} kept before is deleted once a verified one
 * is kept. A request that fails
 * is not remembered: the next reader asks again. Nothing here throws; no catalog at all is {@link CatalogIndex#EMPTY}.
 */
public final class CatalogClient {

    /** The unsigned copy kept before 2026-11-21, beside the signed one, deleted once. */
    static final String UNSIGNED_CACHE = "catalog.json";

    private static final CatalogClient SESSION = new CatalogClient(new GitHubClient(),
            GitHubConfig.catalogSignedRawUrls(), UserDirs.cache().resolve(GitHubConfig.CATALOG_SIGNED_PATH),
            CatalogSignature::verify);

    private final GitHubClient client;
    private final List<String> urls;
    private final Path cacheFile;
    /** A signed file's catalog when its signature verifies, else empty. */
    private final Function<String, Optional<String>> verifier;
    /** The answer every reader of this session shares; null until one is asked, and again after one failed. */
    private CompletableFuture<Reply> asked;

    /** Where an answer came from. New Project and the bot list say why by it (2026-10-24, 2026-11-21). */
    public enum Trust {
        /** GitHub answered just now, with a catalog that verifies. */
        VERIFIED,
        /** GitHub did not answer: the catalog kept from the last verified read, or none. */
        CACHED,
        /** GitHub answered a catalog that does not verify: the kept one serves, or none. */
        UNVERIFIED
    }

    /** One answer and where it came from. */
    public record Reply(CatalogIndex index, Trust trust) {

        /** Whether GitHub gave it just now, verified; otherwise it is the kept catalog or none. */
        public boolean fresh() {
            return trust == Trust.VERIFIED;
        }
    }

    CatalogClient(GitHubClient client, List<String> urls, Path cacheFile,
                  Function<String, Optional<String>> verifier) {
        this.client = client;
        this.urls = List.copyOf(urls);
        this.cacheFile = cacheFile;
        this.verifier = verifier;
    }

    /** The client every Studio reader shares, so a session sends one request. */
    public static CatalogClient session() {
        return SESSION;
    }

    /** The catalog: GitHub's copy, else the one kept from the last read, else {@link CatalogIndex#EMPTY}. */
    public CompletableFuture<CatalogIndex> fetch() {
        return ask().thenApply(Reply::index);
    }

    /**
     * The catalog asked again: the session's answer is dropped and later {@link #fetch} callers share the new one.
     * New Project calls it on every open, so a bot published since Studio started is offered (2026-10-24).
     */
    public synchronized CompletableFuture<Reply> refresh() {
        asked = null;
        return ask();
    }

    private synchronized CompletableFuture<Reply> ask() {
        if (asked != null) return asked;
        CompletableFuture<Reply> answer = new CompletableFuture<>();
        asked = answer;
        client.getFirstString(urls).exceptionally(failure -> null).thenAccept(body -> {
            // Whatever happens below, the answer completes: every reader of the session waits on it.
            try {
                if (body == null) {
                    forget(answer);
                    answer.complete(new Reply(kept(), Trust.CACHED));
                    return;
                }
                Optional<String> payload = verifier.apply(body);
                Optional<CatalogIndex> index = payload.flatMap(CatalogIndex::read);
                if (index.isEmpty()) {
                    System.err.println("The catalog GitHub answered could not be verified; the kept one serves.");
                    forget(answer);
                    answer.complete(new Reply(kept(), Trust.UNVERIFIED));
                    return;
                }
                Optional<String> kept = keptPayload();
                if (kept.isPresent() && builtBefore(payload.get(), kept.get())) {
                    answer.complete(new Reply(CatalogIndex.parse(kept.get()), Trust.VERIFIED));
                    return;
                }
                keepIfCurrent(answer, body);
                answer.complete(new Reply(index.get(), Trust.VERIFIED));
            } catch (RuntimeException e) {
                System.err.println("Could not read the catalog GitHub answered: " + e);
                forget(answer);
                answer.complete(new Reply(kept(), Trust.UNVERIFIED));
            }
        });
        return answer;
    }

    /**
     * Whether {@code fetched} is older than {@code kept}: the CDN behind, or an old catalog served again. One that
     * says no {@code builtAt} while the kept one does is older too. With nothing kept, any verified catalog serves.
     */
    private static boolean builtBefore(String fetched, String kept) {
        Optional<Instant> was = CatalogIndex.builtAt(kept);
        return was.isPresent() && CatalogIndex.builtAt(fetched).map(at -> at.isBefore(was.get())).orElse(true);
    }

    /** When the kept catalog was written: the date New Project names offline. Empty when none verified is kept. */
    public Optional<Instant> keptAt() {
        try {
            return keptPayload().isPresent()
                    ? Optional.of(Files.getLastModifiedTime(cacheFile).toInstant()) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private synchronized void forget(CompletableFuture<Reply> failed) {
        if (asked == failed) asked = null;
    }

    /**
     * The catalog kept on disk from the last verified read, {@link CatalogIndex#EMPTY} when there is none. Blocking,
     * and quick: what New Project shows before GitHub answers.
     */
    public CatalogIndex kept() {
        return keptPayload().map(CatalogIndex::parse).orElse(CatalogIndex.EMPTY);
    }

    /** The kept signed file's catalog, verified again: a file changed on disk serves nothing. */
    private Optional<String> keptPayload() {
        try {
            return Files.isRegularFile(cacheFile) ? verifier.apply(Files.readString(cacheFile)) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Deletes the unsigned catalog kept before 2026-11-21, once a verified one is kept in its place (nothing reads it
     * since). Best effort.
     */
    private void dropUnsigned() {
        Path unsigned = cacheFile.resolveSibling(UNSIGNED_CACHE);
        if (unsigned.equals(cacheFile)) return;
        try {
            Files.deleteIfExists(unsigned);
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not delete the unsigned " + UNSIGNED_CACHE + ": " + e.getMessage());
        }
    }

    /**
     * Keeps the signed {@code body} unless a {@link #refresh} replaced {@code answer} meanwhile: an older reply that
     * lands after a newer one never overwrites it. Under the lock, so two replies never share the {@code .part}
     * file. Best effort: a catalog that cannot be kept is read from GitHub again next session.
     */
    private synchronized void keepIfCurrent(CompletableFuture<Reply> answer, String body) {
        if (asked != answer) return;
        try {
            Files.createDirectories(cacheFile.toAbsolutePath().getParent());
            Path partial = cacheFile.resolveSibling(cacheFile.getFileName() + ".part");
            Files.writeString(partial, body);
            Files.move(partial, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dropUnsigned();
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not keep " + cacheFile.getFileName() + ": " + e.getMessage());
        }
    }
}
