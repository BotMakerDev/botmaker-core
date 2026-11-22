// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.services;

import com.botmaker.cli.bmk.BotFile;
import com.botmaker.cli.bmk.BotFiles;
import com.botmaker.plugin.host.PluginPoms;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.UserLibrary;
import org.apache.maven.model.Model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Maven operations for generated user projects.
 *
 * <p>Replaces the old Gradle integration: the project descriptor (pom.xml) is built with the
 * Maven Model API (no hand-written build string), and dependencies are resolved transitively
 * in-process with Maven Resolver (Aether) — no system {@code mvn} binary is required.
 *
 * <p>The work is in one class per kind: {@link PomDefaults} (what a pom Studio writes holds), {@link PomEdits},
 * {@link PluginShadows} and {@link MavenResolver}, over {@link PomModel}. The fallback versions stay here: the
 * release rewrites them in this file by name ({@code sluice.yml}).
 */
public final class MavenService {

    private MavenService() {}

    // The pom is Studio's again (2026-08-26), one day after phase 3 moved it to the SDK. The reversal is
    // recorded rather than tidied away because the argument that moved it was not wrong, only incomplete:
    // the pom is not a file *about* the SDK, it is the file that *declares which* SDK — and, once there is
    // ever a second plugin, which plugins. The SDK is the editor's default plugin, not the editor; a plugin
    // cannot enumerate its siblings, so a pom it wrote would silently omit theirs. Only the composer can
    // write the manifest of what it composed. Creation still lands in one pass: ProjectCreator hands this
    // text to Authoring.createProject as a caller file.

    /** Maven coordinate of the BotMaker SDK (published from GitHub tags via JitPack). */
    public static final String SDK_GROUP_ID = "com.github.BotMakerDev";
    public static final String SDK_ARTIFACT_ID = "botmaker-sdk";
    /**
     * Version used for the SDK when none is supplied / JitPack is unreachable.
     *
     * <p><b>Hand-typed, and it must stay that way.</b> {@code release.sh} bumps it with a {@code sed} over
     * this string literal on every {@code --sdk} release; deriving it from {@link SdkVersion#latest()} would
     * make that bump silently stop working. What a <em>freshly created</em> pom pins is also a separate
     * question from what this build of the SDK is.
     */
    public static final String SDK_FALLBACK_VERSION = "1.12.1";

    /**
     * The contract tag a dev build of Studio writes into a project ({@code config/HostContract}); a release
     * build writes the tag it was built against instead. Hand-typed for the same reason as
     * {@link #SDK_FALLBACK_VERSION}: the release bumps this literal on every {@code --studio-api} release.
     */
    public static final String CONTRACT_FALLBACK_VERSION = "0.19.1";

    // There is no MIN_SDK_VERSION any more, and its absence is deliberate (2026-08-25).
    //
    // The floor was a statement about generation: below it Studio could not render a generated file, because
    // the templates and the FlowGraph/Wire API they call arrived in 1.1.0. Studio does not generate at all
    // now — the templates left the SDK and its own emitters are not written yet (inversion phase 2) — so a
    // floor would be a comparison with nothing behind it: a banner and a refusal about a capability neither
    // side has. Every pinned SDK therefore opens with no warning, and an incompatibility surfaces where it
    // always could, at compile time, naming the element.
    //
    // Do not reinstate it as a palette floor either. The palette is what the project's own plugins
    // catalogue, loaded from its own jars, which is strictly better than a version comparison.
    // release.sh's check_sdk_floor went with it.

    // localSdkVersions() and localPluginBuilds() — dev-build scans of ~/.m2 for *SNAPSHOT SDK and plugin builds,
    // offered in the version pickers, Browse and New Project — were deleted on 2026-10-01 (the maintainer's
    // call): a bot published from a project pinned to one named a version nobody else could resolve. Since
    // 2026-10-06 a project in dev mode lists them again, through services/LocalBuilds and only there: the
    // pickers outside dev mode and New Project still offer none, and Publish still refuses a SNAPSHOT pin
    // (sharing/PublishRequest.unreleasedPins).

