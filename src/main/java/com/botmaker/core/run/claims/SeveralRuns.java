// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.run.claims;

import com.botmaker.core.run.claims.RunRegistry.LiveRun;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * One bot's several runs as the live runs of this PC hold them (spec {@code 2026-11-15-run-claims-design.md} §5),
 * read by run id from any process: where each one's output goes, whether it still runs, and its stop.
 */
public final class SeveralRuns {

    /** The most runs one start may ask for: each is a JVM acting on a game of this PC. */
    public static final int MOST = 64;

    private SeveralRuns() {}

    /** Where each of {@code project}'s several runs writes its output, {@code <run id>.log}. */
    public static Path outputs(Path project) {
        return project.resolve("target").resolve("botmaker-several");
    }

    /** {@code runId}'s output file under {@code project}. */
    public static Path log(Path project, String runId) {
        return outputs(project).resolve(runId + ".log");
    }

    /** Who a bot's elements and runs are held under in the live runs: its folder, the same for every process. */
    public static String bot(Path project) {
        return project.toAbsolutePath().normalize().toString();
    }

    /** {@code project}'s live runs, in the registry's order. */
    public static List<LiveRun> of(RunRegistry registry, Path project) {
        String bot = bot(project);
        return registry.live().stream().filter(run -> run.bot().equals(bot)).toList();
    }

    /** {@code project}'s live run {@code runId}; empty once it ended, or when it is another bot's. */
    public static Optional<LiveRun> find(RunRegistry registry, Path project, String runId) {
        return of(registry, project).stream().filter(run -> run.runId().equals(runId)).findFirst();
    }

    /**
     * Ends {@code run}'s process and what it started; false when it had already ended. The registry drops the run as
     * it next reads it, and the core that started it commits what it wrote.
     */
    public static boolean stop(LiveRun run) {
        if (run.pid() == ProcessHandle.current().pid()) return false;
        // Its pid looked up again: alive and started when the run says, or it was reused by another process since.
        Optional<ProcessHandle> process = ProcessHandle.of(run.pid())
                .filter(found -> RunRegistry.Alive.PROCESSES.alive(found.pid(), run.startedMillis()));
        return process.isPresent() && killTree(process.get());
    }

    /** Ends {@code process} and what it started, its children first: once it dies they are no longer its own. */
    public static boolean killTree(ProcessHandle process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        boolean asked = process.destroyForcibly();
        descendants.forEach(ProcessHandle::destroyForcibly);
        return asked;
    }

    /** The last {@code lines} lines of {@code runId}'s output; empty when it wrote none. */
    public static List<String> lastLines(Path project, String runId, int lines) throws IOException {
        Path log = log(project, runId);
        if (!Files.isRegularFile(log)) return List.of();
        int wanted = Math.max(lines, 1);
        // Only the file's end is read, however long the run has printed.
        String tail;
        try (SeekableByteChannel channel = Files.newByteChannel(log)) {
            long size = channel.size();
            int read = (int) Math.min(size, (long) wanted * LINE_BYTES);
            ByteBuffer bytes = ByteBuffer.allocate(read);
            channel.position(size - read);
            while (bytes.hasRemaining() && channel.read(bytes) >= 0) {
                // Until the end read is full.
            }
            // Decoded leniently: a bot prints in its platform's charset, and a malformed byte must not lose the rest.
            tail = new String(bytes.array(), 0, bytes.position(), StandardCharsets.UTF_8);
            // A read that starts inside a line drops that line's end.
            if (read < size) tail = tail.substring(tail.indexOf('\n') + 1);
        }
        List<String> all = tail.lines().toList();
        return List.copyOf(all.subList(Math.max(0, all.size() - wanted), all.size()));
    }

    /** How many bytes {@link #lastLines} reads per line asked for: a longer line is cut at its start. */
    private static final int LINE_BYTES = 1024;
}
