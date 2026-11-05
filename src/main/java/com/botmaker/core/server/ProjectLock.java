package com.botmaker.core.server;

import com.botmaker.core.project.ProjectConfig;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.OptionalLong;

/**
 * A core's hold on one open project: {@code .botmaker/core.lock} in the project, locked by the operating system and
 * holding the owner's pid. The lock goes with its process, however it ends, so a core killed with {@code kill -9}
 * leaves only a stale pid, which the next core overwrites.
 */
public final class ProjectLock implements AutoCloseable {

    public static final String NAME = "core.lock";

    /** Another live process holds the project. */
    public static final class Held extends IOException {
        private final transient OptionalLong owner;

        Held(Path project, OptionalLong owner) {
            super(project.getFileName() + " is open in another BotMaker"
                    + (owner.isPresent() ? " (process " + owner.getAsLong() + ")" : "") + ".");
            this.owner = owner;
        }

        /** The pid the file names, when it names one. */
        public OptionalLong owner() {
            return owner;
        }
    }

    private final FileChannel file;
    private final FileLock lock;

    private ProjectLock(FileChannel file, FileLock lock) {
        this.file = file;
        this.lock = lock;
    }

    /**
     * Takes {@code project} for this process.
     *
     * @throws Held when another process, or this one already, holds it
     */
    public static ProjectLock take(Path project) throws IOException {
        Path path = project.resolve(ProjectConfig.STUDIO_DIR).resolve(NAME);
        Files.createDirectories(path.getParent());
        FileChannel file = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try {
                lock = file.tryLock(LOCKED_BYTE, 1, false);
            } catch (OverlappingFileLockException mine) {
                lock = null;
            }
            if (lock == null) throw new Held(project, pidIn(file));
            file.truncate(0);
            file.write(ByteBuffer.wrap(Long.toString(ProcessHandle.current().pid())
                    .getBytes(StandardCharsets.UTF_8)), 0);
            file.force(false);
            return new ProjectLock(file, lock);
        } catch (IOException | RuntimeException e) {
            file.close();
            throw e;
        }
    }

    /**
     * The byte the lock covers, far past the pid: Windows' locks are mandatory, and one over the pid would keep
     * another process from reading whose it is.
     */
    private static final long LOCKED_BYTE = Long.MAX_VALUE - 1;

    /** The pid {@code project}'s lock file names, live or not; empty when there is none. */
    public static OptionalLong owner(Path project) throws IOException {
        Path path = project.resolve(ProjectConfig.STUDIO_DIR).resolve(NAME);
        if (!Files.isRegularFile(path)) return OptionalLong.empty();
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.READ)) {
            return pidIn(file);
        }
    }

    /** Lets the project go; the file stays, empty, so a waiting core never locks a file this one removed. */
    @Override
    public void close() throws IOException {
        try {
            file.truncate(0);
            lock.release();
        } finally {
            file.close();
        }
    }

    private static OptionalLong pidIn(FileChannel file) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(32);
        int read = file.read(bytes, 0);
        if (read <= 0) return OptionalLong.empty();
        String text = new String(bytes.array(), 0, read, StandardCharsets.UTF_8).strip();
        try {
            return OptionalLong.of(Long.parseLong(text));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
