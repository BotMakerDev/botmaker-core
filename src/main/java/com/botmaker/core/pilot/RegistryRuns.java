// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.core.run.claims.RunRegistry;
import com.botmaker.core.run.claims.RunRegistry.LiveRun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * {@link PilotRuns} over this PC's run registry. A run is listed by the registry's own id; the one whose pid the open
 * project's bot has is the viewed one. A run of another process has no channel to this core, so it is stopped,
 * paused and resumed through the pid the registry gives for its id (a pid a phone names is never used).
 *
 * <p>A pid is signalled only while its process is a bot's JVM, which carries the run agent's jar on its command line:
 * until a run is moved to its own process its row holds the pid of the core or Studio that holds it, and stopping that
 * one would end the whole host. A run once viewed here is never signalled by this path, whatever the pid says.
 * Stopping ends the process through the JVM's own handle, so it works on Windows; pausing needs Unix signals.
 */
public final class RegistryRuns implements PilotRuns {

    /** What every bot JVM's command line names: the run agent's jar ({@code RunAgentJar}). */
    static final String AGENT_JAR = "botmaker-run-agent";
    private static final long SIGNAL_SECONDS = 5;

    private final RunRegistry registry;
    private final Supplier<OptionalLong> viewedPid;
    private final Set<String> paused = ConcurrentHashMap.newKeySet();
    /** Runs the open project held, which only its own commands act on. */
    private final Set<String> ownRuns = ConcurrentHashMap.newKeySet();

    /** Over {@code registry}; {@code viewedPid} is the pid of the bot the open project runs, when it does. */
    public RegistryRuns(RunRegistry registry, Supplier<OptionalLong> viewedPid) {
        this.registry = registry;
        this.viewedPid = viewedPid;
    }

    @Override
    public List<Listed> live() {
        List<LiveRun> live = registry.live();
        Set<String> ids = new HashSet<>();
        live.forEach(run -> ids.add(run.runId()));
        paused.retainAll(ids);
        ownRuns.retainAll(ids);
        return live.stream()
                .sorted(Comparator.comparingLong(LiveRun::startedMillis).thenComparing(LiveRun::runId))
                .map(run -> new Listed(run.runId(), run.bot(), run.startedMillis()))
                .toList();
    }

    @Override
    public Optional<String> viewed() {
        OptionalLong pid = viewedPid.get();
        if (pid.isEmpty()) return Optional.empty();
        Optional<String> id = registry.live().stream().filter(run -> run.pid() == pid.getAsLong())
                .map(LiveRun::runId).findFirst();
        id.ifPresent(ownRuns::add);
        return id;
    }

    /** The runs that are stopped: read from the process where the OS says (Linux), else the ones this list paused. */
    @Override
    public Set<String> paused() {
        Set<String> stopped = new HashSet<>(paused);
        for (LiveRun run : registry.live()) {
            Optional<Boolean> real = stoppedByOs(run.pid());
            if (real.isEmpty()) continue;
            if (real.get()) stopped.add(run.runId());
            else stopped.remove(run.runId());
        }
        return Set.copyOf(stopped);
    }

    @Override
    public synchronized Optional<String> stop(String id) {
        Optional<LiveRun> run = signalable(id);
        if (run.isEmpty()) return refusal;
        ProcessHandle handle = ProcessHandle.of(run.get().pid()).orElse(null);
        if (handle == null) return Optional.of(GONE);
        boolean wasPaused = paused().contains(id);
        handle.destroy();
        // A stopped process takes no signal until it continues.
        if (wasPaused) kill("CONT", run.get().pid());
        paused.remove(id);
        return Optional.empty();
    }

    @Override
    public synchronized Optional<String> pause(String id) {
        Optional<LiveRun> run = signalable(id);
        if (run.isEmpty()) return refusal;
        Optional<String> why = kill("STOP", run.get().pid());
        if (why.isEmpty()) paused.add(id);
        return why;
    }

    @Override
    public synchronized Optional<String> resume(String id) {
        Optional<LiveRun> run = signalable(id);
        if (run.isEmpty()) return refusal;
        Optional<String> why = kill("CONT", run.get().pid());
        if (why.isEmpty()) paused.remove(id);
        return why;
    }

    private static final String GONE = "That run is not running any more.";
    /** Why the last {@link #signalable} refused; read straight after it, under the lock the callers hold. */
    private Optional<String> refusal = Optional.empty();

    /** The run {@code id}, when it may be signalled; else {@link #refusal} says why. */
    private Optional<LiveRun> signalable(String id) {
        Optional<LiveRun> run = registry.live().stream().filter(one -> one.runId().equals(id)).findFirst();
        if (run.isEmpty()) {
            refusal = Optional.of(GONE);
            return Optional.empty();
        }
        long pid = run.get().pid();
        if (ownRuns.contains(id) || pid == ProcessHandle.current().pid()
                || viewedPid.get().stream().anyMatch(held -> held == pid)) {
            refusal = Optional.of("This run is the open project's: it is controlled with its own commands.");
            return Optional.empty();
        }
        if (!isBotJvm(pid)) {
            refusal = Optional.of("This run has no process of its own to control yet.");
            return Optional.empty();
        }
        return run;
    }

    private static boolean isBotJvm(long pid) {
        return ProcessHandle.of(pid).flatMap(handle -> handle.info().commandLine())
                .map(line -> line.contains(AGENT_JAR)).orElse(false);
    }

    private static Optional<String> kill(String signal, long pid) {
        if (!PilotControlService.isSupported()) return Optional.of("Pausing another run is not supported on Windows.");
        try {
            Process process = new ProcessBuilder("kill", "-" + signal, Long.toString(pid))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(SIGNAL_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.of("The signal to the run timed out.");
            }
            int code = process.exitValue();
            return code == 0 ? Optional.empty() : Optional.of("kill -" + signal + " exited " + code);
        } catch (IOException e) {
            return Optional.of("Could not signal the run: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of("Interrupted while signalling the run.");
        }
    }

    /** Whether the OS says the process is stopped ({@code T} in {@code /proc/<pid>/stat}); empty where it cannot say. */
    private static Optional<Boolean> stoppedByOs(long pid) {
        Path stat = Path.of("/proc", Long.toString(pid), "stat");
        try {
            String line = Files.readString(stat);
            int close = line.lastIndexOf(')');
            if (close < 0 || close + 2 >= line.length()) return Optional.empty();
            return Optional.of(line.charAt(close + 2) == 'T');
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
