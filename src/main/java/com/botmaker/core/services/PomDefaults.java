// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.services;

import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.runtime.JavacRelease;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Repository;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** What a pom Studio writes holds, and which entries of any project's pom are BotMaker's rather than the user's. */
final class PomDefaults {

    private PomDefaults() {}

    /** Default remote repositories used both in generated POMs and during resolution. */
    static final Map<String, String> DEFAULT_REPOSITORIES = new LinkedHashMap<>();
    static {
        DEFAULT_REPOSITORIES.put("central", "https://repo.maven.apache.org/maven2/");
        DEFAULT_REPOSITORIES.put("jitpack", "https://jitpack.io");
        DEFAULT_REPOSITORIES.put("google", "https://dl.google.com/dl/android/maven2/");
    }

    /** Dependencies every generated project gets (mirrors the old build.gradle). */
    private record Dep(String groupId, String artifactId, String version, String scope) {}

    // There is no TOOLKIT_FALLBACK_VERSION any more, and its absence is the point (2026-09-06).
    //
    // A generated pom used to declare botmaker-plugin-toolkit at a version this constant held, `provided`,
    // for the SDK plugin's sake. It was a landmine rather than a service: botmaker-sdk has declared the
    // toolkit at plain `compile` scope since 2026-09-04, so it is transitive and arrives with the SDK — and
    // Maven's nearest-wins mediation makes a bot's own direct entry BEAT the version the pinned SDK
    // resolved. An SDK built against a newer toolkit therefore failed at runtime with NoSuchMethodError,
    // in a project whose pom looked deliberate. Two pins for one artifact is what this constant was.
    //
    // Do not reinstate it. Whichever toolkit the pinned SDK was built against is the only right answer, and
    // the SDK's own pom is the only thing that knows it. release.sh's PLUGIN_TOOLKIT -> STUDIO forcing edge
    // and check_fallback_versions' second constant went with it.

    /**
     * The whole of a <b>blank</b> project's dependencies: a test framework, and nothing else.
     *
     * <p><b>A blank project names no plugin, and therefore has no bot API</b> (2026-09-04). Until then every
     * project Studio created pinned {@code botmaker-sdk} plus the eight entries below, so "blank" meant a bot
     * project with a {@code System.out.println} in it — and a user who wanted a plain Java project could not
     * have one. The platform's own rule is that the SDK is one plugin among any number; a starting point that
     * names it is a starting point that has chosen one, which is a choice belonging to the person starting.
     *
     * <p>So a blank project opens with <b>no palette, no plugin toolbar buttons, no pictures, no capture and
     * no pilot</b>. That is not a degraded state to be minimised — it is what a project with no plugins
     * installed looks like, and it is reachable in one step from **Project ▸ Manage Plugins…**, which
     * installs the SDK as an ordinary dependency exactly as it installs anybody else's plugin. The richer
     * starting point is a published bot, cloned by New Project.
     *
     * <p>JUnit is here rather than in {@link #BOT_DEPENDENCIES} because it is the one entry that is not about
     * BotMaker at all: it is what a Maven project is expected to come with, and a user who writes a test on
     * day one should not have to add it. {@link #DEFAULT_REPOSITORIES} is unchanged for both shapes, so a
     * blank project can <em>add</em> a BotMaker library later without anybody hand-editing a repository in.
     */
    private static final List<Dep> BLANK_DEPENDENCIES = List.of(
            new Dep("org.junit.jupiter", "junit-jupiter", "5.9.3", "test")
    );

