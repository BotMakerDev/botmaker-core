// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.sharing;

import com.botmaker.cli.catalog.Advisory;
import com.botmaker.cli.catalog.ApprovedRelease;
import com.botmaker.cli.catalog.Catalog;
import com.botmaker.cli.catalog.CatalogJson;
import com.botmaker.cli.catalog.DerivedFacts;
import com.botmaker.cli.catalog.Rank;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code catalog.json} as Studio reads it (2026-10-23): every listed plugin and bot, ranked by publisher, with
 * the facts the catalog's CI derived. It is the only file Studio reads about published work — no topic search,
 * no pom read per row.
 *
 * <p>Each row is read as the cli's own {@link Catalog} record (2026-10-24, schema 4), the one the catalog's index
 * writes: every approved release, newest first, each pinned. The newest release is the row's {@code tag}.
 *
 * <p>{@link #parse} never throws. This Studio is the reader that lags: a row it cannot read costs that row, a
 * field it does not know is skipped, and a body that is no catalog at all is {@link #EMPTY}. A row with no
 * release (the single {@code tag} of schema 3 is not read) is skipped. A bot's plugin repo that names no plugin row
 * (a plugin unlisted since the catalog was built) is kept as a pin; it simply matches no row.
 */
public record CatalogIndex(List<PluginRegistry.Plugin> plugins, List<GalleryEntry> bots, List<Advisory> advisories) {

    public static final CatalogIndex EMPTY = new CatalogIndex(List.of(), List.of());

    private static final ObjectMapper MAPPER = CatalogJson.lenient();

    public CatalogIndex {
        plugins = List.copyOf(plugins);
        bots = List.copyOf(bots);
        advisories = List.copyOf(advisories);
    }

    /** A catalog with no advisory. */
    public CatalogIndex(List<PluginRegistry.Plugin> plugins, List<GalleryEntry> bots) {
        this(plugins, bots, List.of());
    }

    public boolean isEmpty() {
        return plugins.isEmpty() && bots.isEmpty();
    }

    /** The catalog {@code body} holds, {@link #EMPTY} when it holds none. */
    public static CatalogIndex parse(String body) {
        return read(body).orElse(EMPTY);
    }

    /** When the catalog {@code body} holds was built ({@code builtAt}), empty when it says none or no instant. */
    static Optional<Instant> builtAt(String body) {
        try {
            JsonNode built = MAPPER.readTree(body).path("builtAt");
            return built.isTextual() ? Optional.of(Instant.parse(built.asText())) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * The catalog {@code body} holds, empty when it is no catalog at all — which a catalog listing nothing is not:
     * {@code {"plugins": [], "bots": []}} is an answer, and replaces the one kept from the last read.
     */
    static Optional<CatalogIndex> read(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            System.err.println("Failed to parse catalog.json: " + e.getMessage());
            return Optional.empty();
        }
        if (root == null || !root.isObject()) return Optional.empty();
        List<Advisory> advisories = new ArrayList<>();
        for (JsonNode row : root.path("advisories")) {
            row(row, Advisory.class).filter(a -> a.active() && a.id() != null && a.repo() != null
                    && a.summary() != null && !a.summary().isBlank()).ifPresent(advisories::add);
        }
        List<PluginRegistry.Plugin> plugins = new ArrayList<>();
        for (JsonNode row : root.path("plugins")) {
            row(row, Catalog.Plugin.class).map(p -> advised(p, advisories)).flatMap(CatalogIndex::plugin)
                    .ifPresent(plugins::add);
        }
        Map<String, PublisherRank> rankByRepo = new HashMap<>();
        for (PluginRegistry.Plugin plugin : plugins) rankByRepo.putIfAbsent(plugin.repo(), plugin.rank());
        List<GalleryEntry> bots = new ArrayList<>();
        for (JsonNode row : root.path("bots")) {
            row(row, Catalog.Bot.class).map(b -> advised(b, advisories)).flatMap(bot -> bot(bot, rankByRepo))
                    .ifPresent(bots::add);
        }
        return Optional.of(new CatalogIndex(plugins, bots, advisories));
    }

    /**
     * A release an advisory names is pulled as a yanked one is (spec § 5: pickers leave it out and mark it): its
     * {@code yanked} says the advisory, unless the release was already yanked for a reason of its own.
     */
    private static Optional<ApprovedRelease.Yanked> advisory(String repo, List<String> formerly, String tag,
                                                             List<Advisory> advisories) {
        return advisories.stream().filter(a -> a.affects(repo, tag) || formerly.stream().anyMatch(s -> a.affects(s, tag)))
                .max(Comparator.comparing(Advisory::severity))
                .map(a -> new ApprovedRelease.Yanked("security advisory " + a.id() + " ("
                        + a.severity().displayName().toLowerCase(Locale.ROOT) + "): " + a.summary().strip(),
                        a.published()));
    }

    private static Catalog.Plugin advised(Catalog.Plugin row, List<Advisory> advisories) {
        if (advisories.isEmpty() || row.repo() == null) return row;
        List<Catalog.Plugin.Release> releases = row.releases().stream().map(r -> r == null || r.yanked() != null ? r
                : advisory(row.repo(), row.formerly(), r.tag(), advisories).map(why -> new Catalog.Plugin.Release(
                r.tag(), r.commit(), r.jarSha256(), r.pomSha256(), r.minContractVersion(), r.editorDependencies(),
                r.plugins(), r.publishedAt(), why)).orElse(r)).toList();
        return new Catalog.Plugin(row.repo(), row.rank(), row.coordinate(), row.name(), row.description(), row.tags(),
                row.stars(), releases, row.updatedAt(), row.formerly());
    }

    private static Catalog.Bot advised(Catalog.Bot row, List<Advisory> advisories) {
        if (advisories.isEmpty() || row.repo() == null) return row;
        List<Catalog.Bot.Release> releases = row.releases().stream().map(r -> r == null || r.yanked() != null ? r
                : advisory(row.repo(), row.formerly(), r.tag(), advisories).map(why -> new Catalog.Bot.Release(
                r.tag(), r.commit(), r.plugins(), r.bmkSha256(), r.publishedAt(), r.downloads(), r.platforms(), why))
                .orElse(r)).toList();
        return new Catalog.Bot(row.repo(), row.rank(), row.description(), row.tags(), row.stars(), row.forkOf(),
                releases, row.updatedAt(), row.downloads(), row.formerly());
    }

    /** One row as the cli's record, empty when it does not read as one. */
    private static <T> Optional<T> row(JsonNode row, Class<T> type) {
        try {
            return Optional.ofNullable(MAPPER.treeToValue(row, type));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<PluginRegistry.Plugin> plugin(Catalog.Plugin row) {
        String repo = trim(row.repo());
        List<Catalog.Plugin.Release> releases = row.releases().stream()
                .filter(r -> r != null && !trim(r.tag()).isEmpty()).toList();
        if (!isSlug(repo) || releases.isEmpty()) return Optional.empty();
        // What is offered is the newest release not yanked; a row whose every release was pulled offers none
        // (2026-11-21, spec § 4) and stays listed, so a project on one still finds its row and its reason.
        Optional<Catalog.Plugin.Release> offered = releases.stream().filter(r -> r.yanked() == null).findFirst();
        Catalog.Plugin.Release newest = offered.orElse(releases.getFirst());
        String name = trim(row.name());
        return Optional.of(new PluginRegistry.Plugin(name.isEmpty() ? repoName(repo) : name,
                trim(row.coordinate()), repo, row.description(), row.tags(), trim(newest.minContractVersion()),
                newest.editorDependencies(), "", offered.map(Catalog.Plugin.Release::tag).orElse(""),
                rank(row.rank()), row.stars(), releases, slugs(row.formerly())));
    }

    private static Optional<GalleryEntry> bot(Catalog.Bot row, Map<String, PublisherRank> rankByRepo) {
        String repo = trim(row.repo());
        List<Catalog.Bot.Release> releases = row.releases().stream()
                .filter(r -> r != null && !trim(r.tag()).isEmpty()).toList();
        if (!isSlug(repo) || releases.isEmpty()) return Optional.empty();
        // As for a plugin: the newest release not yanked is the one offered, and none when every one was pulled.
        Optional<Catalog.Bot.Release> offered = releases.stream().filter(r -> r.yanked() == null).findFirst();
        Catalog.Bot.Release newest = offered.orElse(releases.getFirst());
        List<PublishedBotPlugins.Pin> pins = new ArrayList<>();
        // A plugin no row lists vouches for nothing, as an unknown rank does.
        PublisherRank pluginsRank = PublisherRank.ADMIN;
        for (DerivedFacts.Pin pin : newest.plugins()) {
            String pinned = pin == null ? "" : trim(pin.repo());
            if (pinned.isEmpty()) continue;
            pins.add(new PublishedBotPlugins.Pin(pinned, trim(pin.version())));
            pluginsRank = PublisherRank.lower(pluginsRank, rankByRepo.getOrDefault(pinned, PublisherRank.COMMUNITY));
        }
        String name = repoName(repo);
        return Optional.of(new GalleryEntry(name, repo.substring(0, repo.indexOf('/')), name, row.description(),
                row.tags(), trim(row.forkOf()), offered.map(Catalog.Bot.Release::tag).orElse(""), rank(row.rank()),
                row.stars(), pins, pluginsRank,
                releases, slugs(row.formerly())));
    }

    /** The {@code owner/repo} slugs of {@code formerly} that read as one, the rest dropped. */
    private static List<String> slugs(List<String> formerly) {
        return formerly.stream().map(CatalogIndex::trim).filter(CatalogIndex::isSlug).toList();
    }

    /**
     * The bot row {@code slug} names, by its repository now or one it had before a rename or a transfer
     * ({@link GalleryEntry#answersTo}); its current slug wins over another row's former one.
     */
    public Optional<GalleryEntry> botAt(String slug) {
        return bots.stream().filter(e -> e.slug().equalsIgnoreCase(trim(slug))).findFirst()
                .or(() -> bots.stream().filter(e -> e.answersTo(slug)).findFirst());
    }

    private static PublisherRank rank(Rank rank) {
        return PublisherRank.fromId(rank == null ? "" : rank.id());
    }

    /** A catalog time ({@code publishedAt}, ISO-8601), or empty for none or one that does not read. */
    static Optional<Instant> time(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        try {
            return Optional.of(Instant.parse(text));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * The banner over a project or run copy on a yanked release (spec § 4): "v1.2.0 was pulled: Deletes saves.
     * Newest: v1.1.0", the last part "No release is offered now." when every release was pulled.
     */
    public static String pulledLine(String tag, ApprovedRelease.Yanked why, String offered) {
        return tag + " was pulled: " + sentence(why)
                + (offered == null || offered.isBlank() ? " No release is offered now." : " Newest: " + offered);
    }

    /** A yank's reason as a sentence: stripped, ending in a stop. */
    static String sentence(ApprovedRelease.Yanked why) {
        String reason = why.reason().strip();
        return reason.isEmpty() || ".!?".indexOf(reason.charAt(reason.length() - 1)) >= 0 ? reason : reason + ".";
    }

    /** "updated today", "updated 3 days ago", "updated 2 months ago": how a list says when a row last changed. */
    public static String updatedAgo(Instant when, Instant now) {
        long days = Math.max(0, Duration.between(when, now).toDays());
        if (days == 0) return "updated today";
        if (days == 1) return "updated yesterday";
        if (days < 60) return "updated " + days + " days ago";
        if (days < 730) return "updated " + days / 30 + " months ago";
        return "updated " + days / 365 + " years ago";
    }

    /** {@code owner/repo}: one slash, neither side blank. */
    private static boolean isSlug(String repo) {
        int slash = repo.indexOf('/');
        return slash > 0 && slash == repo.lastIndexOf('/') && slash < repo.length() - 1;
    }

    private static String repoName(String repo) {
        return repo.substring(repo.indexOf('/') + 1);
    }

    private static String trim(String text) {
        return text == null ? "" : text.trim();
    }
}
