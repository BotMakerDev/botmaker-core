package com.botmaker.core.plugin;

import com.botmaker.cli.catalog.Catalog;
import com.botmaker.cli.catalog.DerivedFacts;
import com.botmaker.core.config.HostContract;
import com.botmaker.core.services.LocalBuilds;
import com.botmaker.core.services.MavenService;
import com.botmaker.core.services.ProgressReporter;
import com.botmaker.core.sharing.PluginRegistry;
import com.botmaker.core.sharing.PublisherRank;
import com.botmaker.plugin.host.PluginPoms;
import com.botmaker.shared.github.SemVer;
import com.botmaker.shared.tools.UserDirs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The plugins the core binds with no project open (2026-11-10, the user's call): every plugin the catalog lists from
 * an admin or a trusted publisher, at its newest release whose contract this host serves, so their assistant tools
 * work before any bot is open. A community plugin's code runs only once the user opens a bot that names it.
 *
 * <p>They are resolved as a bot's libraries are, from a pom generated in the cache, and each plugin's jar and pom are
 * checked against the sha256 the catalog pinned (ReleasePins): a jar that is not the one approved is left out. A core
 * run from a checkout takes a plugin's local {@code -SNAPSHOT} build from {@code ~/.m2} first, unchecked, as a dev
 * Studio offers local builds first.
 */
public final class HomePlugins {

    /** A Maven coordinate part the generated pom may carry: nothing that needs escaping. */
    private static final Pattern PART = Pattern.compile("[A-Za-z0-9_.-]+");
    /** Maven's local repository, where a local build is installed. */
    private static final Path M2 = Path.of(System.getProperty("user.home"), ".m2", "repository");

    private HomePlugins() {}

    /**
     * One plugin the core binds with no project.
     *
     * @param version the catalog's tag, or a local build's {@code -SNAPSHOT}
     * @param dev     a local build, which the catalog pinned nothing of
     */
    public record Choice(PluginRegistry.Plugin row, String version, boolean dev) {

        String said() {
            return row.name() + " " + version + (dev ? " (local build)" : "");
        }
    }

    /**
     * What the core binds with no project, and what it says about it.
     *
     * @param classpath the jars to bind
     * @param dev       whether a local build is among them, so the bind lets {@code -SNAPSHOT}s through
     * @param said      one line per plugin bound or left out, for {@code core_status}
     */
    public record Home(List<String> classpath, boolean dev, List<String> said) {

        public static final Home NONE = new Home(List.of(), false, List.of());

        public Home {
            classpath = List.copyOf(classpath);
            said = List.copyOf(said);
        }
    }

    /**
     * The catalog's admin and trusted plugins resolved and checked: read from the catalog (one request per session),
     * resolved in {@code <cache>/home-plugins}. Never throws; a catalog that cannot be read is no plugin.
     */
    public static Home resolve() {
        try {
            List<PluginRegistry.Plugin> rows = new PluginRegistry().browse().join();
            boolean devCore = HostContract.devVersion().endsWith("-SNAPSHOT");
            List<Choice> chosen = chosen(rows, HostContract.version(), devCore ? LocalBuilds.scan() : List.of());
            if (chosen.isEmpty()) return new Home(List.of(), false, List.of("The catalog lists no plugin to bind."));
            Path dir = UserDirs.cache().resolve("home-plugins");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("pom.xml"), pom(chosen));
            MavenService.Resolution resolution = MavenService.resolve(dir, ProgressReporter.NONE);
            Home home = checked(rows, resolution, devCore, new ReleasePins(Set::of, seen -> {}));
            return devCore ? copied(home, M2, dir.resolve("m2")) : home;
        } catch (IOException | RuntimeException e) {
            return new Home(List.of(), false, List.of("The catalog's plugins could not be resolved: " + e.getMessage()));
        }
    }

    /**
     * {@code home} with each {@code -SNAPSHOT} jar under {@code m2} bound from a copy under {@code copies}, in the same
     * layout: a bound jar stays open while no project is, and on Windows an open jar in {@code ~/.m2} fails the next
     * {@code mvn install} of it. A jar that cannot be copied is bound where it is.
     */
    static Home copied(Home home, Path m2, Path copies) {
        List<String> classpath = new ArrayList<>();
        for (String entry : home.classpath()) {
            Path jar = Path.of(entry);
            if (!ReleasedPlugins.isDevVersion(ReleasedPlugins.versionOf(jar)) || !jar.startsWith(m2)) {
                classpath.add(entry);
                continue;
            }
            Path copy = copies.resolve(m2.relativize(jar));
            try {
                Files.createDirectories(copy.getParent());
                Files.copy(jar, copy, StandardCopyOption.REPLACE_EXISTING);
                Path pom = jar.resolveSibling(jar.getFileName().toString().replaceFirst("\\.jar$", ".pom"));
                if (Files.isRegularFile(pom)) Files.copy(pom, copy.resolveSibling(pom.getFileName()),
                        StandardCopyOption.REPLACE_EXISTING);
                classpath.add(copy.toString());
            } catch (IOException e) {
                classpath.add(entry);
            }
        }
        return new Home(classpath, home.dev(), home.said());
    }

    /** Binds {@code home}'s plugins with no project, replacing what was bound. */
    public static void bind(Home home) {
        PluginHost.bindHome(home.classpath(), home.dev());
    }

    /**
     * The plugins of {@code rows} the core binds: admin and trusted only, each at its newest release whose
     * {@code minContractVersion} is at most {@code contract} and whose jar and pom are pinned, or at its newest build
     * in {@code local}. A plugin another one's release brings is not declared beside it: its bringer's pom pins its
     * version (nearest wins would otherwise mix two).
     */
    static List<Choice> chosen(List<PluginRegistry.Plugin> rows, String contract, List<LocalBuilds.Build> local) {
        List<Choice> picked = new ArrayList<>();
        for (PluginRegistry.Plugin row : rows) {
            if (row.rank() == PublisherRank.COMMUNITY || !row.isInstallable()) continue;
            if (!PART.matcher(row.groupId()).matches() || !PART.matcher(row.artifactId()).matches()) continue;
            Optional<LocalBuilds.Build> build = local.stream()
                    .filter(b -> b.coordinate().equals(row.coordinate())).findFirst();
            if (build.isPresent() && PART.matcher(build.get().version()).matches()) {
                picked.add(new Choice(row, build.get().version(), true));
                continue;
            }
            row.releases().stream().filter(r -> served(r, contract) && r.jarSha256() != null && r.pomSha256() != null)
                    .filter(r -> PART.matcher(r.tag()).matches()).findFirst()
                    .ifPresent(r -> picked.add(new Choice(row, r.tag(), false)));
        }
        Set<String> brought = new HashSet<>();
        for (Choice choice : picked) {
            // A local build's pom is not the catalog's: what its newest release brings stands for it.
            if (choice.dev()) {
                brought.addAll(choice.row().brings());
                continue;
            }
            choice.row().release(choice.version()).ifPresent(r -> r.plugins().stream().filter(Objects::nonNull)
                    .map(DerivedFacts.Pin::repo).filter(Objects::nonNull).forEach(brought::add));
        }
        // A local build stays declared: nearest wins, so it is the one bound, not its bringer's pinned release.
        return picked.stream().filter(c -> c.dev() || !brought.contains(c.row().repo())).toList();
    }

    /** Whether this host's {@code contract} serves {@code release}: its oldest contract is not newer. */
    static boolean served(Catalog.Plugin.Release release, String contract) {
        String oldest = release.minContractVersion();
        if (oldest == null || oldest.isBlank()) return true;
        return SemVer.isValid(oldest) && SemVer.isValid(contract) && SemVer.compare(oldest, contract) <= 0;
    }

    /** A pom declaring {@code chosen}, nothing else: the core's contract is the host's, so none is named. */
    static String pom(List<Choice> chosen) {
        StringBuilder out = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <!-- Written by BotMaker's core: the catalog's plugins it binds with no bot open. -->
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.botmaker.home</groupId>
                  <artifactId>home-plugins</artifactId>
                  <version>1</version>
                  <dependencies>
                """);
        for (Choice choice : chosen) {
            out.append("    <dependency><groupId>").append(choice.row().groupId()).append("</groupId><artifactId>")
                    .append(choice.row().artifactId()).append("</artifactId><version>").append(choice.version())
                    .append("</version></dependency>\n");
        }
        return out.append("  </dependencies>\n</project>\n").toString();
    }

    /**
     * {@code resolution}'s jars with every plugin's jar checked: one the catalog does not list, one a community
     * publisher's (it loads only in a bot that names it), and one that is not the release the catalog approved are left
     * out, and said. On a {@code devCore}, a {@code -SNAPSHOT} is a local build, unchecked.
     */
    static Home checked(List<PluginRegistry.Plugin> rows, MavenService.Resolution resolution, boolean devCore,
                        ReleasePins pins) {
        List<String> said = new ArrayList<>();
        List<String> classpath = new ArrayList<>();
        Set<String> devJars = new HashSet<>();
        for (String entry : resolution.jars()) {
            Path jar = Path.of(entry);
            Optional<PluginRegistry.Plugin> row = rows.stream().filter(r -> isOf(jar, r)).findFirst();
            if (row.isEmpty()) {
                if (PluginPoms.declaresPlugin(jar)) {
                    said.add(jar.getFileName() + " left out: it declares a plugin the catalog does not list.");
                } else {
                    classpath.add(entry);
                }
                continue;
            }
            String version = ReleasedPlugins.versionOf(jar);
            if (row.get().rank() == PublisherRank.COMMUNITY) {
                said.add(row.get().name() + " " + version + " left out: a community plugin loads only in a bot that "
                        + "names it.");
                continue;
            }
            boolean dev = devCore && ReleasedPlugins.isDevVersion(version);
            ReleasePins.Verdict verdict = dev ? ReleasePins.Verdict.APPROVED : pins.check(jar, row.get(), version);
            if (verdict == ReleasePins.Verdict.APPROVED) {
                classpath.add(entry);
                if (dev) devJars.add(entry);
                said.add(new Choice(row.get(), version, dev).said());
            } else {
                said.add(row.get().name() + " " + version + " left out: its jar is not the one the catalog approved ("
                        + verdict.name().toLowerCase() + ").");
            }
        }
        resolution.problems().forEach(problem -> said.add("Did not resolve: " + problem));
        return new Home(classpath, !devJars.isEmpty(), said);
    }

    /** Whether {@code jar} is {@code row}'s, in the Maven layout {@code <group path>/<artifact>/<version>/<file>}. */
    static boolean isOf(Path jar, PluginRegistry.Plugin row) {
        Path versionDir = jar.getParent();
        Path artifactDir = versionDir == null ? null : versionDir.getParent();
        Path groupDir = artifactDir == null ? null : artifactDir.getParent();
        if (groupDir == null || !artifactDir.getFileName().toString().equals(row.artifactId())) return false;
        return groupDir.endsWith(Path.of("", row.groupId().split("\\.")));
    }
}