    /**
     * What a pom that names the SDK plugin has to carry — <b>the list, kept, for a project that wants one</b>.
     *
     * <p>Nothing Studio creates writes this any more: a blank project takes {@link #BLANK_DEPENDENCIES} and a
     * project made from a published bot brings its author's own pom, versions and all. It stays because it
     * is the written statement of the paragraph below, which a bot's author needs and which is not
     * derivable from anywhere else, and because {@link #DEFAULT_GROUP_ARTIFACTS} must go on recognising every
     * one of these in the pom of a project that already exists.
     *
     * <h2>Every entry is a library the <em>bot's own source</em> may name</h2>
     *
     * <p>The list held five more until 2026-09-06, and every one of them was here on behalf of a
     * <b>plugin</b> rather than of the bot. Three left in the morning: {@code botmaker-plugin-toolkit}, which
     * the SDK declares at {@code compile} scope and so brings transitively — a direct entry here
     * <em>outranked</em> it by nearest-wins, pinning a bot to a toolkit its SDK was not built against — and
     * {@code javafx-controls}/{@code javafx-graphics}, which {@code PluginLoader} resolves <b>parent-first</b>
     * so the host's own copy is what every plugin links whatever a bot's pom says.
     *
     * <p>The last two, {@code io.javalin:javalin} and {@code com.google.zxing:core}, were real — the SDK's
     * plugin half needs a web server and a QR encoder, its pom marks both {@code optional} so a headless bot
     * links neither, and <b>{@code optional} is not transitive</b>. What was wrong was <em>where they were
     * written down</em>: in Studio, spelled out for one plugin, so the second plugin to need something of its
     * own had no way to say so. They are {@code editorDependencies} in that plugin's registry entry now, and
     * {@link MavenService#installPlugin} declares whatever the entry names, for any plugin. Nothing here lists
     * them, and {@link #isDefaultDependency} is what stops them reading as user libraries.
     *
     * <p><b>The generalisation, restated:</b> a plugin's dependencies are declared by whoever puts that
     * plugin on a classpath — here that is this pom, because this pom is what names the plugin. What changed
     * is that the list of them is the plugin's own to publish rather than Studio's to know.
     */
    private static final List<Dep> BOT_DEPENDENCIES = List.of(
            new Dep(MavenService.SDK_GROUP_ID, MavenService.SDK_ARTIFACT_ID, MavenService.SDK_FALLBACK_VERSION, null),
            new Dep("net.java.dev.jna", "jna", "5.13.0", null),
            new Dep("net.java.dev.jna", "jna-platform", "5.13.0", null),
            // Jackson stays even though nothing generated needs it any more: a java-model project's settings
            // are Java literals, not a JSON read at startup. It is on the list for what the user might write,
            // and taking it away would break a bot that imports it for a gain nobody would notice.
            new Dep("com.fasterxml.jackson.core", "jackson-databind", "2.15.2", null),
            new Dep("org.junit.jupiter", "junit-jupiter", "5.9.3", "test")
    );

    /**
     * Coordinates {@link #BOT_DEPENDENCIES} <b>used to</b> write and no longer does — recognised here, never
     * generated.
     *
     * <p>The lists above describe the pom Studio writes today; this set is why removing an entry from one of
     * them is not enough. {@link #isDefaultDependency} classifies the pom of <em>any</em> project, and every
     * bot created before 2026-09-06 declares these three. Dropped from the union, they would read as user
     * libraries — offered for deletion in Manage Libraries and then genuinely discarded by
     * {@link MavenService#writeUserLibraries}, which keeps only what is recognised. A dependency stops being
     * written long before the last pom that has it is opened, so the two questions are separate and this set is
     * the second one's answer.
     */
    private static final Set<String> RETIRED_GROUP_ARTIFACTS = Set.of(
            // Retired 2026-09-06. The SDK brings the toolkit transitively at compile scope, and a direct
            // entry outranked it by nearest-wins; JavaFX is parent-first in PluginLoader, so the host's own
            // is what every plugin links. Left in an existing pom they are harmless — an unused provided
            // dependency — and taking them out is Manage Libraries' business, not a rewrite's.
            // The old groupId is the one every such pom actually names: the coordinate moved on 2026-10-05.
            "com.github.LiQiyeDev:botmaker-plugin-toolkit",
            "com.github.BotMakerDev:botmaker-plugin-toolkit",
            "org.openjfx:javafx-controls",
            "org.openjfx:javafx-graphics");

    /**
     * {@code groupId:artifactId} of every built-in dependency — these are never treated as user libraries.
     *
     * <p>A "user library" is anything in the pom that is not on this list, so this set and the pom writers
     * must stay one list or the dialog offers to delete a dependency it cannot re-add. That is an argument
     * for one owner, and the owner is whoever writes the pom — Studio, again.
     *
     * <p><b>It is the union of both lists, and narrowing it to the blank one would be a data-loss bug.</b>
     * This classifies the dependencies of <em>any</em> project's pom, not only of one Studio wrote today: a
     * bot created before 2026-09-04, and every project unpacked from a gallery template, carries the
     * {@link #BOT_DEPENDENCIES} set. Left out of here, the SDK, the toolkit and JavaFX would read as user
     * libraries — offered for deletion in Manage Libraries, and then genuinely dropped by
     * {@link MavenService#writeUserLibraries}, which keeps what {@link #isDefaultDependency} recognises and
     * discards the rest.
     */
    private static final Set<String> DEFAULT_GROUP_ARTIFACTS =
            Stream.concat(
                            Stream.concat(
                                            BLANK_DEPENDENCIES.stream(), BOT_DEPENDENCIES.stream())
                                    .map(d -> d.groupId() + ":" + d.artifactId()),
                            RETIRED_GROUP_ARTIFACTS.stream())
                    .collect(Collectors.toUnmodifiableSet());

