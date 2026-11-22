// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.services;

import com.botmaker.core.plugin.DuplicatePlugins;
import com.botmaker.core.project.UserLibrary;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What one project's pom declares, and each change made to it: read, mutated and written back in place. Each public
 * method is documented on {@link MavenService}.
 */
final class PomEdits {

    private final Path projectDir;

    PomEdits(Path projectDir) {
        this.projectDir = projectDir;
    }

    List<UserLibrary> readUserLibraries() {
        Model model = PomModel.readModel(projectDir);
        if (model == null) return List.of();
        return model.getDependencies().stream()
                .filter(d -> !PomDefaults.isDefaultDependency(d))
                .map(d -> new UserLibrary(d.getGroupId(), d.getArtifactId(), d.getVersion()))
                .collect(Collectors.toList());
    }

    List<UserLibrary> readDeclaredLibraries() {
        Model model = PomModel.readModel(projectDir);
        if (model == null) return List.of();
        return model.getDependencies().stream()
                .map(d -> new UserLibrary(d.getGroupId(), d.getArtifactId(), d.getVersion()))
                .collect(Collectors.toList());
    }

    Map<String, String> readProperties() {
        Model model = PomModel.readModel(projectDir);
        if (model == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        model.getProperties().forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        return out;
    }

    Optional<String> readDependencyVersion(String groupId, String artifactId) {
        Model model = PomModel.readModel(projectDir);
        if (model == null) return Optional.empty();
        return model.getDependencies().stream()
                .filter(d -> groupId.equals(d.getGroupId()) && artifactId.equals(d.getArtifactId()))
                .map(Dependency::getVersion)
                .filter(v -> v != null && !v.isBlank())
                .map(v -> PomModel.propertyOf(v).map(model.getProperties()::getProperty).orElse(v))
                .findFirst();
    }

    void installPlugin(UserLibrary plugin, List<UserLibrary> editorDependencies) throws IOException {
        Model model = PomModel.requireModel(projectDir);
        model.getDependencies().removeIf(d -> PomModel.sameArtifact(d, plugin.groupId(), plugin.artifactId()));
        Dependency dep = new Dependency();
        dep.setGroupId(plugin.groupId());
        dep.setArtifactId(plugin.artifactId());
        dep.setVersion(plugin.version());
        model.getDependencies().add(dep);
        declareProvided(model, editorDependencies);
        PomModel.writeModel(projectDir, model);
    }

    List<UserLibrary> declareCompanions(List<UserLibrary> companions) throws IOException {
        Model model = PomModel.requireModel(projectDir);
        List<UserLibrary> added = declareProvided(model, companions);
        if (!added.isEmpty()) PomModel.writeModel(projectDir, model);
        return added;
    }

    private static List<UserLibrary> declareProvided(Model model, List<UserLibrary> companions) {
        List<UserLibrary> added = new ArrayList<>();
        for (UserLibrary companion : companions) {
            boolean present = model.getDependencies().stream()
                    .anyMatch(d -> PomModel.sameArtifact(d, companion.groupId(), companion.artifactId()));
            if (present) continue;
            Dependency dep = new Dependency();
            dep.setGroupId(companion.groupId());
            dep.setArtifactId(companion.artifactId());
            dep.setVersion(companion.version());
            dep.setScope("provided");
            model.getDependencies().add(dep);
            added.add(companion);
        }
        return added;
    }

    boolean declareIfAbsent(UserLibrary library) throws IOException {
        Model model = PomModel.requireModel(projectDir);
        boolean present = model.getDependencies().stream()
                .anyMatch(d -> PomModel.sameArtifact(d, library.groupId(), library.artifactId()));
        if (present) return false;
        Dependency dep = new Dependency();
        dep.setGroupId(library.groupId());
        dep.setArtifactId(library.artifactId());
        dep.setVersion(library.version());
        model.getDependencies().add(dep);
        PomModel.writeModel(projectDir, model);
        return true;
    }

    void removePlugin(String groupId, String artifactId, List<UserLibrary> editorDependencies) throws IOException {
        Model model = PomModel.requireModel(projectDir);
        model.getDependencies().removeIf(d -> PomModel.sameArtifact(d, groupId, artifactId));
        boolean stillDeclared = model.getDependencies().stream().anyMatch(d -> DuplicatePlugins.sameArtifact(
                d.getGroupId(), d.getArtifactId(), groupId, artifactId));
        if (stillDeclared) {
            PomModel.writeModel(projectDir, model);
            return;
        }
        for (UserLibrary companion : editorDependencies) {
            model.getDependencies()
                    .removeIf(d -> PomModel.sameArtifact(d, companion.groupId(), companion.artifactId()));
        }
        PomModel.writeModel(projectDir, model);
    }

    void writeUserLibraries(List<UserLibrary> libs, String sdkVersion) throws IOException {
        Model model = PomModel.requireModel(projectDir);

        // Keep the built-in deps, drop the previous user deps, then append the new ones.
        List<Dependency> kept = model.getDependencies().stream()
                .filter(PomDefaults::isDefaultDependency)
                .collect(Collectors.toList());
        for (UserLibrary lib : libs) {
            Dependency dep = new Dependency();
            dep.setGroupId(lib.groupId());
            dep.setArtifactId(lib.artifactId());
            dep.setVersion(lib.version());
            kept.add(dep);
        }
        model.setDependencies(kept);

        if (sdkVersion != null && !sdkVersion.isBlank()) {
            setManagedDependencyVersion(model, MavenService.SDK_GROUP_ID, MavenService.SDK_ARTIFACT_ID,
                    sdkVersion.trim());
        }

        PomModel.writeModel(projectDir, model);
    }

    void setDependencyVersions(Map<String, String> versionsByCoordinate) throws IOException {
        Model model = PomModel.requireModel(projectDir);
        versionsByCoordinate.forEach((coordinate, version) -> {
            if (version == null || version.isBlank()) return;
            int colon = coordinate.indexOf(':');
            if (colon <= 0 || colon == coordinate.length() - 1) return;
            setManagedDependencyVersion(model, coordinate.substring(0, colon),
                    coordinate.substring(colon + 1), version.trim());
        });
        PomModel.writeModel(projectDir, model);
    }

    /**
     * Sets the version of the matching dependency already present in the model (no-op if absent). A pin
     * written as {@code ${property}} the pom declares moves the property, so the pom keeps its spelling.
     */
    private static void setManagedDependencyVersion(Model model, String groupId, String artifactId,
                                                    String version) {
        for (Dependency d : model.getDependencies()) {
            if (groupId.equals(d.getGroupId()) && artifactId.equals(d.getArtifactId())) {
                Optional<String> property = d.getVersion() == null ? Optional.empty()
                        : PomModel.propertyOf(d.getVersion()).filter(model.getProperties()::containsKey);
                if (property.isPresent()) model.getProperties().setProperty(property.get(), version);
                else d.setVersion(version);
            }
        }
    }
}
