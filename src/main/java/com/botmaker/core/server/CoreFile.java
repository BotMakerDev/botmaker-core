package com.botmaker.core.server;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Optional;

/**
 * Where a running core says it listens: {@code core.json} in the user's cache directory, beside {@code mcp.json}
 * (whose token guards it). Written once the port is bound, removed when the core ends; a core killed with
 * {@code kill -9} leaves it, and {@link CoreLauncher} finds no core answering behind it.
 *
 * @param version the core's {@code AppVersion.label()}
 * @param pid     the core's process
 * @param port    its port on {@code 127.0.0.1}
 */
public record CoreFile(String version, long pid, int port) {

    public static final String NAME = "core.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The file in {@code dir}, or empty when there is none or it cannot be read. */
    public static Optional<CoreFile> read(Path dir) {
        Path file = dir.resolve(NAME);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(JSON.readValue(file.toFile(), CoreFile.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Writes this into {@code dir} whole, readable by the user only: a reader never sees half of it. */
    public void write(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path partial = Files.createTempFile(dir, "core", ".json");
        try {
            Files.setPosixFilePermissions(partial, EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException notPosix) {
            // The file stays under the user's own profile directory.
        }
        JSON.writeValue(partial.toFile(), this);
        try {
            Files.move(partial, dir.resolve(NAME), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(partial, dir.resolve(NAME), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Removes the file in {@code dir} when it is still this core's, so a core ending never removes its successor's. */
    public void removeFrom(Path dir) {
        if (!read(dir).filter(this::equals).isPresent()) return;
        try {
            Files.deleteIfExists(dir.resolve(NAME));
        } catch (IOException e) {
            System.err.println("Could not remove " + dir.resolve(NAME) + ": " + e.getMessage());
        }
    }
}
