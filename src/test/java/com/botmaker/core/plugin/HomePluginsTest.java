package com.botmaker.core.plugin;

import com.botmaker.cli.catalog.Catalog;
import com.botmaker.cli.catalog.DerivedFacts;
import com.botmaker.core.services.LocalBuilds;
import com.botmaker.core.services.MavenService;
import com.botmaker.core.sharing.PluginRegistry;
import com.botmaker.core.sharing.PublisherRank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The catalog's plugins the core binds with no project: which, at what release, and only the jars it approved. */
class HomePluginsTest {

    private static final String GROUP = "com.github.BotMakerDev";

    private static Catalog.Plugin.Release release(String tag, String minContract, DerivedFacts.Pin... brings) {
        return new Catalog.Plugin.Release(tag, "c0ffee", "a".repeat(64), "b".repeat(64), minContract, List.of(),
                List.of(brings));
    }

    private static PluginRegistry.Plugin row(String artifact, PublisherRank rank, Catalog.Plugin.Release... releases) {
        return new PluginRegistry.Plugin(artifact, GROUP + ":" + artifact, "BotMakerDev/" + artifact, "", List.of(),
                "", List.of(), "", releases[0].tag(), rank, 0, List.of(releases));
    }

    @Test
    void adminAndTrustedOnlyEachAtItsNewestReleaseThisContractServes() {
        var sdk = row("botmaker-sdk", PublisherRank.ADMIN, release("v2.0.0", "0.16.0"), release("v1.9.0", "0.15.0"));
        var trusted = row("ocr", PublisherRank.TRUSTED, release("v1.0.0", ""));
        var community = row("macros", PublisherRank.COMMUNITY, release("v3.0.0", "0.15.0"));
        var unpinned = row("raw", PublisherRank.ADMIN,
                new Catalog.Plugin.Release("v1.0.0", "c0ffee", null, null, "", List.of(), List.of()));

        List<HomePlugins.Choice> chosen = HomePlugins.chosen(List.of(sdk, trusted, community, unpinned), "v0.15.0",
                List.of());

        assertEquals(List.of("botmaker-sdk v1.9.0", "ocr v1.0.0"),
                chosen.stream().map(c -> c.row().artifactId() + " " + c.version()).toList());
    }

    @Test
    void aPluginAnotherBringsIsNotDeclaredBesideIt() {
        var basics = row("botmaker-plugin-basics", PublisherRank.ADMIN, release("v1.4.0", ""));
        var sdk = row("botmaker-sdk", PublisherRank.ADMIN,
                release("v1.9.0", "", new DerivedFacts.Pin("BotMakerDev/botmaker-plugin-basics", "v1.3.0")));

        List<HomePlugins.Choice> chosen = HomePlugins.chosen(List.of(basics, sdk), "v0.15.0", List.of());

        assertEquals(List.of("botmaker-sdk"), chosen.stream().map(c -> c.row().artifactId()).toList());
    }

    @Test
    void aLocalBuildComesFirstOnADevCore() {
        var sdk = row("botmaker-sdk", PublisherRank.ADMIN, release("v1.9.0", ""));
        var local = new LocalBuilds.Build(GROUP, "botmaker-sdk", "2.1.0-SNAPSHOT", Instant.EPOCH);

        HomePlugins.Choice chosen = HomePlugins.chosen(List.of(sdk), "v0.15.0", List.of(local)).getFirst();

        assertEquals("2.1.0-SNAPSHOT", chosen.version());
        assertTrue(chosen.dev());
    }

    @Test
    void thePomDeclaresThemAndNothingElse() {
        var sdk = row("botmaker-sdk", PublisherRank.ADMIN, release("v1.9.0", ""));
        String pom = HomePlugins.pom(List.of(new HomePlugins.Choice(sdk, "v1.9.0", false)));

        assertTrue(pom.contains("<groupId>" + GROUP + "</groupId><artifactId>botmaker-sdk</artifactId>"
                + "<version>v1.9.0</version>"), pom);
        assertFalse(pom.contains("botmaker-studio-api"), "the contract is the host's");
    }

    @Test
    void aJarThatIsNotTheApprovedOneIsLeftOut(@TempDir Path m2) throws IOException {
        Path good = jar(m2, "botmaker-sdk", "v1.9.0", "sdk");
        Path bad = jar(m2, "ocr", "v1.0.0", "ocr, rebuilt");
        Path library = jar(m2, "gson", "2.11.0", "a library");
        var sdk = row("botmaker-sdk", PublisherRank.ADMIN, pinned(good, "v1.9.0"));
        var ocr = row("ocr", PublisherRank.TRUSTED, release("v1.0.0", ""));

        HomePlugins.Home home = HomePlugins.checked(List.of(sdk, ocr),
                new MavenService.Resolution(List.of(good.toString(), bad.toString(), library.toString()), List.of()),
                false, new ReleasePins(Set::of, seen -> {}));

        assertEquals(List.of(good.toString(), library.toString()), home.classpath());
        assertTrue(home.said().stream().anyMatch(line -> line.startsWith("ocr v1.0.0 left out")), home.said()::toString);
        assertFalse(home.dev());
    }

