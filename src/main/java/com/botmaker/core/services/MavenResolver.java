// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.services;

import com.botmaker.cli.bmk.BotFile;
import com.botmaker.cli.bmk.BotFiles;
import com.botmaker.core.services.MavenService.Fetched;
import com.botmaker.core.services.MavenService.Resolution;
import com.botmaker.core.sharing.UnpackedBots;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Repository;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.transfer.AbstractTransferListener;
import org.eclipse.aether.transfer.TransferEvent;
import org.eclipse.aether.transfer.TransferResource;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.ScopeDependencyFilter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Jars, resolved in-process with Maven Resolver (Aether) from {@code ~/.m2} and the pom's repositories. Each public
 * method is documented on {@link MavenService}.
 */
final class MavenResolver {

    private MavenResolver() {}

    static Resolution resolve(Path projectDir, ProgressReporter progress) {
        // A bot opened from a .bmk runs with the jars its bot.json lists, never its pom's: the pom's JitPack
        // dependencies may be gone, which is why the file packs them (2026-10-25).
        Optional<Resolution> unpacked = UnpackedBots.classpath(projectDir, progress);
        if (unpacked.isPresent()) return unpacked.get();
        Collected collected = collect(projectDir, progress);
        List<String> jars = new ArrayList<>();
        collectJars(collected.results(), jars);
        return new Resolution(jars, collected.problems());
    }

    static List<BotFile.Jar> resolvedJars(Path projectDir) throws IOException {
        Collected collected = collect(projectDir, ProgressReporter.NONE);
        if (!collected.problems().isEmpty()) {
            throw new IOException("Some of the bot's libraries did not resolve: "
                    + String.join("; ", collected.problems()));
        }
        Map<String, BotFile.Jar> jars = new TreeMap<>();
        for (ArtifactResult result : collected.results()) {
            Artifact a = result.getArtifact();
            if (a == null || a.getPath() == null || !"jar".equals(a.getExtension())) continue;
            if (BotFiles.isNative(a.getClassifier())) continue;
            String coordinate = a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getBaseVersion()
                    + (a.getClassifier().isEmpty() ? "" : ":" + a.getClassifier());
            jars.putIfAbsent(coordinate, new BotFile.Jar(coordinate, a.getPath()));
        }
        return List.copyOf(jars.values());
    }

    static Fetched fetch(List<String> coordinates, ProgressReporter progress) {
        RepositorySystem system = new RepositorySystemSupplier().get();
        DefaultRepositorySystemSession session = newSession(system);
        session.setTransferListener(new AbstractTransferListener() {
            @Override
            public void transferInitiated(TransferEvent event) {
                progress.report(-1, "Downloading " + fileName(event.getResource()));
            }
        });
        List<RemoteRepository> repositories = buildRemoteRepositories(new Model());
        List<ArtifactRequest> requests = new ArrayList<>();
        for (String coordinate : coordinates) {
            String[] p = coordinate.split(":");
            if (p.length < 3) continue;
            requests.add(new ArtifactRequest(new DefaultArtifact(p[0], p[1], p.length > 3 ? p[3] : "", "jar", p[2]),
                    repositories, null).setRequestContext(coordinate));
        }
        List<ArtifactResult> results;
        try {
            results = system.resolveArtifacts(session, requests);
        } catch (ArtifactResolutionException e) {
            results = e.getResults();
        }
        Map<String, Path> files = new LinkedHashMap<>();
        for (ArtifactResult result : results) {
            if (result.isResolved() && result.getArtifact().getPath() != null) {
                files.put(result.getRequest().getRequestContext(), result.getArtifact().getPath());
            }
        }
        List<String> missing = coordinates.stream().filter(c -> !files.containsKey(c)).toList();
        return new Fetched(files, missing);
    }

