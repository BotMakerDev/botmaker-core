// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.services;

import com.botmaker.core.project.UserLibrary;
import com.botmaker.core.services.MavenService.Shadowed;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.InvalidVersionSpecificationException;
import org.eclipse.aether.version.Version;
import org.eclipse.aether.version.VersionScheme;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pom's direct entries a declared plugin already brings, found in each plugin's own tree and removed. Each public
 * method is documented on {@link MavenService}.
 */
final class PluginShadows {

    private final Path projectDir;

    PluginShadows(Path projectDir) {
        this.projectDir = projectDir;
    }

    List<Shadowed> dropShadowedPlugins() throws IOException {
        Model model = PomModel.requireModel(projectDir);
        List<Dependency> plugins = new ArrayList<>();
        for (Dependency d : model.getDependencies()) {
            String scope = d.getScope() == null ? "compile" : d.getScope();
            if (!"compile".equals(scope) && !"runtime".equals(scope)) continue;
            // A template pins its plugin as ${botmaker.basics.version}; a resolver handed the placeholder
            // resolves nothing, and the plugin would never be seen.
            MavenResolver.resolveArtifact(model, d.getGroupId(), d.getArtifactId(), "", PomModel.versionOf(model, d))
                    .filter(MavenService::declaresPlugin).ifPresent(jar -> plugins.add(d));
        }
        if (plugins.size() < 2) return List.of();

        Map<String, Set<String>> brings = new LinkedHashMap<>();
        treesBelow(model, plugins).forEach((plugin, tree) -> brings.put(plugin, tree.keySet()));

        List<Shadowed> removed = shadowed(brings);
        if (removed.isEmpty()) return removed;
        Set<String> gone = new HashSet<>();
        for (Shadowed s : removed) gone.add(s.coordinate());
        model.getDependencies().removeIf(d -> gone.contains(PomModel.coordinate(d)));
        PomModel.writeModel(projectDir, model);
        return removed;
    }

    boolean dropRedundantContract(UserLibrary contract) throws IOException {
        Model model = PomModel.requireModel(projectDir);
        Dependency direct = model.getDependencies().stream()
                .filter(d -> PomModel.sameArtifact(d, contract.groupId(), contract.artifactId()))
                .findFirst().orElse(null);
        if (direct == null) return false;
        String pinned = PomModel.versionOf(model, direct);
        if (pinned == null) return false;

        List<Dependency> plugins = new ArrayList<>();
        for (Dependency d : model.getDependencies()) {
            String scope = d.getScope() == null ? "compile" : d.getScope();
            if (!"compile".equals(scope) && !"runtime".equals(scope)) continue;
            if (d == direct || excludes(d, contract)) continue;
            MavenResolver.resolveArtifact(model, d.getGroupId(), d.getArtifactId(), "", PomModel.versionOf(model, d))
                    .filter(MavenService::declaresPlugin).ifPresent(jar -> plugins.add(d));
        }
        if (plugins.isEmpty()) return false;

        String wanted = contract.groupId() + ":" + contract.artifactId();
        boolean brought = treesBelow(model, plugins).values().stream()
                .flatMap(tree -> tree.entrySet().stream())
                .anyMatch(e -> e.getKey().equals(wanted) && notOlder(e.getValue(), pinned));
        if (!brought) return false;
        model.getDependencies().remove(direct);
        PomModel.writeModel(projectDir, model);
        return true;
    }

    /**
     * Each of {@code declared}'s {@code groupId:artifactId} mapped to every coordinate in its own tree, collected
     * one at a time so the pom's own conflict resolution cannot hide an edge. A tree that cannot be collected
     * brings what could be read of it. May block on the network.
     */
    private static Map<String, Map<String, String>> treesBelow(Model model, List<Dependency> declared) {
        RepositorySystem system = new RepositorySystemSupplier().get();
        DefaultRepositorySystemSession session = MavenResolver.newSession(system);
        List<RemoteRepository> repositories = MavenResolver.buildRemoteRepositories(model);
        Map<String, Map<String, String>> brings = new LinkedHashMap<>();
        for (Dependency each : declared) {
            Map<String, String> below = new HashMap<>();
            CollectRequest request = new CollectRequest();
            request.setRoot(new org.eclipse.aether.graph.Dependency(new DefaultArtifact(
                    each.getGroupId(), each.getArtifactId(), "", "jar", PomModel.versionOf(model, each)), "compile"));
            request.setRepositories(repositories);
            try {
                collectBelow(system.collectDependencies(session, request).getRoot(), below, true);
            } catch (DependencyCollectionException e) {
                if (e.getResult() != null && e.getResult().getRoot() != null) {
                    collectBelow(e.getResult().getRoot(), below, true);
                }
            }
            brings.put(PomModel.coordinate(each), below);
        }
        return brings;
    }

    /** Every {@code groupId:artifactId} under {@code node}, each with the version its tree resolved it at. */
    private static void collectBelow(DependencyNode node, Map<String, String> out, boolean root) {
        if (node == null) return;
        if (!root && node.getArtifact() != null) {
            out.putIfAbsent(node.getArtifact().getGroupId() + ":" + node.getArtifact().getArtifactId(),
                    node.getArtifact().getVersion());
        }
        for (DependencyNode child : node.getChildren()) collectBelow(child, out, false);
    }

    private static boolean excludes(Dependency d, UserLibrary library) {
        return d.getExclusions().stream().anyMatch(x ->
                ("*".equals(x.getGroupId()) || library.groupId().equals(x.getGroupId()))
                        && ("*".equals(x.getArtifactId()) || library.artifactId().equals(x.getArtifactId())));
    }

    /** Whether {@code version} is {@code pinned} or newer, a leading {@code v} ignored; false when unreadable. */
    static boolean notOlder(String version, String pinned) {
        if (version == null || pinned == null) return false;
        try {
            VersionScheme scheme = new GenericVersionScheme();
            Version brought = scheme.parseVersion(version.replaceFirst("^v", ""));
            Version ours = scheme.parseVersion(pinned.replaceFirst("^v", ""));
            return brought.compareTo(ours) >= 0;
        } catch (InvalidVersionSpecificationException e) {
            return false;
        }
    }

    /**
     * Which of {@code brings}' keys another key brings: {@code brings} maps each declared plugin's
     * {@code groupId:artifactId} to every coordinate in its own tree. Never removes both of two plugins that
     * claim to bring each other, which no real pom can produce but a broken one might. Pure.
     */
    static List<Shadowed> shadowed(Map<String, Set<String>> brings) {
        List<Shadowed> out = new ArrayList<>();
        Set<String> removed = new HashSet<>();
        for (String plugin : brings.keySet()) {
            for (Map.Entry<String, Set<String>> other : brings.entrySet()) {
                if (other.getKey().equals(plugin) || removed.contains(other.getKey())) continue;
                if (other.getValue().contains(plugin)) {
                    out.add(new Shadowed(plugin, other.getKey()));
                    removed.add(plugin);
                    break;
                }
            }
        }
        return out;
    }
}
