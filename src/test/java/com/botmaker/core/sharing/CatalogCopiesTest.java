// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.sharing;

import com.botmaker.cli.bmk.BotFile;
import com.botmaker.cli.bmk.BotJson;
import com.botmaker.cli.catalog.Catalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogCopiesTest {

    private static final String OWNER = "BotMakerDev";
    private static final String REPO = "botmaker-google-sign-in";
    private static final String SLUG = OWNER + "/" + REPO;
    private static final String SHA_1 = "a".repeat(64);
    private static final String SHA_2 = "b".repeat(64);

    @TempDir
    Path tmp;

    private final List<String> downloaded = new ArrayList<>();

    /** A shelf whose catalog is {@code catalog}; a download fails with {@code offline} when it is set. */
    private final class FakeShelf implements CatalogCopies.Shelf {
        CatalogIndex catalog = CatalogIndex.EMPTY;
        /** Whether GitHub answered: an empty catalog then is one that lists nothing. */
        boolean fresh;
        IOException offline;
        BmkOpen.Result refused;

        @Override
        public CatalogClient.Reply catalog() {
            return new CatalogClient.Reply(catalog, fresh);
        }

        @Override
        public Path download(GalleryEntry entry) throws IOException {
            if (offline != null) throw offline;
            downloaded.add(entry.tag());
            return Files.writeString(tmp.resolve(entry.tag() + ".bmk"), "bmk");
        }

        @Override
        public BmkOpen.Result open(Path bmk, CatalogIndex catalog, GalleryEntry entry) {
            if (refused != null) return refused;
            try {
                return new BmkOpen.Opened(checked(entry.tag(), entry.bmkShaOf(entry.tag()).orElseThrow()), null);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Path root() {
            return tmp.resolve("bots");
        }
    }

    private final FakeShelf shelf = new FakeShelf();
    private final CatalogCopies copies = new CatalogCopies(shelf);

    @Test
    void theNewestApprovedReleaseIsGotAndKept() throws Exception {
        shelf.catalog = listed(PublisherRank.ADMIN, "v0.2.0", SHA_2);

        Path dir = copies.copyOf(SLUG);

        assertEquals(shelf.root().resolve(OWNER).resolve(REPO).resolve("v0.2.0"), dir);
        assertEquals(List.of("v0.2.0"), downloaded);
        assertFalse(Files.exists(tmp.resolve("v0.2.0.bmk")), "the downloaded file is not kept");
        assertEquals(dir, copies.copyOf(SLUG), "the checked copy serves again");
        assertEquals(List.of("v0.2.0"), downloaded, "without a second download");
    }

    @Test
    void aNewerListedTagReplacesTheKeptCopy() throws Exception {
        checked("v0.1.0", SHA_1);
        shelf.catalog = listed(PublisherRank.ADMIN, "v0.2.0", SHA_2);

        assertEquals("v0.2.0", copies.copyOf(SLUG).getFileName().toString());
    }

    /** A yank refuses nothing: a copy on a pulled release serves until a newer release is listed. */
    @Test
    void aCopyOnAPulledReleaseServesUntilANewerOne() throws Exception {
        Path kept = checked("v0.2.0", SHA_2);
        shelf.fresh = true;
        shelf.catalog = CatalogIndex.parse(pulled("""
                {"tag": "v0.2.0", "commit": "c2", "bmkSha256": "%s", "yanked": {"reason": "Breaks", "at": "2026-11-21T00:00:00Z"}},
                {"tag": "v0.1.0", "commit": "c1", "bmkSha256": "%s"}""".formatted(SHA_2, SHA_1)));
        assertEquals(kept, copies.copyOf(SLUG), "not swapped for the older v0.1.0");
        assertEquals(List.of(), downloaded);

        shelf.catalog = CatalogIndex.parse(pulled("""
                {"tag": "v0.3.0", "commit": "c3", "bmkSha256": "%s"},
                {"tag": "v0.2.0", "commit": "c2", "bmkSha256": "%s", "yanked": {"reason": "Breaks", "at": "2026-11-21T00:00:00Z"}}"""
                .formatted(SHA_1, SHA_2)));
        assertEquals("v0.3.0", copies.copyOf(SLUG).getFileName().toString(), "a newer release replaces it");
    }

    @Test
    void everyReleasePulledAndNoneKeptSaysWhy() {
        shelf.fresh = true;
        shelf.catalog = CatalogIndex.parse(pulled("""
                {"tag": "v0.1.0", "commit": "c1", "bmkSha256": "%s", "yanked": {"reason": "Bans accounts", "at": "2026-11-21T00:00:00Z"}}"""
                .formatted(SHA_1)));
        IOException refused = assertThrows(IOException.class, () -> copies.copyOf(SLUG));
        assertEquals(SLUG + ": Every release was pulled: Bans accounts.", refused.getMessage());
    }

    private static String pulled(String releases) {
        return "{\"bots\": [{\"repo\": \"" + SLUG + "\", \"rank\": \"admin\", \"releases\": [" + releases + "]}]}";
    }

    @Test
    void offlineTheLastCopyServes() throws Exception {
        Path old = checked("v0.1.0", SHA_1);
        shelf.catalog = listed(PublisherRank.ADMIN, "v0.2.0", SHA_2);
        shelf.offline = new IOException("GitHub could not be reached.");

        assertEquals(old, copies.copyOf(SLUG));
    }

    @Test
    void noCatalogAndACopyKeptTheCopyServes() throws Exception {
        Path old = checked("v0.1.0", SHA_1);

        assertEquals(old, copies.copyOf(SLUG));
    }

    @Test
    void aFileNotTheCatalogsIsRefusedNotReplacedByTheOldCopy() throws Exception {
        checked("v0.1.0", SHA_1);
        shelf.catalog = listed(PublisherRank.ADMIN, "v0.2.0", SHA_2);
        shelf.offline = new BmkDownload.NotPinned("replaced since");

        assertThrows(BmkDownload.NotPinned.class, () -> copies.copyOf(SLUG));
    }

    @Test
    void aBotTheCatalogReachedNoLongerListsIsNotRun() throws Exception {
        checked("v0.1.0", SHA_1);
        shelf.fresh = true;

        IOException e = assertThrows(IOException.class, () -> copies.copyOf(SLUG));

        assertTrue(e.getMessage().contains("no longer lists"), e.getMessage());
    }

    @Test
    void theListedTagKeptServesBesideANewerOne() throws Exception {
        Path listed = checked("v1.0.0", SHA_1);
        checked("v1.1.0", SHA_2);
        shelf.catalog = listed(PublisherRank.ADMIN, "v1.0.0", SHA_1);

        assertEquals(listed, copies.copyOf(SLUG));
        assertTrue(downloaded.isEmpty(), "rolled back to a tag kept: nothing downloaded");
    }

    @Test
    void theOwnerAndRepoAreMatchedWithoutCase() throws Exception {
        Path old = checked("v0.1.0", SHA_1);

        assertEquals(old, copies.copyOf(SLUG.toLowerCase(Locale.ROOT)));
    }

    @Test
    void anOpenRefusedSaysWhy() {
        shelf.catalog = listed(PublisherRank.ADMIN, "v0.2.0", SHA_2);
        shelf.refused = new BmkOpen.Refused("This file is not the one the catalog lists.");

        IOException e = assertThrows(IOException.class, () -> copies.copyOf(SLUG));

        assertEquals("This file is not the one the catalog lists.", e.getMessage());
    }

    @Test
    void noneListedAndNoneKeptIsRefused() {
        IOException e = assertThrows(IOException.class, () -> copies.copyOf(SLUG));

        assertTrue(e.getMessage().contains("lists no " + SLUG), e.getMessage());
    }

    @Test
    void aBotNotOfficialIsNotGot() {
        shelf.catalog = listed(PublisherRank.COMMUNITY, "v0.2.0", SHA_2);

        IOException e = assertThrows(IOException.class, () -> copies.copyOf(SLUG));

        assertTrue(e.getMessage().contains("not one of BotMaker's own"), e.getMessage());
        assertTrue(downloaded.isEmpty());
    }

    @Test
    void aListingWithNoReadyFileIsRefused() {
        shelf.catalog = listed(PublisherRank.ADMIN, "v0.2.0", null);

        IOException e = assertThrows(IOException.class, () -> copies.copyOf(SLUG));

        assertTrue(e.getMessage().contains("no ready-to-run release"), e.getMessage());
    }

    @Test
    void tagsCompareByTheirNumbers() {
        assertTrue(CatalogCopies.byVersion("v1.10.0", "v1.9.2") > 0);
        assertTrue(CatalogCopies.byVersion("v0.1.0", "v0.1.1") < 0);
        assertTrue(CatalogCopies.byVersion("v1.0.0-rc1", "v1.0.0") < 0, "a pre-release before its release");
    }

    private static CatalogIndex listed(PublisherRank rank, String tag, String sha) {
        GalleryEntry entry = new GalleryEntry("Google sign-in", OWNER, REPO, "", List.of(), "", tag, rank, 0,
                List.of(), PublisherRank.ADMIN, List.of(new Catalog.Bot.Release(tag, "7f57e0e", List.of(), sha)));
        return new CatalogIndex(List.of(), List.of(entry));
    }

    /** A checked unpacked copy of {@code tag}, as an open leaves it. */
    private Path checked(String tag, String sha) throws IOException {
        Path dir = shelf.root().resolve(OWNER).resolve(REPO).resolve(tag);
        Files.createDirectories(dir);
        BotJson bot = new BotJson(BotJson.FORMAT, "Google sign-in", OWNER, REPO, tag, "7f57e0e", 25,
                "com.botmaker.googlesignin.GoogleSignIn", List.of());
        Files.writeString(dir.resolve(BotFile.BOT_JSON), new ObjectMapper().writeValueAsString(bot));
        Files.writeString(dir.resolve(UnpackedBots.CHECKED), sha + "\n");
        return dir;
    }
}
