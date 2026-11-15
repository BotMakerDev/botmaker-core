package com.botmaker.core.run.claims;

import com.botmaker.shared.tools.UserDirs;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The live runs of this PC (spec {@code 2026-11-15-run-claims-design.md} §4): one file per run under
 * {@code UserDirs.data()/live-runs/}, naming its bot, its run id, the elements its row holds as the grammar writes
 * them, its run-time claims and its process. Every check and write happens under one lock, shared by every process
 * of this user, so two starters never deal one element twice. A run whose process is gone is dropped as it is read,
 * a crash included.
 *
 * <p>A plugin pool's element is held under the plugin and its parameter, for every bot on this PC; a bot pool's under
 * that bot (see {@link Hold}).
 */
public final class RunRegistry {

    /** Where every BotMaker process of this user keeps its live runs. */
    public static RunRegistry ofThisPc() {
        return new RunRegistry(UserDirs.data().resolve("live-runs"), Alive.PROCESSES);
    }

    /** Whether a run's process still runs. */
    @FunctionalInterface
    public interface Alive {
        /**
         * The operating system's answer: the pid is live and started when the run says. A process whose start this
         * user cannot read is not a run this user started, so the pid was reused and the run is gone.
         */
        Alive PROCESSES = (pid, started) -> ProcessHandle.of(pid)
                .filter(ProcessHandle::isAlive)
                .filter(process -> started == 0 || process.info().startInstant()
                        .map(instant -> Math.abs(instant.toEpochMilli() - started) < START_TOLERANCE_MILLIS)
                        .orElse(false))
                .isPresent();

        boolean alive(long pid, long startedMillis);
    }

    /**
     * One element a run's row holds. Equal {@code kind}, {@code owner}, {@code parameter} and {@code element} are one
     * element; {@code share} live runs may hold it at once ({@code Pool.each}).
     *
     * @param owner     the plugin's {@code groupId:artifactId}, or the bot
     * @param parameter the parameter the pool fills, {@code owner class#method}
     * @param element   the element as the grammar writes it
     */
    public record Hold(Kind kind, String owner, String parameter, String element, int share) {

        /** Who an element is held under. */
        public enum Kind { PLUGIN, BOT }

        public Hold {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(parameter, "parameter");
            Objects.requireNonNull(element, "element");
            if (share < 1) throw new IllegalArgumentException("share " + share + " < 1");
        }

        /** An element of a plugin's pool: held for every bot on this PC. */
        public static Hold plugin(String coordinate, String parameter, String element) {
            return new Hold(Kind.PLUGIN, coordinate, parameter, element, 1);
        }

        /** An element of one bot's own pool: no other bot conflicts with it. */
        public static Hold bot(String bot, String parameter, String element) {
            return new Hold(Kind.BOT, bot, parameter, element, 1);
        }

        /** This hold, {@code n} live runs sharing its element. */
        public Hold sharedBy(int n) {
            return new Hold(kind, owner, parameter, element, n);
        }

        /** Whether {@code other} names the same element, whatever its share. */
        public boolean sameElement(Hold other) {
            return kind == other.kind && owner.equals(other.owner) && parameter.equals(other.parameter)
                    && element.equals(other.element);
        }
    }

    /**
     * One live run.
     *
     * @param startedMillis when its process started, epoch millis, 0 when unknown: a reused pid is not the run's
     * @param claims        its run-time claims, each key as the run gave it to {@code Claims.claim}
     */
    public record LiveRun(String runId, String bot, long pid, long startedMillis, List<Hold> holds,
                          List<String> claims) {

        public LiveRun {
            Objects.requireNonNull(bot, "bot");
            if (!RUN_ID.matcher(Objects.requireNonNull(runId, "runId")).matches()) {
                throw new IllegalArgumentException("A run id is letters, digits and dashes: " + runId);
            }
            holds = List.copyOf(holds);
            claims = List.copyOf(claims);
        }

        /** A run held by this process until {@link Locked#moveTo} gives it the run's own. */
        public static LiveRun ofThisProcess(String runId, String bot, List<Hold> holds) {
            ProcessHandle self = ProcessHandle.current();
            return new LiveRun(runId, bot, self.pid(), startOf(self), holds, List.of());
        }
    }

