// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.sharing;

import com.botmaker.cli.bmk.BotJson;
import com.botmaker.cli.catalog.Advisory;
import com.botmaker.cli.catalog.Catalog;
import com.botmaker.cli.catalog.Rank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The catalog's advisories as the core enforces them (spec 2026-11-21 § 5). */
class AdvisoriesTest {

    @TempDir
    Path config;

    private static final Advisory LEAK = new Advisory("BMA-2026-0001", "olddev/firestone", List.of("v1", "v2"),
            Advisory.Severity.HIGH, "Sends the account's token away", "https://example.org/bma-1", "2026-11-21", null);

    /** A catalog listing somedev/firestone, once olddev/firestone, at v1 to v3, with {@code advisories}. */
    private static CatalogIndex catalog(Advisory... advisories) {
        Catalog.Bot bot = new Catalog.Bot("somedev/firestone", Rank.TRUSTED, "", List.of(), 0, null,
                List.of(release("v3"), release("v2"), release("v1")), null, null, List.of("olddev/firestone"));
        return CatalogIndex.parse(new Catalog(6, "2026-11-21T00:00:00Z", List.of(), List.of(bot),
                List.of(advisories)).toJson());
    }

    private static Catalog.Bot.Release release(String tag) {
        return new Catalog.Bot.Release(tag, "a".repeat(40), List.of());
    }

    private static Optional<BotJson> copy(String tag) {
        return Optional.of(new BotJson(BotJson.FORMAT, "firestone", "somedev", "firestone", tag, "a".repeat(40), 25,
                "Main", List.of()));
    }

    @Test
    void anAffectedReleaseIsLeftOutOfPickers() {
        GalleryEntry row = catalog(LEAK).botAt("somedev/firestone").orElseThrow();
        assertEquals("v3", row.tag(), "the newest release no advisory names");
        assertEquals(List.of("v3"), row.approvedTags());
        assertTrue(row.pulledLine("v1").orElseThrow().contains("BMA-2026-0001"), row.pulledLine("v1").toString());
    }

    @Test
    void withdrawnNotListed() {
        CatalogIndex withdrawn = catalog(LEAK.withdraw("never true"));
        assertEquals(List.of(), withdrawn.advisories());
        assertEquals(Optional.empty(), Advisories.affecting(withdrawn, "somedev/firestone", "v1"));
    }

    @Test
    void bmkRefused() {
        Advisories here = new Advisories(config.resolve(Advisories.FILE));
        Advisories.Refused refused = here.refusingRun(copy("v1"), catalog(LEAK)).orElseThrow();
        assertEquals(LEAK, refused.advisory(), "filed under the slug the entry had before its move");
        assertTrue(refused.text().contains("high security advisory, BMA-2026-0001: Sends the account's token away."),
                refused.text());
        assertTrue(refused.text().contains("https://example.org/bma-1"), refused.text());
        assertTrue(refused.text().contains("The newest release it does not name is v3."), refused.text());
        assertEquals(Optional.empty(), here.refusingRun(copy("v3"), catalog(LEAK)));
        assertEquals(Optional.empty(), here.refusingRun(Optional.empty(), catalog(LEAK)), "no run copy");
    }

    @Test
    void overrideAdmits() {
        Advisories here = new Advisories(config.resolve(Advisories.FILE));
        here.override(here.refusingRun(copy("v1"), catalog(LEAK)).orElseThrow());
        assertEquals(Optional.empty(), here.refusingRun(copy("v1"), catalog(LEAK)));
        assertEquals(Optional.empty(), new Advisories(config.resolve(Advisories.FILE)).refusingRun(copy("v1"),
                catalog(LEAK)), "kept on this computer");
    }

    @Test
    void overridingTheWorstStillAsksAboutTheOthers() {
        Advisories here = new Advisories(config.resolve(Advisories.FILE));
        Advisory critical = new Advisory("BMA-2026-0002", "somedev/firestone", List.of("v1"),
                Advisory.Severity.CRITICAL, "Deletes the save.", null, "2026-11-21", null);
        Advisory unknown = new Advisory("BMA-2026-0003", "somedev/firestone", List.of("v1"),
                Advisory.Severity.UNKNOWN, "A word this build does not know.", null, "2026-11-21", null);
        CatalogIndex catalog = catalog(LEAK, critical, unknown);
        Advisories.Refused worst = here.refusingRun(copy("v1"), catalog).orElseThrow();
        assertEquals(critical, worst.advisory(), "critical first, an unknown severity last");
        here.override(worst);
        assertEquals(LEAK, here.refusingRun(copy("v1"), catalog).orElseThrow().advisory(), "the high one still asks");
        here.override(here.refusingRun(copy("v1"), catalog).orElseThrow());
        assertEquals(unknown, here.refusingRun(copy("v1"), catalog).orElseThrow().advisory());
    }

    @Test
    void overrideIsPerRelease() {
        Advisories here = new Advisories(config.resolve(Advisories.FILE));
        here.override(here.refusingRun(copy("v1"), catalog(LEAK)).orElseThrow());
        assertTrue(here.refusingRun(copy("v2"), catalog(LEAK)).isPresent(), "v2 is asked about on its own");
        Advisory extended = LEAK.extend(List.of("v3"));
        assertTrue(here.refusingRun(copy("v3"), catalog(extended)).isPresent(), "extended to v3 later: asked again");
        Advisory another = new Advisory("BMA-2026-0002", "somedev/firestone", List.of("v1"), Advisory.Severity.LOW,
                "Another problem.", null, "2026-11-21", null);
        assertTrue(here.refusingRun(copy("v1"), catalog(another)).isPresent(), "another advisory: asked again");
    }

    @Test
    void offlineStillRefuses() {
        // What CatalogClient keeps is the verified body; read back alone, with no network, it still refuses.
        String kept = new Catalog(6, "2026-11-21T00:00:00Z", List.of(), List.of(), List.of(LEAK)).toJson();
        Advisories here = new Advisories(config.resolve(Advisories.FILE));
        assertTrue(here.refusingRun(Optional.of(new BotJson(BotJson.FORMAT, "firestone", "olddev", "firestone", "v2",
                "a".repeat(40), 25, "Main", List.of())), CatalogIndex.parse(kept)).isPresent());
        assertFalse(CatalogIndex.parse(kept).advisories().isEmpty());
    }
}
