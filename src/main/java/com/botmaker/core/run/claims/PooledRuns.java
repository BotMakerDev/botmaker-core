// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

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
     * @param how      what the start was asked to do: for {@link SeveralAsk.How#COUNT}, nothing started and the
     *                 dealing's rows are what could
     */
    public record Outcome(Dealer.Dealing dealing, List<Started> started, List<String> warnings, List<String> failed,
                          List<String> claims, List<String> offers, SeveralAsk.How how) {

        public Outcome {
            started = List.copyOf(started);
            warnings = List.copyOf(warnings);
            failed = List.copyOf(failed);
            claims = List.copyOf(claims);
            offers = List.copyOf(offers);
            if (how == null) how = SeveralAsk.How.START;
        }

        /** This outcome, saying the keys {@code sites} claim are decided while running. */
        public Outcome claiming(List<String> sites) {
            return new Outcome(dealing, started, warnings, failed, sites, offers, how);
        }

        /** This outcome, listing {@code titles} as the ways to raise capacity. */
        public Outcome offering(List<String> titles) {
            return new Outcome(dealing, started, warnings, failed, claims, titles, how);
        }

        /**
         * {@code "2 of 3 started: …"}, or {@code "2 of 3 can run: …"} for a count, the dealing's reasons, the failures,
         * then what is decided while running.
         */
        public String words() {
            // A count's line is the dealing's own, its reasons included.
            String head = how == SeveralAsk.How.COUNT ? dealing.words()
                    : started.size() + " of " + dealing.asked() + " started";
            List<String> why = new ArrayList<>();
            if (how != SeveralAsk.How.COUNT) why.addAll(dealing.reasons().stream().map(Dealer.Reason::words).toList());
            why.addAll(failed);
            if (how == SeveralAsk.How.ALL_OR_NONE && started.isEmpty()) why.add("all or none was asked, so none started");
            if (!claims.isEmpty()) {
                why.add("decided while running: the keys claimed in " + String.join(", ", claims));
            }
            if (why.isEmpty()) return head;
            return head + (head.contains(": ") ? "; " : ": ") + String.join("; ", why);
        }

        /** The rows to tell: each run started, or for a count each row that could, with no run id. */
        public List<Listed> listed() {
            if (how == SeveralAsk.How.COUNT) {
                return dealing.rows().stream().map(row -> new Listed(row.index(), "", row.picks())).toList();
            }
            return started.stream().map(run -> new Listed(run.row(), run.runId(), run.picks())).toList();
        }
    }

    /**
     * One row an outcome tells.
     *
     * @param row   its index
     * @param runId the run's id; empty for a count's row, which started nothing
     * @param picks what it was dealt, by each pool's parameter
     */
    public record Listed(int row, String runId, Map<String, List<String>> picks) {
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
     * Deals {@code ask}'s rows of {@code read}'s pools for {@code bot}, holds them, keeps what was dealt in the bot's
     * {@code .botmaker/}, and starts a run per row. A count deals under the same lock and holds, keeps and starts
     * nothing; all or none starts none unless every run asked for is dealt, held and launched, and stops those it
     * launched when one fails.
     *
     * @param project the bot's folder, where {@link DealHistory} is kept
     */
    public static Outcome start(String bot, Path project, PoolSources.Read read, SeveralAsk ask,
                                RunRegistry registry, Launcher launcher) {
        DealHistory history = DealHistory.of(project);
        boolean allOrNone = ask.how() == SeveralAsk.How.ALL_OR_NONE;
        List<String> failed = new ArrayList<>();
        record Held(Dealer.Row row, String runId) {}
        List<Held> held = new ArrayList<>();
        Dealer.Dealing dealing = registry.locked(locked -> {
            Dealer.Dealing dealt = Dealer.deal(Dealer.Request.of(read.pools(), ask.runs(), locked.live())
                    .pinned(ask.pins()).after(history));
            if (ask.how() == SeveralAsk.How.COUNT) return dealt;
            if (allOrNone && !dealt.complete()) return dealt;
            for (Dealer.Row row : dealt.rows()) {
                String runId = RunRegistry.newRunId();
                Optional<RunRegistry.Conflict> conflict = locked.hold(LiveRun.ofThisProcess(runId, bot,
                        row.holds(read.pools()), row.claims(read.pools())));
                if (conflict.isPresent()) failed.add("run " + (row.index() + 1) + ": " + conflict.get().words());
                else held.add(new Held(row, runId));
            }
            if (allOrNone && !failed.isEmpty()) {
                for (Held one : held) locked.release(one.runId());
                held.clear();
            }
            return dealt;
        });
        if (held.isEmpty() && ask.how() != SeveralAsk.How.START) {
            // Nothing held: a count, or all or none that fell short. Nothing is kept as the last dealing either.
            return new Outcome(dealing, List.of(), read.warnings(), failed, List.of(), List.of(), ask.how());
        }
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
                    if (allOrNone) {
                        // One did not start: the ones that did are stopped, and the rest freed below.
                        for (Started run : started) {
                            run.process().destroy();
                            registry.release(run.runId());
                        }
                        started.clear();
                        next++;
                        break;
                    }
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
        return new Outcome(dealing, started, read.warnings(), failed, List.of(), List.of(), ask.how());
    }
}
