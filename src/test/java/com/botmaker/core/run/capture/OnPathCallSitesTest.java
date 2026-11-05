package com.botmaker.core.run.capture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Studio's SV4 guard for the two call sites that moved here in plan phase 7a ({@code OnPathParityTest} in Studio keeps
 * the rest): no source asks whether a program is installed by spawning {@code which}, and every name handed to
 * {@code Executables.onPath} is a bare program name, since a path there quietly answers "not installed".
 */
class OnPathCallSitesTest {

    private static final List<Path> CALL_SITES = List.of(
            Path.of("src/main/java/com/botmaker/core/run/capture/DesktopGrab.java"),
            Path.of("src/main/java/com/botmaker/core/run/capture/SessionEnvironment.java"));

    @Test
    void noCoreSourceAsksTheQuestionBySpawningWhich() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            for (Path source : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (Files.readString(source).contains("ProcessBuilder(\"which\"")) offenders.add(source.toString());
            }
        }
        assertTrue(offenders.isEmpty(), "these spawn `which` instead of calling Executables.onPath: " + offenders);
    }

    @Test
    void everyCallSitePassesABareNameAndNotAPath() throws IOException {
        List<String> pathShaped = new ArrayList<>();
        for (Path source : CALL_SITES) {
            for (String literal : literalsReachingOnPath(Files.readString(source))) {
                if (literal.indexOf('/') >= 0 || literal.indexOf('\\') >= 0) {
                    pathShaped.add(source.getFileName() + ": " + literal);
                }
            }
        }
        assertTrue(pathShaped.isEmpty(),
                "these arguments are paths, so Executables.onPath would answer 'no' for them: " + pathShaped);
    }

    /**
     * The literals passed to {@code Executables.onPath} directly, and every array initializer of literals only (the
     * package-manager loop, {@code DesktopGrab}'s tool table): crude on purpose, and over-matching is harmless.
     */
    private static List<String> literalsReachingOnPath(String source) {
        List<String> found = new ArrayList<>();
        Matcher direct = Pattern.compile("Executables\\.onPath\\(\"([^\"]*)\"\\)").matcher(source);
        while (direct.find()) found.add(direct.group(1));
        Matcher braced = Pattern.compile("\\{([^{}]*)}").matcher(source);
        while (braced.find()) {
            List<String> parts = new ArrayList<>();
            for (String part : braced.group(1).split(",")) {
                String trimmed = part.trim();
                if (trimmed.length() > 1 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                    parts.add(trimmed.substring(1, trimmed.length() - 1));
                } else if (!trimmed.isEmpty()) {
                    parts.clear();
                    break;
                }
            }
            found.addAll(parts);
        }
        return found;
    }
}
