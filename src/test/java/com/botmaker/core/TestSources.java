package com.botmaker.core;

import com.botmaker.core.plugin.PluginHost;
import com.botmaker.plugin.api.meta.Refactor;
import org.junit.jupiter.api.Assumptions;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * What the core's tests parse and resolve against: this module's source root and the test's own classpath. Came with the
 * tests that moved from Studio on 2026-11-05 (plan phase 6e), where its {@code TestSupport} held the same lines.
 */
public final class TestSources {

    public static final Path SOURCE_ROOT = Paths.get("src", "main", "java").toAbsolutePath();

    private TestSources() {
    }

    public static List<String> runtimeClassPath() {
        return Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator));
    }

    /**
     * The contract jar (or class directory) this test runs against, as one resolved-classpath entry — what a bot on the
     * SDK has, and what a host checks before it writes {@code @Refactor}.
     */
    public static String contractOnClasspath() {
        try {
            return Path.of(Refactor.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Skips the calling test unless the SDK plugin is on this classpath, known by its pom's artifactId. */
    public static void assumeSdkPluginBound() {
        boolean bound = PluginHost.plugins().stream()
                .anyMatch(p -> "botmaker-sdk".equals(p.coordinate().artifactId()));
        Assumptions.assumeTrue(bound, "the SDK plugin is not on this classpath");
    }
}