    /** Why a run was not held: {@code hold} is already held by as many live runs as it allows, {@code by} among them. */
    public record Conflict(Hold hold, Optional<LiveRun> by) {

        /** In words, naming the element and the run that holds it; no run when the row itself asks for it too often. */
        public String words() {
            return by.map(run -> hold.element() + " is held by a run of " + run.bot() + " (" + run.runId() + ")")
                    .orElseGet(() -> "The row asks for " + hold.element() + " more often than it may be held");
        }
    }

    /** The registry under its lock: what one starter reads and writes before another may. */
    public interface Locked {

        /** Every live run, the dead dropped. */
        List<LiveRun> live();

        /** Holds {@code run}'s row, or says the first element that is not free; a run id already live is refused. */
        Optional<Conflict> hold(LiveRun run);

        /** Gives {@code runId}'s row to the run's own process; false when it is not live. */
        boolean moveTo(String runId, ProcessHandle process);

        /** Frees {@code runId}'s row and claims; nothing when it is not live. */
        void release(String runId);

        /** {@link RunRegistry#claim}. */
        boolean claim(String runId, String key);

        /** {@link RunRegistry#unclaim}. */
        void unclaim(String runId, String key);
    }

    /** A new run id. */
    public static String newRunId() {
        return UUID.randomUUID().toString();
    }

    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final long START_TOLERANCE_MILLIS = 1000;
    private static final String SUFFIX = ".json";
    private static final String LOCK_FILE = ".lock";
    /** Lenient on what it does not know: another BotMaker version's run is still a live run. */
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    /** One lock per folder in this process, beside the file lock for the others: a JVM cannot file-lock twice. */
    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private final Path folder;
    private final Alive alive;

    public RunRegistry(Path folder, Alive alive) {
        this.folder = folder.toAbsolutePath().normalize();
        this.alive = alive;
    }

    /** The folder of the live runs: what a run's agent opens to check its claims. */
    public Path folder() {
        return folder;
    }

    /** Every live run, the dead dropped. */
    public List<LiveRun> live() {
        return locked(Locked::live);
    }

    /** {@link Locked#hold}, under the lock. */
    public Optional<Conflict> hold(LiveRun run) {
        return locked(registry -> registry.hold(run));
    }

    /** {@link Locked#release}, under the lock. */
    public void release(String runId) {
        locked(registry -> {
            registry.release(runId);
            return null;
        });
    }

    /**
     * Claims {@code key} for {@code runId} while it runs: false when another live run holds it, or {@code runId} is
     * not live. Claiming a key the run holds already answers true.
     */
    public boolean claim(String runId, String key) {
        return locked(registry -> registry.claim(runId, key));
    }

    /** Frees {@code runId}'s claim on {@code key}, if it holds one. */
    public void unclaim(String runId, String key) {
        locked(registry -> {
            registry.unclaim(runId, key);
            return null;
        });
    }

