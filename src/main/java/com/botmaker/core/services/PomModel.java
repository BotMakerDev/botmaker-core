// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.services;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** A project's {@code pom.xml} read and written as a Maven {@link Model}, and what one of its dependencies says. */
final class PomModel {

    private PomModel() {}

    static Model readModel(Path projectDir) {
        Path pomPath = projectDir.resolve("pom.xml");
        if (!Files.exists(pomPath)) return null;
        try (InputStream in = Files.newInputStream(pomPath)) {
            return new MavenXpp3Reader().read(in);
        } catch (Exception e) {
            System.err.println("Failed to read pom.xml: " + e.getMessage());
            return null;
        }
    }

    static Model requireModel(Path projectDir) throws IOException {
        Model model = readModel(projectDir);
        if (model == null) {
            throw new IOException("No pom.xml found at " + projectDir.resolve("pom.xml"));
        }
        return model;
    }

    static void writeModel(Path projectDir, Model model) throws IOException {
        try (OutputStream out = Files.newOutputStream(projectDir.resolve("pom.xml"))) {
            new MavenXpp3Writer().write(out, model);
        }
    }

    /** {@code d}'s version, with a whole-{@code ${property}} pin read from the pom's properties. */
    static String versionOf(Model model, Dependency d) {
        String v = d.getVersion();
        if (v == null) return null;
        return propertyOf(v).map(model.getProperties()::getProperty).orElse(v);
    }

    /** {@code name} for a version written exactly {@code ${name}}, otherwise empty. */
    static Optional<String> propertyOf(String version) {
        String v = version.strip();
        return v.startsWith("${") && v.endsWith("}") && v.length() > 3
                ? Optional.of(v.substring(2, v.length() - 1))
                : Optional.empty();
    }

    static String coordinate(Dependency d) {
        return d.getGroupId() + ":" + d.getArtifactId();
    }

    static boolean sameArtifact(Dependency d, String groupId, String artifactId) {
        return groupId.equals(d.getGroupId()) && artifactId.equals(d.getArtifactId());
    }
}
