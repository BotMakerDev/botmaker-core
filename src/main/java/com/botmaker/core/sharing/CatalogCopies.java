// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.sharing;

import com.botmaker.core.services.MavenService;
import com.botmaker.core.services.ProgressReporter;
import com.botmaker.shared.tools.UserDirs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The run copy of a catalog bot a plugin runs itself ({@code SideRuns.catalogCopy}, 2026-11-15, the Google sign-in):
 * got as <i>Get</i> gets one ({@link BmkDownload}, then {@link BmkOpen#openGot}), into the same
 * {@link UnpackedBots#root()}, but without the trust sheet, so only for a bot the catalog ranks
 * {@link PublisherRank#ADMIN}. The listed tag's folder serves once checked; a newer listed tag is got and replaces it.
 * Only when the catalog or the download cannot be reached does the newest copy kept serve: a bot the catalog, reached,
 * no longer lists is not run, nor is a file other than the one it pinned.
 */
public final class CatalogCopies {

    /** How long the catalog is waited for before the kept one answers. */
    static final int CATALOG_SECONDS = 10;
    /** One copy got at a time: two sign-ins at once would download into one file. */
    private static final Object GETTING = new Object();

    /** What a copy is got through; {@link #live()} in the host, a fake in tests. */
    public interface Shelf {
        /** The catalog, {@code fresh} when GitHub answered just now; else the one kept from the last read. */
        CatalogClient.Reply catalog();

        /**
         * {@code entry}'s listed {@code .bmk}, downloaded and checked against its pinned sha256.
         *
         * @throws BmkDownload.NotPinned when the file is not the pinned one
         */
        Path download(GalleryEntry entry) throws IOException;

        /** {@code bmk} opened as {@code entry}'s got file under {@link #root()}. */
        BmkOpen.Result open(Path bmk, CatalogIndex catalog, GalleryEntry entry);

        /** Where unpacked bots are kept. */
        Path root();
    }

    private final Shelf shelf;

    public CatalogCopies(Shelf shelf) {
        this.shelf = shelf;
    }

    /** The host's: the catalog asked again, GitHub anonymously, Maven Central for the listed jars. */
    public static CatalogCopies live() {
        return new CatalogCopies(new Shelf() {
            @Override
            public CatalogClient.Reply catalog() {
                CatalogClient client = CatalogClient.session();
                CatalogClient.Reply kept = new CatalogClient.Reply(client.kept(), CatalogClient.Trust.CACHED);
                try {
                    // asked again: a release since this BotMaker started is got, as New Project offers it
                    return client.refresh().completeOnTimeout(kept, CATALOG_SECONDS, TimeUnit.SECONDS).join();
                } catch (CompletionException e) {
                    return kept;
                }
            }

            @Override
            public Path download(GalleryEntry entry) throws IOException {
                return new BmkDownload(null).fetch(entry, UserDirs.cache(), BmkDownload.Progress.NONE);
            }

            @Override
            public BmkOpen.Result open(Path bmk, CatalogIndex catalog, GalleryEntry entry) {
                return BmkOpen.openGot(bmk, catalog, entry, root(),
                        coordinates -> MavenService.fetch(coordinates, ProgressReporter.NONE));
            }

            @Override
            public Path root() {
                return UnpackedBots.root();
            }
        });
    }

    /**
     * The folder of {@code repo}'s ({@code owner/repo}) run copy. Blocking.
     *
     * @throws IOException saying why there is none: not listed, not official, no release yet, a file refused, or
     *                     offline with no copy kept
     */
    public Path copyOf(String repo) throws IOException {
        String slug = repo == null ? "" : repo.strip();
        String[] parts = slug.split("/");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IOException("\"" + slug + "\" names no bot: it is owner/repo.");
        }
        synchronized (GETTING) {
            CatalogClient.Reply reply = shelf.catalog();
            // A run copy got before its repository was renamed or transferred still finds its row (2026-11-21),
            // and stays in the folder of the slug it was got by.
            Optional<GalleryEntry> listed = reply.index().botAt(slug);
            Optional<Path> bot = folder(parts[0], parts[1]);
            Optional<Path> kept = bot.flatMap(this::newest);
            if (listed.isEmpty()) {
                if (reply.fresh()) {
                    throw new IOException("The catalog no longer lists " + slug + ", so it is not run.");
                }
                // offline: the copy got before still serves
                return kept.orElseThrow(() -> new IOException("The catalog lists no " + slug + ", and none was got "
                        + "before. Connect to the internet, or wait for it to be listed."));
            }
            GalleryEntry entry = listed.get();
            if (entry.trust() != PublisherRank.ADMIN) {
                throw new IOException(slug + " is not one of BotMaker's own bots in the catalog, so it is not run "
                        + "without you getting it from the bot list.");
            }
            // A yank refuses nothing (spec § 4): a copy got before on a release since pulled still serves, rather than
            // being swapped for an older one (a newer release replaces it); with none kept, every release pulled is
            // said as such.
            if (kept.isPresent()) {
                String keptTag = kept.get().getFileName().toString();
                if (entry.yanked(keptTag).isPresent()
                        && (entry.tag().isBlank() || byVersion(entry.tag(), keptTag) < 0)) return kept.get();
            }
            Optional<String> allPulled = entry.allPulledLine();
            if (allPulled.isPresent()) {
                if (kept.isPresent()) return kept.get();
                throw new IOException(slug + ": " + allPulled.get());
            }
            Optional<String> pinned = entry.bmkShaOf(entry.tag());
            if (!BmkDownload.ready(entry) || pinned.isEmpty()) {
                throw new IOException("The catalog lists no ready-to-run release of " + slug + " yet.");
            }
            Optional<Path> current = bot.map(dir -> dir.resolve(entry.tag()))
                    .filter(dir -> UnpackedBots.at(root(), dir).isPresent()
                            && pinned.get().equalsIgnoreCase(checkedSha(dir)));
            if (current.isPresent()) return current.get();
            Path bmk;
            try {
                bmk = shelf.download(entry);
            } catch (BmkDownload.NotPinned e) {
                // a file refused is said, never run instead of
                throw e;
            } catch (IOException e) {
                // GitHub out of reach: the copy kept serves
                if (kept.isPresent()) return kept.get();
                throw new IOException("Could not get " + slug + " " + entry.tag() + ": " + e.getMessage(), e);
            }
            try {
                return switch (shelf.open(bmk, reply.index(), entry)) {
                    case BmkOpen.Opened opened -> opened.dir();
                    case BmkOpen.Refused refused -> throw new IOException(refused.why());
                    case BmkOpen.NeedsTrust ignored -> throw new IOException("The catalog lists no ready-to-run "
                            + "file for " + slug + " " + entry.tag() + ".");
                };
            } finally {
                Files.deleteIfExists(bmk);
            }
        }
    }

    private Path root() {
        return shelf.root().toAbsolutePath().normalize();
    }

    /** {@code root/<owner>/<repo>}, its two names matched without case as the catalog's are; empty when absent. */
    private Optional<Path> folder(String owner, String repo) {
        return child(root(), owner).flatMap(dir -> child(dir, repo));
    }

    private static Optional<Path> child(Path dir, String name) {
        Path exact = dir.resolve(name);
        if (Files.isDirectory(exact)) return Optional.of(exact);
        if (!Files.isDirectory(dir)) return Optional.empty();
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isDirectory)
                    .filter(c -> c.getFileName().toString().equalsIgnoreCase(name)).findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** The newest checked tag in {@code bot}'s folder. */
    private Optional<Path> newest(Path bot) {
        try (Stream<Path> tags = Files.list(bot)) {
            return tags.filter(tag -> UnpackedBots.at(root(), tag).isPresent())
                    .max(Comparator.comparing(tag -> tag.getFileName().toString(), CatalogCopies::byVersion));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static String checkedSha(Path dir) {
        try {
            return Files.readString(dir.resolve(UnpackedBots.CHECKED)).strip();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Tags compared by their numbers ({@code v1.10.0} after {@code v1.9.2}), then as text; a pre-release
     * ({@code v1.0.0-rc1}) before its release.
     */
    static int byVersion(String a, String b) {
        String[] x = a.replaceFirst("^v", "").split("-", 2);
        String[] y = b.replaceFirst("^v", "").split("-", 2);
        String[] xs = x[0].split("\\.");
        String[] ys = y[0].split("\\.");
        for (int i = 0; i < Math.max(xs.length, ys.length); i++) {
            String p = i < xs.length ? xs[i] : "0";
            String q = i < ys.length ? ys[i] : "0";
            int c;
            try {
                c = Integer.compare(Integer.parseInt(p), Integer.parseInt(q));
            } catch (NumberFormatException e) {
                c = p.compareTo(q);
            }
            if (c != 0) return c;
        }
        if (x.length != y.length) return x.length == 1 ? 1 : -1;
        return x.length == 1 ? 0 : x[1].compareTo(y[1]);
    }
}