    /** {@code work} under the registry's lock, for a starter that reads, deals and holds in one step. */
    public <T> T locked(Function<Locked, T> work) {
        ReentrantLock lock;
        try {
            Files.createDirectories(folder);
            // The real path: a link or a Windows short name is the same folder, and must be the same lock.
            lock = LOCKS.computeIfAbsent(folder.toRealPath(), any -> new ReentrantLock());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open the live runs in " + folder, e);
        }
        lock.lock();
        try {
            if (lock.getHoldCount() > 1) return work.apply(new Opened());
            try (FileChannel channel = FileChannel.open(folder.resolve(LOCK_FILE), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return work.apply(new Opened());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not lock the live runs in " + folder, e);
        } catch (OverlappingFileLockException e) {
            throw new IllegalStateException("The live runs in " + folder + " are locked by this process already.", e);
        } finally {
            lock.unlock();
        }
    }

    private static long startOf(ProcessHandle process) {
        return process.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
    }

    /** The registry's files, read and written only under {@link #locked}. */
    private final class Opened implements Locked {

        @Override
        public List<LiveRun> live() {
            List<LiveRun> runs = new ArrayList<>();
            try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*" + SUFFIX)) {
                for (Path file : files) {
                    Optional<LiveRun> run = read(file);
                    if (run.isPresent() && alive.alive(run.get().pid(), run.get().startedMillis())) {
                        runs.add(run.get());
                    } else {
                        Files.deleteIfExists(file);
                    }
                }
            } catch (IOException e) {
                // A file that cannot be opened may be a live run's: nothing is dealt over it.
                throw new UncheckedIOException("Could not read the live runs in " + folder, e);
            }
            runs.sort(Comparator.comparing(LiveRun::runId));
            return runs;
        }

        @Override
        public Optional<Conflict> hold(LiveRun run) {
            List<LiveRun> live = live();
            if (live.stream().anyMatch(other -> other.runId().equals(run.runId()))) {
                throw new IllegalStateException("Run " + run.runId() + " is live already.");
            }
            List<Hold> mine = new ArrayList<>();
            for (Hold hold : run.holds()) {
                // Every hold of the element counts, and the smallest share any of them allows is the limit.
                List<Hold> same = Stream.concat(live.stream().flatMap(other -> other.holds().stream()), mine.stream())
                        .filter(hold::sameElement).toList();
                int share = Stream.concat(same.stream(), Stream.of(hold)).mapToInt(Hold::share).min().orElseThrow();
                if (same.size() >= share) {
                    return Optional.of(new Conflict(hold, live.stream()
                            .filter(other -> other.holds().stream().anyMatch(hold::sameElement)).findFirst()));
                }
                mine.add(hold);
            }
            write(run);
            return Optional.empty();
        }

        @Override
        public boolean moveTo(String runId, ProcessHandle process) {
            Optional<LiveRun> run = find(runId);
            run.ifPresent(found -> write(new LiveRun(found.runId(), found.bot(), process.pid(), startOf(process),
                    found.holds(), found.claims())));
            return run.isPresent();
        }

        @Override
        public void release(String runId) {
            if (!RUN_ID.matcher(runId).matches()) return;
            try {
                Files.deleteIfExists(fileOf(runId));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not free run " + runId, e);
            }
        }

        @Override
        public boolean claim(String runId, String key) {
            List<LiveRun> live = live();
            Optional<LiveRun> run = live.stream().filter(other -> other.runId().equals(runId)).findFirst();
            if (run.isEmpty()) return false;
            if (run.get().claims().contains(key)) return true;
            if (live.stream().anyMatch(other -> other.claims().contains(key))) return false;
            List<String> claims = new ArrayList<>(run.get().claims());
            claims.add(key);
            write(withClaims(run.get(), claims));
            return true;
        }

        @Override
        public void unclaim(String runId, String key) {
            find(runId).filter(run -> run.claims().contains(key)).ifPresent(run -> {
                List<String> claims = new ArrayList<>(run.claims());
                claims.remove(key);
                write(withClaims(run, claims));
            });
        }

        private Optional<LiveRun> find(String runId) {
            return live().stream().filter(run -> run.runId().equals(runId)).findFirst();
        }

        private LiveRun withClaims(LiveRun run, List<String> claims) {
            return new LiveRun(run.runId(), run.bot(), run.pid(), run.startedMillis(), run.holds(), claims);
        }

        /** The run's file, written whole then moved over the old one, so no reader sees half of it. */
        private void write(LiveRun run) {
            Path file = fileOf(run.runId());
            Path partial = file.resolveSibling(run.runId() + ".partial");
            try {
                Files.write(partial, JSON.writeValueAsBytes(run));
                Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                try {
                    Files.deleteIfExists(partial);
                } catch (IOException left) {
                    e.addSuppressed(left);
                }
                throw new UncheckedIOException("Could not hold run " + run.runId(), e);
            }
        }

        /**
         * The run a file names; empty for one whose text is not a run, which no live run wrote: each is written
         * whole. A file that cannot be opened throws instead, since it may be a live run's.
         */
        private Optional<LiveRun> read(Path file) throws IOException {
            byte[] bytes = Files.readAllBytes(file);
            try {
                return Optional.of(JSON.readValue(bytes, LiveRun.class));
            } catch (IOException | RuntimeException e) {
                return Optional.empty();
            }
        }

        private Path fileOf(String runId) {
            return folder.resolve(runId + SUFFIX);
        }
    }
}
