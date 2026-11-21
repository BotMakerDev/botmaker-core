// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.sharing;

import com.botmaker.cli.catalog.ApprovedRelease;
import com.botmaker.cli.catalog.Catalog;
import com.botmaker.cli.catalog.Platform;
import com.botmaker.shared.github.SemVer;
import com.botmaker.shared.platform.Os;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One bot Browse Bots lists: a row of {@code catalog.json} ({@link CatalogIndex}, 2026-10-23), a pointer to a
 * bot's own GitHub repo at the release the catalog's check passed.
 *
 * @param name        the repository's name — also the install dir name
 * @param owner       GitHub repo owner (login)
 * @param repo        GitHub repo name
 * @param description short human description
 * @param tags        optional free-form tags for filtering
 * @param basedOn     {@code owner/repo} of the bot this one is a fork of and still follows (the catalog's
 *                    {@code forkOf}), blank for a bot of its own or a fork that stopped following
 * @param tag         the release Install, New Project and <i>Get vX.Y</i> go to — never a newer one, which no
 *                    check has passed; blank for a row that is not the catalog's
 * @param rank        who published it
 * @param stars       GitHub's star count when the catalog was built
 * @param plugins     the catalog plugins the bot's pom at {@code tag} declares, with the versions it pins; an id
 *                    that names no plugin row is kept and matches nothing
 * @param pluginsRank the lowest rank among {@code plugins} as the catalog ranks them — community for an id that
 *                    names no row — and {@link PublisherRank#ADMIN} for a bot that uses none: a bot runs its
 *                    plugins' code too, so it is trusted no further than they are ({@link #trust})
 * @param releases    every release the catalog approved, newest first, each pinned to its commit (2026-10-24):
 *                    {@code tag} and {@code plugins} are the newest one's; empty for a row that is not the catalog's
 * @param formerly    the {@code owner/repo} slugs its repository had before GitHub renamed or transferred it, oldest
 *                    first (2026-11-21, spec § 3): a run copy or an {@code upstream} at one still finds this row
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GalleryEntry(String name, String owner, String repo, String description, List<String> tags,
                           String basedOn, String tag, PublisherRank rank, int stars,
                           List<PublishedBotPlugins.Pin> plugins, PublisherRank pluginsRank,
                           List<Catalog.Bot.Release> releases, List<String> formerly) {

    // TEMPLATE_TAG went on 2026-10-23, with the template concept: every published bot is a starting point, and
    // New Project clones it linked to its author as Browse Bots' Install does. An entry still tagged "template"
    // reads as an ordinary bot with that tag.

    @JsonCreator
    public GalleryEntry {
        name = name == null ? "" : name.trim();
        owner = owner == null ? "" : owner.trim();
        repo = repo == null ? "" : repo.trim();
        description = description == null ? "" : description;
        tags = tags == null ? List.of() : List.copyOf(tags);
        basedOn = basedOn == null ? "" : basedOn.trim();
        tag = tag == null ? "" : tag.trim();
        rank = rank == null ? PublisherRank.COMMUNITY : rank;
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
        pluginsRank = pluginsRank == null ? PublisherRank.COMMUNITY : pluginsRank;
        releases = releases == null ? List.of() : List.copyOf(releases);
        formerly = formerly == null ? List.of() : List.copyOf(formerly);
    }

    /** A row never moved. */
    public GalleryEntry(String name, String owner, String repo, String description, List<String> tags,
                        String basedOn, String tag, PublisherRank rank, int stars,
                        List<PublishedBotPlugins.Pin> plugins, PublisherRank pluginsRank,
                        List<Catalog.Bot.Release> releases) {
        this(name, owner, repo, description, tags, basedOn, tag, rank, stars, plugins, pluginsRank, releases,
                List.of());
    }

    /** Whether {@code slug} names this row: its {@link #slug()} or one it had {@link #formerly()}, without case. */
    public boolean answersTo(String slug) {
        if (slug == null) return false;
        String wanted = slug.strip();
        return slug().equalsIgnoreCase(wanted) || formerly.stream().anyMatch(wanted::equalsIgnoreCase);
    }

    /** A row with no pinned releases: a test's. */
    public GalleryEntry(String name, String owner, String repo, String description, List<String> tags,
                        String basedOn, String tag, PublisherRank rank, int stars,
                        List<PublishedBotPlugins.Pin> plugins, PublisherRank pluginsRank) {
        this(name, owner, repo, description, tags, basedOn, tag, rank, stars, plugins, pluginsRank, List.of());
    }

    /** A bot of its own the catalog did not list — one being published, a test's: community, no tag. */
    public GalleryEntry(String name, String owner, String repo, String description, List<String> tags) {
        this(name, owner, repo, description, tags, "", "", PublisherRank.COMMUNITY, 0, List.of(),
                PublisherRank.ADMIN);
    }

    /** This listing, read as a fork of {@code slug}, or as nothing's when it is blank. */
    public GalleryEntry withBasedOn(String slug) {
        return new GalleryEntry(name, owner, repo, description, tags, slug, tag, rank, stars, plugins, pluginsRank,
                releases, formerly);
    }

    /** The tags the catalog approved and nobody yanked, newest first: what <i>Get vX.Y</i> picks from. */
    public List<String> approvedTags() {
        return releases.stream().filter(r -> r.yanked() == null).map(Catalog.Bot.Release::tag).toList();
    }

    /** Why the catalog's release {@code tag} was pulled; empty when it was not, or is not listed (spec § 4). */
    public Optional<ApprovedRelease.Yanked> yanked(String tag) {
        return releases.stream().filter(r -> r.tag().equals(tag)).map(Catalog.Bot.Release::yanked)
                .filter(Objects::nonNull).findFirst();
    }

    /** The banner for a copy on {@code installed}, when that release was pulled; empty otherwise. */
    public Optional<String> pulledLine(String installed) {
        return yanked(installed).map(why -> CatalogIndex.pulledLine(installed, why, tag));
    }

    /** Whether the catalog lists releases but every one was pulled, so none is offered. */
    public boolean allPulled() {
        return !releases.isEmpty() && tag.isBlank();
    }

    /** "Every release was pulled: <newest's reason>." when {@link #allPulled}; empty otherwise. */
    public Optional<String> allPulledLine() {
        if (!allPulled()) return Optional.empty();
        return Optional.of("Every release was pulled"
                + yanked(releases.getFirst().tag()).map(why -> ": " + CatalogIndex.sentence(why)).orElse("."));
    }

    /**
     * The commit the catalog approved {@code tag} at, which Install, New Project and <i>Get vX.Y</i> take; blank
     * when it approved no such release (a row that is not the catalog's).
     */
    public String commitOf(String tag) {
        return releases.stream().filter(r -> r.tag().equals(tag)).map(Catalog.Bot.Release::commit)
                .filter(c -> c != null).findFirst().orElse("");
    }

    /**
     * The sha256 the catalog pins for {@code tag}'s {@code .bmk}, which an opened file must match; empty when it
     * approved no such release, or one with no {@code .bmk} (2026-10-25).
     */
    public Optional<String> bmkShaOf(String tag) {
        return releases.stream().filter(r -> r.tag().equals(tag)).map(Catalog.Bot.Release::bmkSha256)
                .filter(s -> s != null && !s.isBlank()).findFirst();
    }

    /**
     * How often its releases' {@code .bmk}s were downloaded, summed; empty when none has a count (no {@code .bmk}, or
     * a catalog older than schema 6, 2026-11-21).
     */
    public OptionalInt downloads() {
        return releases.stream().map(Catalog.Bot.Release::downloads).filter(Objects::nonNull)
                .mapToInt(Integer::intValue).reduce(Integer::sum);
    }

    /** When its newest release was published; empty when the catalog does not say or says it unreadably. */
    public Optional<Instant> updatedAt() {
        return releases.isEmpty() ? Optional.empty() : CatalogIndex.time(releases.getFirst().publishedAt());
    }

    /** "42 downloads · updated 3 days ago", either half left out when the catalog does not say; blank for neither. */
    public String factsLine(Instant now) {
        List<String> parts = new ArrayList<>();
        downloads().ifPresent(n -> parts.add(n == 1 ? "1 download" : n + " downloads"));
        updatedAt().ifPresent(when -> parts.add(CatalogIndex.updatedAgo(when, now)));
        return String.join(" · ", parts);
    }

    /** Whether its newest release runs on {@code os}: it names that system, or names none (2026-11-21). */
    public boolean runsHere(Os os) {
        return offered().map(r -> r.runsOn(Platform.fromId(os.id()))).orElse(true);
    }

    /** "Windows", "Linux and macOS": the systems its offered release names, every known one when it names none. */
    public String systemsLine() {
        List<Platform> named = offered().map(Catalog.Bot.Release::platforms).filter(p -> !p.isEmpty())
                .orElse(Platform.known());
        List<String> names = named.stream().map(Platform::displayName).toList();
        return names.size() == 1 ? names.getFirst()
                : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }

    /** The release {@link #tag} names, the one offered; empty when none is. */
    private Optional<Catalog.Bot.Release> offered() {
        return releases.stream().filter(r -> r.tag().equals(tag)).findFirst();
    }

    /** How far this bot is trusted: its publisher's rank, or its plugins' when one of theirs is lower. */
    public PublisherRank trust() {
        return PublisherRank.lower(rank, pluginsRank);
    }

    /** "based on alice/miner", or blank for a bot of its own. */
    public String basedOnLine() {
        return basedOn.isBlank() ? "" : "based on " + basedOn;
    }

    /**
     * The order every list of bots is shown in (2026-10-23): official first, then trusted, then community
     * ({@link #trust}), the most starred first within a rank, then by name.
     */
    public static final Comparator<GalleryEntry> RANKED = Comparator.comparing(GalleryEntry::trust)
            .thenComparing(Comparator.comparingInt(GalleryEntry::stars).reversed())
            .thenComparing(GalleryEntry::name, String.CASE_INSENSITIVE_ORDER);

    /**
     * The bots among {@code entries} New Project offers to start from, {@link #RANKED}.
     *
     * <p>Sorted rather than left in the catalog's order so the list reads the same on every launch.
     */
    public static List<GalleryEntry> startingPoints(List<GalleryEntry> entries) {
        return entries.stream().sorted(RANKED).toList();
    }

    /**
     * The release an installed copy should move to: the listed one when it is not the one installed. Pure, so
     * the rule is tested without a network.
     *
     * @param installed the tag the project was installed at
     * @param latest    the catalog's tag for the bot, blank when it is not listed
     */
    public static Optional<String> updateTarget(String installed, String latest) {
        String target = latest == null ? "" : latest;
        if (target.isBlank() || target.equals(installed)) return Optional.empty();
        // Never backwards: a newest release older than the one installed is a release deleted since.
        if (SemVer.isValid(installed) && SemVer.isValid(target) && SemVer.compare(target, installed) < 0) {
            return Optional.empty();
        }
        return Optional.of(target);
    }

    /**
     * What every door that starts from this bot says first — Browse Bots' Install, New Project, the assistant's
     * {@code create_project} — by {@link #trust} (2026-10-23): nothing for an official bot, who published it for
     * a trusted one, and for a community one that nobody reviewed its code. Only a community bot is confirmed
     * before it is installed ({@link #asksFirst}).
     */
    public String trustWarning() {
        return switch (trust()) {
            case ADMIN -> "";
            case TRUSTED -> "By " + owner + ", a vetted developer.";
            case COMMUNITY -> rank == PublisherRank.COMMUNITY
                    ? "“" + name + "” by " + owner + " is a bot that can control your mouse, keyboard and screen. "
                    + "It was listed automatically, and nobody reviewed its code. Only start from bots by authors "
                    + "you trust."
                    : "“" + name + "” by " + owner + " uses a community plugin, which can control your mouse, "
                    + "keyboard and screen. It was listed automatically, and nobody reviewed its code. Only start "
                    + "from bots whose plugins are by authors you trust.";
        };
    }

    /** Whether starting from this bot is confirmed first: a community bot, or one using a community plugin. */
    public boolean asksFirst() {
        return trust() == PublisherRank.COMMUNITY;
    }

    public String htmlUrl() {
        return "https://github.com/" + owner + "/" + repo;
    }

    /** {@code owner/repo}, the stable identity of a bot. */
    public String slug() {
        return owner + "/" + repo;
    }

    /** True if the entry matches a free-text query against name / description / owner / tags. */
    public boolean matches(String query) {
        if (query == null || query.isBlank()) return true;
        String q = query.toLowerCase();
        if (name.toLowerCase().contains(q)) return true;
        if (description.toLowerCase().contains(q)) return true;
        if (owner.toLowerCase().contains(q)) return true;
        return tags.stream().anyMatch(t -> t.toLowerCase().contains(q));
    }
}