    static Optional<Path> resolveArtifact(Model model, String groupId, String artifactId,
                                          String classifier, String version) {
        if (version == null || version.isBlank()) {
            return Optional.empty();
        }
        // A pin read off the pom as written (readDeclaredLibraries) is still ${botmaker.sdk.version} text here;
        // the pom it came from says what it means (2026-10-01: the gamebot's SDK jar never resolved).
        String pinned = PomModel.propertyOf(version).map(model.getProperties()::getProperty).orElse(version);
        Artifact artifact = new DefaultArtifact(groupId, artifactId, classifier, "jar", pinned.trim());

        RepositorySystem system = new RepositorySystemSupplier().get();
        DefaultRepositorySystemSession session = newSession(system);

        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(artifact);
        request.setRepositories(buildRemoteRepositories(model));
        try {
            ArtifactResult result = system.resolveArtifact(session, request);
            return Optional.ofNullable(result.getArtifact().getPath());
        } catch (ArtifactResolutionException e) {
            System.err.println("Could not resolve artifact " + artifact + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /** The pom's dependencies resolved, with what failed. */
    private record Collected(List<ArtifactResult> results, List<String> problems) {}

    private static Collected collect(Path projectDir, ProgressReporter progress) {
        Path pomPath = projectDir.resolve("pom.xml");
        if (!Files.exists(pomPath)) {
            System.err.println("No pom.xml found at " + pomPath);
            return new Collected(List.of(), List.of("pom.xml — not found"));
        }

        Model model;
        try (InputStream in = Files.newInputStream(pomPath)) {
            model = new MavenXpp3Reader().read(in);
        } catch (Exception e) {
            System.err.println("Failed to read pom.xml: " + e.getMessage());
            return new Collected(List.of(), List.of("pom.xml — " + e.getMessage()));
        }

        RepositorySystem system = new RepositorySystemSupplier().get();
        DefaultRepositorySystemSession session = newSession(system);
        DownloadAggregator downloads = new DownloadAggregator();
        session.setTransferListener(new AbstractTransferListener() {
            @Override
            public void transferInitiated(TransferEvent event) {
                progress.report(downloads.fraction(), "Downloading " + fileName(event.getResource()));
            }
            @Override
            public void transferProgressed(TransferEvent event) {
                downloads.progressed(event.getResource(), event.getTransferredBytes());
                progress.report(downloads.fraction(), "Downloading " + fileName(event.getResource()));
            }
            @Override
            public void transferSucceeded(TransferEvent event) {
                downloads.finished(event.getResource(), event.getTransferredBytes());
                progress.report(downloads.fraction(), "Downloaded " + fileName(event.getResource()));
            }
            @Override
            public void transferFailed(TransferEvent event) {
                downloads.finished(event.getResource(), event.getTransferredBytes());
            }
        });

        List<RemoteRepository> remoteRepos = buildRemoteRepositories(model);

        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRepositories(remoteRepos);
        // Test dependencies are collected, then left out of what is resolved: they take part in choosing versions,
        // as in Maven. Left out of the graph, a library's older JUnit won over the pom's own and a bot's .bmk from
        // Studio differed from the one `mvn dependency:list` makes, which the catalog rebuilds (2026-10-25).
        for (Dependency d : model.getDependencies()) {
            if ("sources".equals(d.getClassifier())) continue;
            String classifier = d.getClassifier() == null ? "" : d.getClassifier();
            // A template pins its plugin as ${botmaker.sdk.version}; handed the placeholder, the resolver looked
            // for a version of that name and the project bound no plugin at all (until 2026-10-01).
            Artifact artifact = new DefaultArtifact(
                    d.getGroupId(), d.getArtifactId(), classifier, "jar", PomModel.versionOf(model, d));
            String scope = d.getScope() == null ? "compile" : d.getScope();
            collectRequest.addDependency(new org.eclipse.aether.graph.Dependency(artifact, scope, false,
                    exclusionsOf(d)));
        }
        // The pom's own <dependencyManagement> pins transitive versions as Maven does. An imported BOM is not read.
        if (model.getDependencyManagement() != null) {
            for (Dependency d : model.getDependencyManagement().getDependencies()) {
                if ("import".equals(d.getScope())) continue;
                String classifier = d.getClassifier() == null ? "" : d.getClassifier();
                Artifact artifact = new DefaultArtifact(
                        d.getGroupId(), d.getArtifactId(), classifier, "jar", PomModel.versionOf(model, d));
                collectRequest.addManagedDependency(new org.eclipse.aether.graph.Dependency(artifact,
                        d.getScope() == null ? "" : d.getScope(), null, exclusionsOf(d)));
            }
        }

        DependencyRequest dependencyRequest = new DependencyRequest(collectRequest,
                new ScopeDependencyFilter(null, List.of(JavaScopes.TEST)));
        List<ArtifactResult> results = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        try {
            DependencyResult result = system.resolveDependencies(session, dependencyRequest);
            results.addAll(result.getArtifactResults());
        } catch (DependencyResolutionException e) {
            System.err.println("Some dependencies failed to resolve: " + e.getMessage());
            if (e.getResult() != null && e.getResult().getArtifactResults() != null) {
                results.addAll(e.getResult().getArtifactResults());
                collectProblems(e.getResult().getArtifactResults(), problems);
            }
            // A collection failure (a pom that is not there at all) has no artifact results to name.
            if (problems.isEmpty()) problems.add(firstLine(e.getMessage()));
        }
        return new Collected(results, problems);
    }

    /** A resolver session over {@code ~/.m2}, the one every resolution here starts from. */
    static DefaultRepositorySystemSession newSession(RepositorySystem system) {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        Path localRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
        session.setLocalRepositoryManager(
                system.newLocalRepositoryManager(session, new LocalRepository(localRepo)));
        // Expose the JVM's system properties (notably java.version) to the model builder so POMs whose
        // effective model depends on JDK-activated profiles resolve correctly. Without this, bytedeco's
        // javacpp-presets parent fails ("Failed to determine Java version for profile doclint-java8-disable"),
        // the descriptor read is silently ignored, and the whole opencv subtree — including the opencv main
        // jar that carries org.opencv.core.Mat — is dropped from the bot's runtime classpath.
        session.setSystemProperties(System.getProperties());
        return session;
    }

    private static void collectProblems(List<ArtifactResult> results, List<String> out) {
        if (results == null) return;
        for (ArtifactResult ar : results) {
            if (ar.isResolved() || ar.getRequest() == null || ar.getRequest().getArtifact() == null) continue;
            Artifact a = ar.getRequest().getArtifact();
            String reason = ar.getExceptions().isEmpty() ? "not found"
                    : firstLine(ar.getExceptions().getFirst().getMessage());
            out.add(a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion() + " — " + reason);
        }
    }

    private static String firstLine(String message) {
        if (message == null || message.isBlank()) return "could not be resolved";
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    /**
     * Aggregates bytes across all concurrent Aether transfers into a single overall fraction. New artifacts
     * are discovered mid-resolve, so the denominator grows as downloads start — the fraction is honest (real
     * bytes) but may briefly step back when a new large jar appears. Thread-safe: Aether fires transfer
     * callbacks from worker threads.
     */
    private static final class DownloadAggregator {
        /** resource identity → {transferredBytes, contentLength (-1 if unknown)} for in-flight transfers. */
        private final Map<TransferResource, long[]> active = new IdentityHashMap<>();
        private long completedBytes = 0;

        synchronized void progressed(TransferResource resource, long transferred) {
            active.put(resource, new long[]{transferred, resource.getContentLength()});
        }

        synchronized void finished(TransferResource resource, long transferred) {
            long[] prev = active.remove(resource);
            long bytes = transferred > 0 ? transferred : (prev != null ? prev[0] : 0);
            completedBytes += Math.max(0, bytes);
        }

        /** Overall completed-fraction in [0,1], or -1 when nothing with a known size is in flight yet. */
        synchronized double fraction() {
            long transferred = completedBytes;
            long total = completedBytes;
            for (long[] v : active.values()) {
                transferred += v[0];
                total += Math.max(v[1], v[0]); // unknown length → count its own transferred bytes as the total
            }
            return total > 0 ? Math.min(1.0, (double) transferred / total) : -1;
        }
    }

    /** The trailing file name of a transfer resource (e.g. {@code opencv-4.9.0.jar}), for progress text. */
    private static String fileName(TransferResource resource) {
        String name = resource.getResourceName();
        if (name == null) return "";
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    private static void collectJars(List<ArtifactResult> results, List<String> out) {
        if (results == null) return;
        for (ArtifactResult ar : results) {
            if (ar.getArtifact() != null && ar.getArtifact().getPath() != null) {
                out.add(ar.getArtifact().getPath().toAbsolutePath().toString());
            }
        }
    }

    static List<RemoteRepository> buildRemoteRepositories(Model model) {
        Map<String, String> repos = new LinkedHashMap<>(PomDefaults.DEFAULT_REPOSITORIES);
        for (Repository r : model.getRepositories()) {
            if (r.getUrl() != null) repos.put(r.getId(), r.getUrl());
        }
        // Disable snapshot fetching on every remote: in this project SNAPSHOT coordinates are always
        // local-only dev builds (botmaker-sdk / botmaker-shared at their main -SNAPSHOT, installed to ~/.m2
        // by the umbrella reactor). Letting a remote (notably jitpack) answer for a SNAPSHOT could shadow the
        // freshly reinstalled local jar. Releases are non-SNAPSHOT, so user libraries are unaffected.
        RepositoryPolicy noSnapshots = new RepositoryPolicy(
                false, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_WARN);
        List<RemoteRepository> result = new ArrayList<>();
        repos.forEach((id, url) ->
                result.add(new RemoteRepository.Builder(id, "default", url)
                        .setSnapshotPolicy(noSnapshots)
                        .build()));
        return result;
    }

    /** {@code d}'s {@code <exclusions>}, as the resolver applies them below it. */
    private static List<Exclusion> exclusionsOf(Dependency d) {
        return d.getExclusions().stream().map(x -> new Exclusion(x.getGroupId(), x.getArtifactId(), "*", "*"))
                .toList();
    }
}
