package com.botmaker.core.run.claims;

import com.botmaker.core.project.ProjectConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What each row of a bot's runs held last time, and when each element was last dealt (spec
 * {@code 2026-11-15-run-claims-design.md} §4): what makes a dealing sticky, so an instance keeps its account, or
 * rotate. Kept by the core in the bot's {@code .botmaker/runs/dealt.json}: this PC's, never Java, in no version,
 * publish or {@code .bmk}.
 *
 * @param rows  by row index, each pool's last pick by its parameter
 * @param used  by pool then element, the dealing that last used it: a count, not a time
 * @param deals how many dealings were remembered
 */
public record DealHistory(List<Map<String, List<String>>> rows, Map<String, Map<String, Long>> used, long deals) {

    /** Nothing dealt yet: every pool deals in its own order. */
    public static final DealHistory NONE = new DealHistory(List.of(), Map.of(), 0);

    private static final String FOLDER = "runs";
    private static final String FILE = "dealt.json";
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public DealHistory {
        List<Map<String, List<String>>> kept = new ArrayList<>();
        for (Map<String, List<String>> row : rows == null ? List.<Map<String, List<String>>>of() : rows) {
            Map<String, List<String>> picks = new LinkedHashMap<>();
            if (row != null) row.forEach((pool, pick) -> picks.put(pool, pick == null ? List.of() : List.copyOf(pick)));
            kept.add(Collections.unmodifiableMap(picks));
        }
        rows = Collections.unmodifiableList(kept);
        Map<String, Map<String, Long>> when = new LinkedHashMap<>();
        if (used != null) used.forEach((pool, elements) -> when.put(pool, Map.copyOf(elements)));
        used = Collections.unmodifiableMap(when);
    }

    /** What row {@code index} held last for the pool filling {@code parameter}; none when it never ran. */
    public List<String> held(int index, String parameter) {
        return index < rows.size() ? rows.get(index).getOrDefault(parameter, List.of()) : List.of();
    }

    /** The dealing that last used {@code element}, {@code 0} for one never dealt: the smaller, the longer ago. */
    public long usedAt(String parameter, String element) {
        return used.getOrDefault(parameter, Map.of()).getOrDefault(element, 0L);
    }

    /** This history after {@code dealt} started: each of its rows remembered under its index, the others kept. */
    public DealHistory after(List<Dealer.Row> dealt) {
        if (dealt.isEmpty()) return this;
        long now = deals + 1;
        List<Map<String, List<String>>> next = new ArrayList<>(rows);
        Map<String, Map<String, Long>> when = new LinkedHashMap<>();
        used.forEach((pool, elements) -> when.put(pool, new LinkedHashMap<>(elements)));
        for (Dealer.Row row : dealt) {
            while (next.size() <= row.index()) next.add(Map.of());
            next.set(row.index(), row.picks());
            row.picks().forEach((pool, pick) -> pick.forEach(
                    element -> when.computeIfAbsent(pool, any -> new LinkedHashMap<>()).put(element, now)));
        }
        return new DealHistory(next, when, now);
    }

    /** The history kept for the bot in {@code project}; {@link #NONE} when none is, or it does not read. */
    public static DealHistory of(Path project) {
        Path file = file(project);
        if (!Files.isRegularFile(file)) return NONE;
        try {
            DealHistory read = JSON.readValue(Files.readAllBytes(file), DealHistory.class);
            return read == null ? NONE : read;
        } catch (IOException | RuntimeException e) {
            // Only an order of preference is lost: the next dealing writes it again.
            return NONE;
        }
    }

    /**
     * Keeps this history for the bot in {@code project}, written whole then moved over the old one. False when it
     * could not be (the file open in another process): only an order of preference is lost, so no start fails on it.
     */
    public boolean keep(Path project) {
        Path file = file(project);
        Path partial = null;
        try {
            Files.createDirectories(file.getParent());
            partial = Files.createTempFile(file.getParent(), FILE, ".partial");
            Files.write(partial, JSON.writeValueAsBytes(this));
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            System.err.println("Warning: could not keep what the runs were dealt in " + file + ": " + e);
            if (partial != null) {
                try {
                    Files.deleteIfExists(partial);
                } catch (IOException left) {
                    // Left beside it: the next keep writes its own.
                }
            }
            return false;
        }
    }

    private static Path file(Path project) {
        return project.resolve(ProjectConfig.STUDIO_DIR).resolve(FOLDER).resolve(FILE);
    }
}
