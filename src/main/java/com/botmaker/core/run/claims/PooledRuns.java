package com.botmaker.core.run.claims;

import com.botmaker.core.run.agent.PooledMethods;
import com.botmaker.core.run.agent.RunPlan;
import com.botmaker.core.run.claims.RunRegistry.LiveRun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Starts several runs of one bot from one compile (spec {@code 2026-11-15-run-claims-design.md} §5): deals the rows
 * against the live runs of this PC and holds them, under one lock, so no other starter deals an element twice; then
 * starts each run's JVM with its row and run id, gives the row to that process, and frees it when the process ends,
 * or at once when it never starts.
 *
 * <p>Until a run's process has its row, the row is the core's own ({@link LiveRun#ofThisProcess}): it lives as long as
 * the core does, so a core that dies between the hold and the start leaves nothing held.
 */
public final class PooledRuns {

    private PooledRuns() {}

    /** No run was started: the words say why. */
    public static final class Refused extends Exception {
        public Refused(String message) {
            super(message);
        }
    }

    /** Starts one run's JVM: the agent given {@code pooled} and {@code runId} in its plan. */
    @FunctionalInterface
    public interface Launcher {
        Process launch(String runId, List<RunPlan.Pooled> pooled) throws IOException;
    }

    /**
     * One run started.
     *
     * @param row   its row's index
     * @param runId its id in the live runs
     * @param picks what it was dealt, by each pool's parameter
     */
    public record Started(int row, String runId, Process process, Map<String, List<String>> picks) {

        public Started {
            picks = Map.copyOf(picks);
        }
    }

    /**
     * What a start did.
     *
     * @param dealing  how many could run, and why not more
     * @param started  the runs started, in row order
     * @param warnings what the runs should be told (a pool that did not read)
     * @param failed   each row that was dealt but did not start, in words; its elements are free again
     * @param claims   each method of the bot that claims a value while it runs ({@link ClaimCalls}): those keys are
     *                 decided while running, and counted into no capacity
     * @param offers   the plugins' ways to raise capacity when fewer started than asked, by title; listed, never run
     *                 by the start
     */
    public record Outcome(Dealer.Dealing dealing, List<Started> started, List<String> warnings, List<String> failed,
                          List<String> claims, List<String> offers) {

        public Outcome {
            started = List.copyOf(started);
            warnings = List.copyOf(warnings);
            failed = List.copyOf(failed);
            claims = List.copyOf(claims);
            offers = List.copyOf(offers);
        }

        /** This outcome, saying the keys {@code sites} claim are decided while running. */
        public Outcome claiming(List<String> sites) {
            return new Outcome(dealing, started, warnings, failed, sites, offers);
        }

        /** This outcome, listing {@code titles} as the ways to raise capacity. */
        public Outcome offering(List<String> titles) {
            return new Outcome(dealing, started, warnings, failed, claims, titles);
        }

        /** {@code "2 of 3 started: …"}, the dealing's reasons, the failures, then what is decided while running. */
        public String words() {
            StringBuilder out = new StringBuilder(started.size() + " of " + dealing.asked() + " started");
            List<String> why = new ArrayList<>(dealing.reasons().stream().map(Dealer.Reason::words).toList());
            why.addAll(failed);
            if (!claims.isEmpty()) {
                why.add("decided while running: the keys claimed in " + String.join(", ", claims));
            }
            if (!why.isEmpty()) out.append(": ").append(String.join("; ", why));
            return out.toString();
        }
    }

    /**
     * Why the compiled bot in {@code classes} cannot take what {@code read}'s pools deal: a method that is gone, has
     * changed its type, or has no body. Empty when every one can.
     */
    public static Optional<String> unfit(Path classes, PoolSources.Read read) {
        return PooledMethods.unfit(read.methods(), owner -> {
            Path file = classes.resolve(owner.replace('.', '/') + ".class");
            try {
                return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
            } catch (IOException e) {
                return Optional.empty();
            }
        });
    }

    /**
     * Deals {@code runs} rows of {@code read}'s pools for {@code bot}, holds them, keeps what was dealt in the bot's
     * {@code .botmaker/}, and starts a run per row.
     *
     * @param project the bot's folder, where {@link DealHistory} is kept
     * @param pins    by row index, the picks fixed by hand
     */
    public static Outcome start(String bot, Path project, PoolSources.Read read, int runs,
                                Map<Integer, Map<String, List<String>>> pins, RunRegistry registry,
                                Launcher launcher) {
        DealHistory history = DealHistory.of(project);
        List<String> failed = new ArrayList<>();
        record Held(Dealer.Row row, String runId) {}
        List<Held> held = new ArrayList<>();
        Dealer.Dealing dealing = registry.locked(locked -> {
            Dealer.Dealing dealt = Dealer.deal(Dealer.Request.of(read.pools(), runs, locked.live())
                    .pinned(pins).after(history));
            for (Dealer.Row row : dealt.rows()) {
                String runId = RunRegistry.newRunId();
                Optional<RunRegistry.Conflict> conflict = locked.hold(LiveRun.ofThisProcess(runId, bot,
                        row.holds(read.pools())));
                if (conflict.isPresent()) failed.add("run " + (row.index() + 1) + ": " + conflict.get().words());
                else held.add(new Held(row, runId));
            }
            return dealt;
        });
        List<Started> started = new ArrayList<>();
        int next = 0;
        try {
            history.after(held.stream().map(Held::row).toList()).keep(project);
            for (; next < held.size(); next++) {
                Held one = held.get(next);
                Process process;
                try {
                    process = launcher.launch(one.runId(), read.pooled(one.row()));
                } catch (IOException | RuntimeException e) {
                    registry.release(one.runId());
                    failed.add("run " + (one.row().index() + 1) + " did not start: " + e.getMessage());
                    continue;
                }
                // Given to the run's own process: freed when it ends, even when this core has gone.
                registry.locked(locked -> locked.moveTo(one.runId(), process.toHandle()));
                process.onExit().thenRun(() -> registry.release(one.runId()));
                started.add(new Started(one.row().index(), one.runId(), process, one.row().picks()));
            }
        } finally {
            // What threw left the rest the core's own, held as long as it lives: freed now.
            for (int i = next; i < held.size(); i++) registry.release(held.get(i).runId());
        }
        return new Outcome(dealing, started, read.warnings(), failed, List.of(), List.of());
    }
}