    @Test
    void aDevCoresSnapshotIsTakenUnchecked(@TempDir Path m2) throws IOException {
        Path local = jar(m2, "botmaker-sdk", "2.1.0-SNAPSHOT", "sdk, built here");
        var sdk = row("botmaker-sdk", PublisherRank.ADMIN, release("v1.9.0", ""));

        HomePlugins.Home home = HomePlugins.checked(List.of(sdk),
                new MavenService.Resolution(List.of(local.toString()), List.of()), true,
                new ReleasePins(Set::of, seen -> {}));

        assertEquals(List.of(local.toString()), home.classpath());
        assertTrue(home.dev());
    }

    @Test
    void aCommunityPluginATrustedOneBringsIsLeftOut(@TempDir Path m2) throws IOException {
        Path brought = jar(m2, "macros", "v3.0.0", "community code");
        var macros = row("macros", PublisherRank.COMMUNITY, pinned(brought, "v3.0.0"));

        HomePlugins.Home home = HomePlugins.checked(List.of(macros),
                new MavenService.Resolution(List.of(brought.toString()), List.of()), false,
                new ReleasePins(Set::of, seen -> {}));

        assertTrue(home.classpath().isEmpty());
        assertTrue(home.said().getFirst().contains("community"), home.said()::toString);
    }

    @Test
    void aLocalBuildStaysDeclaredThoughAnotherBringsIt() {
        var basics = row("botmaker-plugin-basics", PublisherRank.ADMIN, release("v1.4.0", ""));
        var sdk = row("botmaker-sdk", PublisherRank.ADMIN,
                release("v1.9.0", "", new DerivedFacts.Pin("BotMakerDev/botmaker-plugin-basics", "v1.3.0")));
        var local = new LocalBuilds.Build(GROUP, "botmaker-plugin-basics", "1.5.0-SNAPSHOT", Instant.EPOCH);

        List<HomePlugins.Choice> chosen = HomePlugins.chosen(List.of(basics, sdk), "v0.15.0", List.of(local));

        assertEquals(List.of("botmaker-plugin-basics 1.5.0-SNAPSHOT", "botmaker-sdk v1.9.0"),
                chosen.stream().map(c -> c.row().artifactId() + " " + c.version()).toList());
    }

    @Test
    void aLocalBuildIsBoundFromACopySoItsJarCanBeRebuilt(@TempDir Path m2, @TempDir Path cache) throws IOException {
        Path local = jar(m2, "botmaker-sdk", "2.1.0-SNAPSHOT", "sdk, built here");
        Path release = jar(m2, "gson", "2.11.0", "a library");

        HomePlugins.Home home = HomePlugins.copied(
                new HomePlugins.Home(List.of(local.toString(), release.toString()), true, List.of()), m2, cache);

        Path copy = cache.resolve(m2.relativize(local));
        assertEquals(List.of(copy.toString(), release.toString()), home.classpath());
        assertEquals("sdk, built here", Files.readString(copy));
        assertTrue(Files.isRegularFile(copy.resolveSibling("botmaker-sdk-2.1.0-SNAPSHOT.pom")));
    }

    @Test
    void withNoBotTheBotsServicesRefuseSayingSo() {
        HomeServices services = new HomeServices();

        assertThrows(HomeServices.NeedsBot.class, services::projectDir);
        assertThrows(HomeServices.NeedsBot.class, () -> services.pluginValues().ids());
        assertThrows(HomeServices.NeedsBot.class, () -> services.runs().start());
        assertFalse(services.hasUi());
        assertTrue(services.stateDir().isEmpty());
    }

    /** A jar and its pom in the Maven layout under {@code m2}, the jar holding {@code text}. */
    private static Path jar(Path m2, String artifact, String version, String text) throws IOException {
        Path dir = m2.resolve(Path.of("com", "github", "BotMakerDev", artifact, version));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(artifact + "-" + version + ".pom"), "<project/>");
        return Files.writeString(dir.resolve(artifact + "-" + version + ".jar"), text);
    }

    /** A release pinning {@code jar} and its pom as they are. */
    private static Catalog.Plugin.Release pinned(Path jar, String tag) {
        String artifact = jar.getParent().getParent().getFileName().toString();
        return new Catalog.Plugin.Release(tag, "c0ffee", ReleasePins.sha256(jar).orElseThrow(),
                ReleasePins.sha256(ReleasePins.pomOf(jar, artifact, tag)).orElseThrow(), "", List.of(), List.of());
    }
}
