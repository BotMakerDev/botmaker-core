// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.sharing;

import com.botmaker.cli.bmk.BotJson;
import com.botmaker.cli.catalog.Advisory;
import com.botmaker.cli.catalog.CatalogJson;
import com.botmaker.shared.tools.UserDirs;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The catalog's security advisories as the core enforces them (spec 2026-11-21 § 5): a plugin release or a run copy's
 * release an advisory names is refused, loaded or run only once this computer's user said <i>Run anyway</i> for
 * that advisory, that entry and that release. The yes is kept in {@value #FILE} under {@link UserDirs#config()};
 * it never reaches another release, so an advisory extended to the next one asks again.
 *
 * <p>It reads the catalog it is handed, the last verified one when GitHub does not answer, so it holds offline.
 */
public final class Advisories {

    /** This computer's overrides, beside the rest of BotMaker's configuration. */
    public static final String FILE = "advisory-overrides.json";

    /** One <i>Run anyway</i>: advisory {@code id} let through for {@code tag} of the entry at {@code repo}. */
    record Override(String id, String repo, String tag) {
        Override {
            repo = repo.toLowerCase(Locale.ROOT);
        }
    }

    private final Path file;

    public Advisories(Path file) {
        this.file = file;
    }

    /** This computer's. */
    public static Advisories here() {
        return new Advisories(UserDirs.config().resolve(FILE));
    }

    /**
     * The worst advisory of {@code catalog} naming release {@code tag} of the entry {@code slug} names: by the entry's
     * repository now or one it had before a move, whichever of them the advisory was filed under.
     */
    public static Optional<Advisory> affecting(CatalogIndex catalog, String slug, String tag) {
        if (slug == null || tag == null || slug.isBlank() || tag.isBlank()) return Optional.empty();
        List<String> slugs = slugsOf(catalog, slug.strip());
        return catalog.advisories().stream()
                .filter(a -> slugs.stream().anyMatch(s -> a.affects(s, tag.strip())))
                .max(Comparator.comparing(Advisory::severity));
    }

    /** The advisory refusing {@code tag} of {@code slug} on this computer: {@link #affecting}, not overridden. */
    public Optional<Advisory> refusing(CatalogIndex catalog, String slug, String tag) {
        return affecting(catalog, slug, tag).filter(a -> !overridden(a, slug, tag));
    }

    /**
     * A release refused by an advisory.
     *
     * @param text the refusal in words ({@link #refusal})
     */
    public record Refused(Advisory advisory, String slug, String tag, String text) {}

    /**
     * Why the run copy in {@code dir} may not run on this computer: its release is named by an advisory not overridden.
     * Empty for any other project, and for a run copy no advisory names.
     */
    public Optional<Refused> refusingRun(Path dir, CatalogIndex catalog) {
        return refusingRun(UnpackedBots.at(dir), catalog);
    }

    /** As {@link #refusingRun(Path, CatalogIndex)}, for the run copy {@code bot} says, if any. */
    public Optional<Refused> refusingRun(Optional<BotJson> bot, CatalogIndex catalog) {
        return bot.flatMap(b -> {
            String slug = b.owner() + "/" + b.repo();
            return refusing(catalog, slug, b.tag())
                    .map(a -> new Refused(a, slug, b.tag(), refusal(catalog, a, slug, b.tag())));
        });
    }

    /** Keeps the <i>Run anyway</i> that lets {@code refused}'s release through, and no other. */
    public void override(Refused refused) {
        override(refused.advisory(), refused.slug(), refused.tag());
    }

    /** Whether this computer's user said <i>Run anyway</i> to {@code advisory} for exactly this release. */
    public boolean overridden(Advisory advisory, String slug, String tag) {
        return read().contains(new Override(advisory.id(), slug.strip(), tag.strip()));
    }

    /** Keeps a <i>Run anyway</i> for {@code advisory}, entry {@code slug}, release {@code tag}, and no other. */
    public void override(Advisory advisory, String slug, String tag) {
        Override yes = new Override(advisory.id(), slug.strip(), tag.strip());
        List<Override> all = new ArrayList<>(read());
        if (all.contains(yes)) return;
        all.add(yes);
        try {
            Files.createDirectories(file.getParent());
            Path next = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(next, CatalogJson.strict().writeValueAsString(all) + "\n");
            Files.move(next, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("could not keep the override in " + file, e);
        }
    }

    private List<Override> read() {
        if (!Files.isRegularFile(file)) return List.of();
        try {
            return CatalogJson.lenient().readValue(Files.readString(file), new TypeReference<List<Override>>() {});
        } catch (IOException e) {
            return List.of();   // one that does not read lets nothing through
        }
    }

    /**
     * The refusal, in words: what is wrong, how bad, where it is described, and the newest release of the entry no
     * advisory names, when there is one.
     */
    public static String refusal(CatalogIndex catalog, Advisory advisory, String slug, String tag) {
        String summary = advisory.summary().strip();
        Optional<String> newest = newestUnaffected(catalog, slug);
        return slug + " " + tag + " has a " + advisory.severity().displayName().toLowerCase(Locale.ROOT)
                + " security advisory, " + advisory.id() + ": " + summary
                + (".!?".indexOf(summary.charAt(summary.length() - 1)) >= 0 ? "" : ".")
                + (advisory.url() == null ? "" : " " + advisory.url())
                + newest.map(t -> " The newest release it does not name is " + t + ".")
                .orElse(" No listed release is free of it.");
    }

    /**
     * The newest release of the entry {@code slug} names that is neither yanked nor named by an advisory: the row's
     * offered tag, which {@link CatalogIndex} chooses past both.
     */
    public static Optional<String> newestUnaffected(CatalogIndex catalog, String slug) {
        return Stream.concat(catalog.plugins().stream().filter(p -> p.repo().equalsIgnoreCase(slug)
                                || p.formerly().stream().anyMatch(slug::equalsIgnoreCase)).map(PluginRegistry.Plugin::tag),
                        catalog.botAt(slug).stream().map(GalleryEntry::tag))
                .filter(t -> t != null && !t.isBlank()).findFirst();
    }

    /** {@code slug} and every other slug of the row it names. */
    private static List<String> slugsOf(CatalogIndex catalog, String slug) {
        List<String> slugs = new ArrayList<>(List.of(slug));
        catalog.plugins().stream().filter(p -> p.repo().equalsIgnoreCase(slug)
                        || p.formerly().stream().anyMatch(slug::equalsIgnoreCase)).findFirst()
                .ifPresent(p -> {
                    slugs.add(p.repo());
                    slugs.addAll(p.formerly());
                });
        catalog.botAt(slug).ifPresent(b -> {
            slugs.add(b.slug());
            slugs.addAll(b.formerly());
        });
        return slugs;
    }
}