    /**
     * Whether a pom in {@code jar} names a plugin ({@code botmaker.plugin}, 2026-10-27, read by {@link PluginPoms}).
     * A jar whose two poms each name one counts too: the loader refuses it, and the consent question must not
     * pass it as a library.
     *
     * <p><b>This is the project's only definition of "is this a plugin".</b>
     * {@code services/upgrade/InstalledPlugin} asks it of a dependency the pom declares that no registry
     * entry accounts for. A second rule — a naming convention, a registry lookup treated as definitive —
     * would answer differently the first time somebody published a plugin the registry has not seen, which
     * is precisely the case that reader exists for.
     */
    public static boolean declaresPlugin(Path jar) {
        return !PluginPoms.read(jar).isEmpty();
    }

    // =========================================================================
    // POM GENERATION (Maven Model API)
    // =========================================================================

    /**
     * A <b>blank</b> project's {@code pom.xml} as text — {@link PomDefaults#BLANK_DEPENDENCIES}, and no plugin
     * named.
     *
     * <p>This is what project creation writes. See {@link #botPomXml} for the shape a project that wants the
     * SDK carries, and {@link PomDefaults#BLANK_DEPENDENCIES} for why creation no longer writes that one.
     */
    public static String blankPomXml(ProjectConfig cfg) {
        return PomDefaults.pomXml(cfg, null);
    }

    /**
     * A project's {@code pom.xml} as text, <b>naming the SDK plugin</b> and everything its plugin half needs
     * — {@link PomDefaults#BOT_DEPENDENCIES}, with the SDK pinned to {@code sdkVersion} (blank →
     * {@link #SDK_FALLBACK_VERSION}).
     *
     * <p>Nothing creates a project this way since 2026-09-04. It is what {@code ProjectRepair} rebuilds a
     * missing pom as for a project that already had one, and what a test builds a bot-shaped fixture with.
     */
    public static String botPomXml(ProjectConfig cfg, String sdkVersion) {
        return PomDefaults.pomXml(cfg, sdkVersion == null || sdkVersion.isBlank()
                ? SDK_FALLBACK_VERSION : sdkVersion.trim());
    }

