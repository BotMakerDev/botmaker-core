// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.core.pilot.transport.FunnelMarker;
import com.botmaker.core.pilot.transport.TransportKind;
import com.botmaker.shared.Diag;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * What the pilot remembers between runs of the core: its pairing token, the port it last bound, the way the user
 * picked to reach it, and which process has a Funnel up. All are about this user and this computer, not a project.
 *
 * <p>One properties file, readable by its owner alone since it holds the token, read at each use and replaced whole
 * at each change. Nothing throws: an unreadable or unwritable file costs a new pairing, never a failed start.
 */
public final class PilotPreferences implements PairingStore, FunnelMarker {

    /** The file's name in the user's configuration directory. */
    public static final String NAME = "pilot.properties";

    private static final String TOKEN = "token";
    private static final String PORT = "port";
    private static final String TRANSPORT = "transport";
    private static final String FUNNEL_OWNER = "funnelOwner";

    /** The file, or {@code null} for a store that forgets with the process. */
    private final Path file;
    /** What is kept when there is no file. */
    private final Properties held = new Properties();

    private PilotPreferences(Path file) {
        this.file = file;
    }

    /** The preferences kept in {@code file}, made when first written. */
    public static PilotPreferences in(Path file) {
        return new PilotPreferences(file);
    }

    /** Preferences that forget with the process. */
    public static PilotPreferences inMemory() {
        return new PilotPreferences(null);
    }

    /** The way to reach the pilot the user last picked, {@link TransportKind#TAILNET} until they pick one. */
    public TransportKind transport() {
        TransportKind kind = TransportKind.fromId(get(TRANSPORT));
        return kind == TransportKind.UNKNOWN ? TransportKind.TAILNET : kind;
    }

    /** Remembers the way to reach the pilot the user picked. */
    public void transport(TransportKind kind) {
        put(TRANSPORT, kind.id());
    }

    @Override
    public String token() {
        String token = get(TOKEN);
        return token == null || token.isBlank() ? null : token;
    }

    @Override
    public void token(String token) {
        put(TOKEN, token);
    }

    @Override
    public int port() {
        long port = number(PORT);
        return port > 0 && port <= 0xFFFF ? (int) port : 0;
    }

    @Override
    public void port(int port) {
        put(PORT, port > 0 ? String.valueOf(port) : null);
    }

    @Override
    public long funnelOwner() {
        return number(FUNNEL_OWNER);
    }

    @Override
    public void funnelOwner(long pid) {
        put(FUNNEL_OWNER, pid > 0 ? String.valueOf(pid) : null);
    }

    private long number(String key) {
        String value = get(key);
        try {
            return value == null ? 0 : Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private synchronized String get(String key) {
        return read().getProperty(key);
    }

    /** Sets {@code key}, or removes it when {@code value} is null or blank. */
    private synchronized void put(String key, String value) {
        Properties properties = read();
        if (value == null || value.isBlank()) properties.remove(key);
        else properties.setProperty(key, value);
        if (file != null) write(properties);
    }

    private Properties read() {
        if (file == null) return held;
        Properties properties = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(in);
        } catch (NoSuchFileException none) {
            // Nothing kept yet.
        } catch (IOException | IllegalArgumentException e) {
            Diag.warn("Pilot", "Could not read " + file + ": " + e.getMessage());
            // The next write starts from nothing: keep what could not be read beside it, not overwritten.
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".bak"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // Nothing more to do.
            }
        }
        return properties;
    }

    private void write(Properties properties) {
        try {
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            // A temporary file is made for its owner alone, and the move keeps that.
            Path fresh = Files.createTempFile(dir, "pilot", ".tmp");
            try {
                try (Writer out = Files.newBufferedWriter(fresh, StandardCharsets.UTF_8)) {
                    properties.store(out, "BotMaker Remote Pilot. Holds the pairing token: keep it private.");
                }
                Files.move(fresh, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(fresh);
            }
        } catch (IOException e) {
            Diag.warn("Pilot", "Could not write " + file + ": " + e.getMessage());
        }
    }
}