    /**
     * Whether a pom entry is BotMaker's rather than the user's — <b>a named coordinate, or any
     * {@code provided} dependency</b>.
     *
     * <p>The second arm arrived 2026-09-06 with {@code editorDependencies}, and it is the only rule that can
     * work: what a plugin needs in the editor is declared by <em>that plugin's</em> registry entry, so the
     * set of coordinates is open and Studio cannot hold a list of it. What it can say is that
     * {@link MavenService#installPlugin} writes them at {@code provided} and nothing else in a bot's pom is
     * {@code provided} — the scope means <i>present while the code is edited, absent when the bot runs</i>,
     * which is exactly what an editor-side companion is and is nothing a user adds through Manage Libraries
     * (that dialog writes no scope at all).
     *
     * <p>The cost is bounded and one-directional: a {@code provided} dependency somebody hand-added is not
     * <em>listed</em> in Manage Libraries, so it cannot be removed there. It is not <em>lost</em> —
     * {@link MavenService#writeUserLibraries} keeps everything this method recognises — and the failure it
     * replaces was the other way round, a companion offered for deletion and then genuinely discarded.
     */
    static boolean isDefaultDependency(Dependency d) {
        return DEFAULT_GROUP_ARTIFACTS.contains(d.getGroupId() + ":" + d.getArtifactId())
                || "provided".equals(d.getScope());
    }

    /**
     * Builds a {@code pom.xml} for the given project using the Maven Model API and returns it as text. The
     * model is assembled as an object graph and serialized with {@link MavenXpp3Writer} — no XML string
     * templating, so a project name with an {@code &} in it cannot produce a pom that does not parse.
     *
     * <p><b>{@code sdkVersion} is {@code null} for a blank project</b>, which is the one thing this method
     * branches on: it selects {@link #BLANK_DEPENDENCIES} over {@link #BOT_DEPENDENCIES}. Null rather than
     * blank, because blank already means "pin the fallback" to every caller that ever passed a user's typed
     * version through, and a project with no SDK at all is a different statement from one whose version was
     * left empty. Both public spellings ({@link MavenService#blankPomXml}, {@link MavenService#botPomXml}) say
     * which they mean in their names.
     *
     * <p>Text rather than a write, because project <em>creation</em> does not write this file itself: it
     * hands the text to {@code Authoring.createProject}, which commits it in the same all-or-none pass as
     * the files the SDK owns. Composing it here and committing it there is what keeps both halves — Studio
     * knows the whole dependency set (the SDK is only one plugin), and a failed creation still leaves
     * nothing behind.
     */
    static String pomXml(ProjectConfig cfg, String resolvedSdkVersion) {
        List<Dep> dependencies = resolvedSdkVersion == null ? BLANK_DEPENDENCIES : BOT_DEPENDENCIES;
        Model model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId("com." + cfg.packageName());
        model.setArtifactId(cfg.projectName());
        model.setVersion("0.0.1-SNAPSHOT");
        model.setPackaging("jar");

        Properties props = new Properties();
        props.setProperty("maven.compiler.release", String.valueOf(JavacRelease.NEW_PROJECT));
        props.setProperty("project.build.sourceEncoding", "UTF-8");
        model.setProperties(props);

        DEFAULT_REPOSITORIES.forEach((id, url) -> {
            Repository repo = new Repository();
            repo.setId(id);
            repo.setUrl(url);
            model.addRepository(repo);
        });

        for (Dep d : dependencies) {
            Dependency dep = new Dependency();
            dep.setGroupId(d.groupId());
            dep.setArtifactId(d.artifactId());
            boolean isSdk = MavenService.SDK_GROUP_ID.equals(d.groupId())
                    && MavenService.SDK_ARTIFACT_ID.equals(d.artifactId());
            dep.setVersion(isSdk ? resolvedSdkVersion : d.version());
            if (d.scope() != null) dep.setScope(d.scope());
            model.addDependency(dep);
        }

        StringWriter out = new StringWriter();
        try {
            new MavenXpp3Writer().write(out, model);
        } catch (IOException impossible) {
            // A StringWriter does not fail. Wrapping rather than declaring keeps every caller's throws
            // clause about the filesystem, which is the only IO any of them can do anything about.
            throw new UncheckedIOException(impossible);
        }
        return out.toString();
    }
}