    /**
     * Writes a <b>bot-shaped</b> {@code pom.xml} — {@link #botPomXml} — to {@code projectDir/pom.xml}.
     *
     * <p>This is the <em>repair</em> path — restoring a build file somebody deleted out of an otherwise
     * intact project, where re-creating the project would (correctly) refuse. Creation goes through
     * {@link #blankPomXml} instead, so that its write is part of one atomic commit.
     *
     * <p><b>The caller chooses the shape, and must.</b> A repair that always wrote this one would hand an
     * SDK to a blank project that never had one — see {@link #writeBlankPom} and {@code ProjectRepair},
     * which reads {@code settings.json}'s recorded template to decide.
     */
    public static void writePom(Path projectDir, ProjectConfig cfg, String sdkVersion) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("pom.xml"), botPomXml(cfg, sdkVersion));
    }

    /** Writes a <b>blank</b> {@code pom.xml} — {@link #blankPomXml} — to {@code projectDir/pom.xml}. */
    public static void writeBlankPom(Path projectDir, ProjectConfig cfg) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("pom.xml"), blankPomXml(cfg));
    }

    // =========================================================================
    // DEPENDENCY RESOLUTION (Maven Resolver / Aether)
    // =========================================================================

    /**
     * Reads {@code projectDir/pom.xml} and resolves its (non-test) dependencies transitively,
     * returning the absolute paths of all resolved jars from the local {@code ~/.m2} repository.
     * Missing artifacts are downloaded from the POM's repositories (plus Maven Central).
     *
     * <p>Resolution is best-effort: if some artifacts fail, the ones that did resolve are still returned.
     */
    public static List<String> resolveClasspath(Path projectDir) {
        return resolveClasspath(projectDir, ProgressReporter.NONE);
    }

    /**
     * As {@link #resolveClasspath(Path)}, but reports download progress via {@code progress}: a real
     * fraction (aggregated across all concurrent transfers by bytes) plus a short message, e.g.
     * {@code "Downloading opencv-4.9.0.jar"}. It only fires for actual network transfers, so already-cached
     * opens stay quiet. It may be called from Aether's worker threads — callers that touch the UI must
     * marshal onto the FX thread.
     */
    public static List<String> resolveClasspath(Path projectDir, ProgressReporter progress) {
        return resolve(projectDir, progress).jars();
    }

    /**
     * What a resolve produced: the jars that resolved, and one line per dependency that did not.
     *
     * <p>The second half is why this exists (2026-09-29). Resolution was best-effort and said what failed on
     * stderr alone, so a plugin installed at a tag JitPack had not built was written to the pom, resolved to
     * nothing, and simply never appeared — the install reported success and the plugin was not there.
     *
     * @param problems {@code group:artifact:version — reason}, empty when everything resolved
     */
    public record Resolution(List<String> jars, List<String> problems) {

        public Resolution {
            jars = List.copyOf(jars);
            problems = List.copyOf(problems);
        }

        /** Whether {@code groupId:artifactId} is among the dependencies that failed. */
        public boolean failed(String groupId, String artifactId) {
            String prefix = groupId + ":" + artifactId + ":";
            return problems.stream().anyMatch(line -> line.startsWith(prefix));
        }
    }

    /** {@link #resolveClasspath(Path, ProgressReporter)}, keeping what failed. */
    public static Resolution resolve(Path projectDir, ProgressReporter progress) {
        return MavenResolver.resolve(projectDir, progress);
    }

    /**
     * Every jar {@code projectDir}'s pom resolves to, with its coordinate ({@code group:artifact:version}, plus
     * {@code :classifier}), sorted by it: what a {@code .bmk} lists. No native-classifier jar
     * ({@link BotFiles#isNative}), no other type than {@code jar}.
     *
     * @throws IOException naming the dependencies that did not resolve
     */
    public static List<BotFile.Jar> resolvedJars(Path projectDir) throws IOException {
        return MavenResolver.resolvedJars(projectDir);
    }

    /**
     * What {@link #fetch} got: each coordinate's file in {@code ~/.m2}, and the coordinates it could not get
     * (offline, gone from the repository).
     */
    public record Fetched(Map<String, Path> files, List<String> missing) {
        public Fetched {
            files = Map.copyOf(files);
            missing = List.copyOf(missing);
        }
    }

    /**
     * The jars {@code coordinates} name ({@code group:artifact:version[:classifier]}), each alone — no pom read, no
     * dependency followed — from {@code ~/.m2}, else Maven Central, JitPack or Google's repository. For a list that
     * is already whole, a {@code .bmk}'s.
     */
    public static Fetched fetch(List<String> coordinates, ProgressReporter progress) {
        return MavenResolver.fetch(coordinates, progress);
    }

    // =========================================================================
    // USER LIBRARIES (pom.xml is the source of truth)
    // =========================================================================

    /**
     * Reads the user-added libraries from {@code projectDir/pom.xml}: every dependency that is not one
     * of the built-in ones ({@link PomDefaults#isDefaultDependency}). Returns an empty list if the pom is missing
     * or unreadable.
     */
    public static List<UserLibrary> readUserLibraries(Path projectDir) {
        return new PomEdits(projectDir).readUserLibraries();
    }

    /**
     * <b>Every</b> dependency {@code projectDir/pom.xml} declares, the built-in ones included.
     *
     * <p>The second question about a pom, and the one that had no reader until 2026-09-05.
     * {@link #readUserLibraries} answers <i>what did the user add</i> — which is the right question for
     * <b>Manage Libraries</b>, whose rows are things a user may delete. It is the wrong question for
     * <b>Manage Plugins</b>, which asks <i>is this plugin installed</i>: {@code botmaker-sdk} is on
     * {@link PomDefaults#DEFAULT_GROUP_ARTIFACTS}, so the one plugin that exists today was invisible to that
     * check by construction and its row read <i>Install</i> forever. Pressing it again then appended a second
     * {@code botmaker-sdk} dependency, because {@link #writeUserLibraries} keeps the pom's defaults
     * <em>and</em> appends the list it is handed.
     *
     * <p>Versions are whatever the pom says, uninterpolated — a {@code ${property}} pin comes back as text.
     * Every caller so far compares {@code groupId:artifactId} and never the version, which is the same rule
     * {@code PluginRegistry.Plugin.isInstalledIn} states for itself: a plugin pinned to an older version is
     * installed. {@link #resolveArtifact(Path, String, String, String, String)} reads such a pin through the
     * pom's properties, so the text may be handed to it as is.
     */
    public static List<UserLibrary> readDeclaredLibraries(Path projectDir) {
        return new PomEdits(projectDir).readDeclaredLibraries();
    }

    /**
     * The {@code <properties>} of {@code projectDir/pom.xml}, empty when the pom is missing or unreadable.
     *
     * <p>For reading a pin {@link #readDeclaredLibraries} hands back as {@code ${property}} text, which is
     * meaningless to anybody outside this project — a gallery entry naming the plugins a bot requires, say.
     */
    public static Map<String, String> readProperties(Path projectDir) {
        return new PomEdits(projectDir).readProperties();
    }

    /**
     * Declares {@code plugin} in {@code projectDir/pom.xml}, replacing any dependency already on that
     * {@code groupId:artifactId}, and declares at {@code provided} whatever that plugin's registry entry
     * says it needs to load in the editor at all.
     *
     * <p><b>Idempotent by coordinate.</b> The pom is edited in place rather than rebuilt through
     * {@link #writeUserLibraries}, which cannot express this: that method keeps every default dependency the
     * pom already has and appends everything it is handed, so installing a plugin that is <em>also</em> a
     * default — which the SDK is — writes it twice. Two versions of one artifact on a classpath is the least
     * diagnosable failure this platform has.
     *
     * <p><b>{@code editorDependencies} is the plugin's own list, and until 2026-09-06 it was Studio's.</b>
     * The rule is the platform's: <i>whoever puts a plugin on a classpath supplies what that plugin needs</i>,
     * and here that is this pom, because this pom is what names the plugin. What a plugin needs is a fact
     * about the plugin, though, and this method used to hold it as {@code if (isSdk(…))} over a list spelled
     * out in Studio's own source — the one privilege plugin #1 had, and exactly the privilege
     * {@code BrowsePluginsTab}'s javadoc says a plugin platform must not grant. The list comes from the
     * caller now, out of the plugin's registry entry, and this method has no idea which plugin it is
     * installing.
     *
     * <p><b>Why the entry declares them and the plugin's pom cannot.</b> These are precisely the dependencies
     * a resolve does not reach: the SDK marks its web server and QR encoder {@code optional} so a headless
     * bot links neither, and <b>{@code optional} is not transitive</b>. A list that could be read off the pom
     * would not need writing down.
     *
     * <p>They are declared {@code provided} — <i>present while the code is edited, absent when the bot
     * runs</i>. {@link #resolveClasspath} filters out only {@code test}, so they are on the classpath Studio
     * binds plugins from, while {@code mvn package} and the run/debug launchers exclude them by definition,
     * so the headless case the {@code optional} flags protect is still protected.
     *
     * <p>An entry the pom already declares is left alone, version and all — the list is a floor, not a pin
     * this dialog is entitled to move.
     */
    public static void installPlugin(Path projectDir, UserLibrary plugin,
                                     List<UserLibrary> editorDependencies) throws IOException {
        new PomEdits(projectDir).installPlugin(plugin, editorDependencies);
    }

    /**
     * Declares {@code provided} each of {@code companions} the pom of {@code projectDir} lacks, as
     * {@link #installPlugin} would, and answers those it added — empty when the pom was not written.
     */
    public static List<UserLibrary> declareCompanions(Path projectDir, List<UserLibrary> companions)
            throws IOException {
        return new PomEdits(projectDir).declareCompanions(companions);
    }

    /**
     * Removes from {@code projectDir/pom.xml} every declared plugin that another declared plugin already brings,
     * and answers what was removed, each with the plugin that brings it.
     *
     * <p><b>Why a direct entry must go.</b> Maven's nearest-wins makes the pom's own entry beat the version the
     * bringing plugin was built against, and the failure is a linkage error inside that plugin. A template that
     * declares {@code botmaker-plugin-basics} gains the SDK, which brings basics itself, and the template's entry
     * would then pin basics where the SDK never was. The umbrella states the rule; this applies it, for any
     * plugin, after every install (2026-10-01).
     *
     * <p>Plugins only, told apart by the declaring pom in their jar ({@link #declaresPlugin}): a library the bot
     * pins on purpose is the user's to pin. Each declared plugin's tree is collected on its own, so Maven's
     * conflict resolution over the whole pom (which keeps the direct entry and drops the deeper one) cannot hide
     * the edge. Best-effort: a plugin whose tree cannot be collected brings nothing, so nothing is removed for it.
     * May block on the network — call it off the FX thread.
     */
    public static List<Shadowed> dropShadowedPlugins(Path projectDir) throws IOException {
        return new PluginShadows(projectDir).dropShadowedPlugins();
    }

    /**
     * Removes the pom's direct {@code contract} entry when a declared plugin already brings the contract at that
     * version or newer, and answers whether it did (2026-10-05).
     *
     * <p>Studio writes that entry for a bot whose own source imports the contract while nothing else brings it
     * ({@code ContractDependency}). Left in place once a plugin brings it again, nearest-wins would pin the bot
     * to that contract rather than the one the plugin was built against — the trap {@link #dropShadowedPlugins}
     * closes for plugins, and the same rule: a direct entry beside a plugin that brings it goes, whoever wrote
     * it. Three things keep it:
     * <ul>
     *   <li>a plugin bringing an <em>older</em> contract — the bot may use what only the newer one has
     *       ({@code @Refactor} arrived after {@code @Param}), and nearest-wins would then hand it the older;</li>
     *   <li>a plugin whose entry {@code <exclusions>} the contract — it brings nothing here;</li>
     *   <li>a tree that cannot be collected (offline) — it brings nothing, so nothing is removed for it.</li>
     * </ul>
     * An entry nothing imports any more stays: harmless with no plugin, and dropped by this once one arrives.
     * Plugins only, as {@link #dropShadowedPlugins} reads them, so a bot's libraries cost no tree walk. May block
     * on the network: call it off the FX thread.
     */
    public static boolean dropRedundantContract(Path projectDir, UserLibrary contract) throws IOException {
        return new PluginShadows(projectDir).dropRedundantContract(contract);
    }

    /** A declared plugin {@link #dropShadowedPlugins} removed, and the declared plugin that brings it. */
    public record Shadowed(String coordinate, String broughtBy) {

        /** One sentence naming what was removed and why, or {@code ""} for nothing. */
        public static String sentence(List<Shadowed> removed) {
            if (removed == null || removed.isEmpty()) return "";
            return removed.stream()
                    .map(s -> artifact(s.coordinate()) + " is brought by " + artifact(s.broughtBy())
                            + ", so its own entry was removed.")
                    .collect(Collectors.joining(" "));
        }

        private static String artifact(String coordinate) {
            return coordinate.substring(coordinate.indexOf(':') + 1);
        }
    }

    /**
     * Declares {@code library} at {@code compile} scope unless the pom already names its coordinate, and
     * answers whether it wrote. A declared version is never moved: that is Manage Libraries' question.
     */
    public static boolean declareIfAbsent(Path projectDir, UserLibrary library) throws IOException {
        return new PomEdits(projectDir).declareIfAbsent(library);
    }

    /**
     * Removes {@code groupId:artifactId} from {@code projectDir/pom.xml}, with the
     * {@code editorDependencies} {@link #installPlugin} declared alongside it.
     *
     * <p>The mirror image, and it has to be: leaving a {@code provided} web server behind in the pom of a
     * project that no longer names the plugin that needed it is a dependency nothing in the project explains.
     *
     * <p><b>The list is the caller's here too, and that is the one asymmetry worth knowing.</b> Install reads
     * it from the entry of the plugin being installed; remove reads it from the entry of the plugin being
     * removed, which is the same entry — so a plugin whose entry has changed its list since install leaves
     * behind whatever it no longer names. That is the honest outcome for a record kept by the plugin rather
     * than by the project, and it is the same shape as a version the user pinned by hand.
     *
     * <p><b>A copy still declared under BotMaker's other groupId keeps them</b> (2026-10-07). Removing the
     * {@code com.github.LiQiyeDev} half of an SDK declared twice took javalin and zxing with it, and the
     * {@code com.github.BotMakerDev} SDK left behind needed both: the Pilot failed on
     * {@code NoClassDefFoundError: io/javalin/websocket/WsContext}.
     */
    public static void removePlugin(Path projectDir, String groupId, String artifactId,
                                    List<UserLibrary> editorDependencies) throws IOException {
        new PomEdits(projectDir).removePlugin(groupId, artifactId, editorDependencies);
    }

    /**
     * The BotMaker SDK version {@code projectDir/pom.xml} declares — <b>empty when it declares none</b>.
     *
     * <p>It answered {@link #SDK_FALLBACK_VERSION} for a pom naming no SDK until 2026-09-04, which was
     * harmless while every pom Studio wrote named one and became a lie the moment a blank project could
     * exist. The readers of this answer resolve jars with it, offer upgrades against it and print it in an
     * about box; every one of them would have been describing a dependency the project does not have.
     *
     * <p><b>Empty is not an error.</b> A project with no SDK is an ordinary project — it is what New Project
     * now creates — so each caller degrades rather than refusing: no docs, no surface index, no upgrade
     * offered, no version reported.
     */
    public static Optional<String> readSdkVersion(Path projectDir) {
        return readDependencyVersion(projectDir, SDK_GROUP_ID, SDK_ARTIFACT_ID);
    }

    /**
     * The version {@code projectDir/pom.xml} declares for one coordinate — <b>empty when it declares none</b>.
     *
     * <p>The general form of {@link #readSdkVersion}, and the one the project upgrade window asks per row:
     * a plugin is installed because its coordinate is in the pom, so this is the same question for every
     * plugin, plugin #1 included. Empty is not an error anywhere here — a project that does not declare a
     * plugin is an ordinary project, and every caller degrades rather than refusing.
     *
     * <p>A pin written as {@code ${property}} answers the property's value: the template pins its SDK as
     * {@code ${botmaker.sdk.version}} (2026-09-24), and a resolver handed the placeholder text resolves nothing.
     */
    public static Optional<String> readDependencyVersion(Path projectDir, String groupId, String artifactId) {
        return new PomEdits(projectDir).readDependencyVersion(groupId, artifactId);
    }

    // resolveSdkSourcesJar stood here until 2026-09-28. The API docs read every bound plugin's sources jar
    // now (services/ApiDocsService), through resolveArtifact below.

    /**
     * Resolves the SDK's own (classifier-less) jar for an <em>arbitrary</em> version — not necessarily the
     * one this project pins. That is what {@code services/upgrade/PluginUpgradeService} compares against: answering "what breaks
     * if I move to v2.0.0" means reading v2.0.0's bytecode, which nothing else in Studio ever needs.
     *
     * <p>The project's own pom is still consulted, for its {@code <repositories>} — JitPack is declared
     * there, so a version that has never been resolved on this machine downloads on demand.
     */
    public static Optional<Path> resolveSdkJar(Path projectDir, String version) {
        return resolveSdkArtifact(projectDir, version, "");
    }

    /**
     * The same, for a caller that has <b>no project</b> — {@code ProjectCreator}, which must know what the
     * chosen SDK contains <em>before</em> a pom exists to read repositories from.
     *
     * <p>Resolving against an empty model is not a compromise here:
     * {@link MavenResolver#buildRemoteRepositories} starts from {@link PomDefaults#DEFAULT_REPOSITORIES}, and those
     * are exactly the repositories {@link #blankPomXml} is about to declare. The only thing a project pom adds is a
     * repository the <em>user</em> put there, which by definition a project that does not exist yet has none of.
     */
    public static Optional<Path> resolveSdkJar(String version) {
        return MavenResolver.resolveArtifact(new Model(), SDK_GROUP_ID, SDK_ARTIFACT_ID, "", version);
    }

    /**
     * Resolves one BotMaker SDK artifact from the local repo, downloading it via the project pom's
     * repositories if absent. {@code classifier} is {@code ""} for the jar itself, {@code "sources"} for the
     * sources jar. Best-effort: empty when the pom is missing, the artifact cannot be resolved, or offline.
     * May block on the network — call off the FX thread.
     */
    public static Optional<Path> resolveSdkArtifact(Path projectDir, String version, String classifier) {
        return resolveArtifact(projectDir, SDK_GROUP_ID, SDK_ARTIFACT_ID, classifier, version);
    }

    /**
     * The same for <b>any</b> coordinate — what the project upgrade window resolves a plugin's two jars with.
     *
     * <p>The SDK-named entry points above are two-line delegations to this since 2026-09-15. They stay
     * because their callers are about creating and repairing a project that names the SDK, not about an
     * upgrade, and have no coordinate to hand.
     *
     * <p>The project's own pom is read for its {@code <repositories>} — JitPack is declared there — so a
     * version that has never been resolved on this machine downloads on demand. Best-effort throughout:
     * empty for a missing pom, a blank version, an unresolvable artifact or no network. May block — call it
     * off the FX thread.
     */
    public static Optional<Path> resolveArtifact(Path projectDir, String groupId, String artifactId,
                                                 String classifier, String version) {
        Model model = PomModel.readModel(projectDir);
        if (model == null) return Optional.empty();
        return MavenResolver.resolveArtifact(model, groupId, artifactId, classifier, version);
    }

    /**
     * Replaces the user-added libraries with {@code libs}, leaving the SDK version unchanged — and adding
     * none to a project that declares no SDK.
     *
     * @see #writeUserLibraries(Path, List, String)
     */
    public static void writeUserLibraries(Path projectDir, List<UserLibrary> libs) throws IOException {
        writeUserLibraries(projectDir, libs, readSdkVersion(projectDir).orElse(""));
    }

    /**
     * Replaces the user-added libraries in {@code projectDir/pom.xml} with {@code libs} and pins the
     * BotMaker SDK to {@code sdkVersion}, leaving the other built-in dependencies, repositories and
     * properties untouched. The pom is read, mutated and written back in place (no regeneration).
     *
     * <p><b>A blank {@code sdkVersion} pins nothing, and does not add the SDK.</b>
     * {@link PomEdits#setManagedDependencyVersion} only ever edits a dependency that is already declared, so a
     * blank project keeps naming no plugin however often its libraries are edited — which is what makes
     * {@code LibraryService} usable on one at all.
     */
    public static void writeUserLibraries(Path projectDir, List<UserLibrary> libs, String sdkVersion)
            throws IOException {
        new PomEdits(projectDir).writeUserLibraries(libs, sdkVersion);
    }

    /**
     * Re-pins every coordinate in {@code versionsByCoordinate} — {@code "groupId:artifactId"} to the version
     * it should now declare — in one read and one write of the pom.
     *
     * <p><b>One write, several rows.</b> That is the whole reason it takes a map: the project upgrade window
     * moves several plugins in one pass, and a pom rewritten once per row would leave the project in a state
     * no row describes if the second write failed. Everything else about the pom — the other dependencies,
     * the repositories, the properties — is untouched, exactly as {@link #installPlugin} leaves them.
     *
     * <p><b>It only ever re-versions what the pom already declares</b>, the same rule
     * {@link PomEdits#setManagedDependencyVersion} has always had. A coordinate that is not there is silently
     * skipped rather than added: the window's rows are built from the pom, so a missing one means the pom
     * moved underneath the window, and quietly acquiring a dependency is the wrong answer to that.
     *
     * <p>A blank version is skipped for the same reason a blank {@code sdkVersion} pins nothing.
     */
    public static void setDependencyVersions(Path projectDir, Map<String, String> versionsByCoordinate)
            throws IOException {
        new PomEdits(projectDir).setDependencyVersions(versionsByCoordinate);
    }
}
