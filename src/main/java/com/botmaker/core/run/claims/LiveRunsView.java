// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.run.claims;

import com.botmaker.core.run.claims.RunRegistry.Hold;
import com.botmaker.core.run.claims.RunRegistry.LiveRun;
import com.botmaker.core.sharing.UnpackedBots;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The live runs of this PC as the Runs window shows them (spec {@code 2026-11-15-run-claims-design.md} §7, the user's
 * pick H-A): grouped by bot, in the registry's order, each run with what it was dealt and the last line it printed.
 * Read from the registry by any process; it starts and stops nothing.
 *
 * @param groups one per bot holding a live run, in the order its first run was held
 */
public record LiveRunsView(List<Group> groups) {

    public LiveRunsView {
        groups = List.copyOf(groups);
    }

    /** What holds a group's runs. */
    public enum Kind {
        /** A bot of mine: a project folder. */
        MY_BOT("my bot"),
        /** A run copy, a {@code .bmk} unpacked from Get more bots, whose runner never opens a project. */
        RUN_COPY("run copy"),
        /** Setup work holding an element for itself, the Emulators window's Apply and its side runs. */
        SIDE("side run");

        private final String displayName;

        Kind(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /**
     * One bot's live runs.
     *
     * @param bot  who the runs are held under: a bot's folder, or the setup work's own words
     * @param name what the window calls it: a run copy by its {@code bot.json} name, a folder by its own name
     * @param dir  the bot's folder; empty for setup work
     */
    public record Group(String bot, String name, Kind kind, Optional<Path> dir, List<Live> runs) {
        public Group {
            runs = List.copyOf(runs);
        }
    }

    /**
     * One live run.
     *
     * @param holds    what its row holds, in the row's order
     * @param claims   its run-time claims, decided while running
     * @param lastLine the last line it printed; empty when it printed none, or keeps no output this PC reads
     */
    public record Live(String runId, long startedMillis, List<Hold> holds, List<String> claims, String lastLine) {
        public Live {
            holds = List.copyOf(holds);
            claims = List.copyOf(claims);
            lastLine = lastLine == null ? "" : lastLine;
        }

        /** What it was dealt, by each pool's parameter ({@code owner class#method}), in the row's order. */
        public Map<String, List<String>> picks() {
            Map<String, List<String>> picks = new LinkedHashMap<>();
            for (Hold hold : holds) picks.computeIfAbsent(hold.parameter(), p -> new ArrayList<>()).add(hold.element());
            picks.replaceAll((parameter, elements) -> List.copyOf(elements));
            return picks;
        }

        /** Whether it holds an element of a plugin's pool filling {@code parameter}: an instance another bot needs. */
        public boolean holdsPluginElement(String parameter) {
            return holds.stream().anyMatch(hold -> hold.kind() == Hold.Kind.PLUGIN
                    && hold.parameter().equals(parameter));
        }
    }

    /** The live runs of {@code registry} as the window shows them. */
    public static LiveRunsView of(RunRegistry registry) {
        return of(registry.live(), LiveRunsView::lastLine);
    }

    /** {@code live} grouped by bot, {@code lastLine} reading a run's last line from its group's folder. */
    static LiveRunsView of(List<LiveRun> live, Function<Placed, String> lastLine) {
        Map<String, List<LiveRun>> byBot = new LinkedHashMap<>();
        for (LiveRun run : live) byBot.computeIfAbsent(run.bot(), bot -> new ArrayList<>()).add(run);
        List<Group> groups = new ArrayList<>();
        byBot.forEach((bot, runs) -> {
            Optional<Path> dir = folder(bot);
            Kind kind = dir.isEmpty() ? Kind.SIDE : UnpackedBots.isUnpacked(dir.get()) ? Kind.RUN_COPY : Kind.MY_BOT;
            String name = dir.map(UnpackedBots::nameOf).orElse(bot);
            groups.add(new Group(bot, name, kind, dir, runs.stream().map(run -> new Live(run.runId(),
                    run.startedMillis(), run.holds(), run.claims(),
                    dir.map(d -> lastLine.apply(new Placed(d, run.runId()))).orElse(""))).toList()));
        });
        return new LiveRunsView(groups);
    }

    /** A run of the bot in {@code dir}, where its output is. */
    record Placed(Path dir, String runId) {
    }

    /** How many runs are live, every bot's. */
    public int count() {
        return groups.stream().mapToInt(group -> group.runs().size()).sum();
    }

    /** The group of the bot in {@code project}; empty when none of its runs is live. */
    public Optional<Group> groupOf(Path project) {
        String bot = SeveralRuns.bot(project);
        return groups.stream().filter(group -> group.bot().equals(bot)).findFirst();
    }

    /** Every other bot's runs, as {@link #groupOf(Path)} leaves them. */
    public List<Group> others(Path project) {
        String bot = SeveralRuns.bot(project);
        return groups.stream().filter(group -> !group.bot().equals(bot)).toList();
    }

    /** {@code bot} as a folder: a bot is held under its absolute folder, setup work under its own words. */
    private static Optional<Path> folder(String bot) {
        try {
            Path path = Path.of(bot);
            return path.isAbsolute() && Files.isDirectory(path) ? Optional.of(path) : Optional.empty();
        } catch (InvalidPathException words) {
            return Optional.empty();
        }
    }

    private static String lastLine(Placed run) {
        try {
            List<String> lines = SeveralRuns.lastLines(run.dir(), run.runId(), 1);
            return lines.isEmpty() ? "" : lines.getFirst().strip();
        } catch (IOException unreadable) {
            return "";
        }
    }
}
