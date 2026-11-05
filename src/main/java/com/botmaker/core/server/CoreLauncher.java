package com.botmaker.core.server;

import com.botmaker.core.assist.McpConfig;
import com.botmaker.core.assist.McpEndpoint;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * How a client reaches the core: the one running, found by its {@link CoreFile} and proven by its hello, or a new one
 * it starts. Studio uses it, and phase 11's bridge speaks the same files. Two starters at once start one core: each
 * finds or starts under an operating-system lock on {@code core.lock} beside the file, so the second finds the
 * first's.
 *
 * <p>A core of another version is asked to quit when nothing uses it; a core in use is not, and the client is
 * refused with a sentence naming both versions.
 */
public final class CoreLauncher {

    public static final String START_LOCK = "core.lock";
    /** The MCP's own config, whose token guards the core too. */
    public static final String TOKEN_FILE = "mcp.json";

    /** How long a start waits for the core it started, or an old core it asked to quit. */
    public static final Duration WAIT = Duration.ofSeconds(30);
    private static final Duration PAUSE = Duration.ofMillis(100);

    /** Where a core answers, and the token every request but the hello carries. */
    public record Address(int port, String token) {
        public URI uri(String path) {
            return URI.create("http://127.0.0.1:" + port + path);
        }

        @Override
        public String toString() {
            return "Address[port=" + port + "]";
        }
    }

    /** Starts a core for {@code dir}; it is found once its {@link CoreFile} names a port that answers. */
    @FunctionalInterface
    public interface Starter {
        void start(Path dir) throws IOException;
    }

    /** A core of another version is in use, so this client cannot have one of its own. */
    public static final class Refused extends IOException {
        Refused(String sentence) {
            super(sentence);
        }
    }

    /** One start at a time in this process: the file lock alone throws for a second holder in the same JVM. */
    private static final ReentrantLock IN_PROCESS = new ReentrantLock();
    private static final ObjectMapper JSON = new ObjectMapper();
    /** The one client every request to a core goes through, channels too. */
    static final HttpClient HTTP =HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private CoreLauncher() {}

    /**
     * The core of {@code version} for {@code dir} (the user's cache directory), started by {@code starter} when none
     * runs.
     *
     * @throws Refused     when a core of another version is in use
     * @throws IOException when the core started does not answer within {@link #WAIT}
     */
    public static Address connect(Path dir, String version, Starter starter) throws IOException {
        Files.createDirectories(dir);
        IN_PROCESS.lock();
        try (FileChannel file = FileChannel.open(dir.resolve(START_LOCK), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
             FileLock ignored = file.lock()) {
            String token = token(dir);
            Optional<Address> running = running(dir, token, version);
            if (running.isPresent()) return running.get();
            starter.start(dir);
            return awaitStart(dir, token, version);
        } finally {
            IN_PROCESS.unlock();
        }
    }

    /** Starts {@link CoreMain} in a new process with this process's Java and classpath, its output to a log. */
    public static Starter process() {
        return dir -> {
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            ProcessBuilder builder = new ProcessBuilder(List.of(java.toString(), "-cp",
                    System.getProperty("java.class.path"), CoreMain.class.getName(), CoreMain.DIR, dir.toString()));
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("core.log").toFile()));
            builder.redirectInput(ProcessBuilder.Redirect.from(nullFile()));
            builder.start();
        };
    }

    /**
     * The token in {@code dir}'s MCP config, saved first when none is saved (no file, an unreadable one, a blank
     * token), so the core reads the same one.
     */
    static String token(Path dir) {
        Path file = dir.resolve(TOKEN_FILE);
        return McpConfig.readSaved(file).orElseGet(() -> {
            McpConfig fresh = McpConfig.read(file);
            fresh.write(file);
            return fresh;
        }).token();
    }

    /** The core the file names, when it answers as ours: of this version, else quit when unused. */
    private static Optional<Address> running(Path dir, String token, String version) throws IOException {
        Optional<CoreFile> named = CoreFile.read(dir);
        if (named.isEmpty()) return Optional.empty();
        Address at = new Address(named.get().port(), token);
        Optional<CoreServer.Info> info = info(at).filter(core -> !core.ending());
        if (info.isEmpty()) return Optional.empty();
        if (info.get().version().equals(version)) return Optional.of(at);
        if (info.get().clients() > 0 || info.get().busy()) {
            throw new Refused(inUse(info.get().version(), version));
        }
        quit(at, info.get(), version);
        return Optional.empty();
    }

    private static void quit(Address at, CoreServer.Info old, String version) throws IOException {
        int status = send(HttpRequest.newBuilder(at.uri(CoreServer.QUIT))
                .header("Authorization", "Bearer " + at.token())
                .POST(HttpRequest.BodyPublishers.noBody()));
        if (status == 409) {
            throw new Refused(inUse(old.version(), version));
        }
        long until = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < until) {
            if (!proven(at)) return;
            pause();
        }
        throw new IOException("BotMaker's core " + old.version() + " did not quit within " + WAIT.toSeconds() + " s.");
    }

    private static Address awaitStart(Path dir, String token, String version) throws IOException {
        long until = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < until) {
            Optional<CoreFile> named = CoreFile.read(dir);
            if (named.isPresent() && named.get().version().equals(version)) {
                Address at = new Address(named.get().port(), token);
                if (info(at).filter(core -> !core.ending()).isPresent()) return at;
            }
            pause();
        }
        throw new IOException("BotMaker's core did not start within " + WAIT.toSeconds() + " s; its log is "
                + dir.resolve("core.log") + ".");
    }

    /** What the core at {@code at} is, once its hello proves it holds the token; empty when nothing ours answers. */
    static Optional<CoreServer.Info> info(Address at) {
        if (!proven(at)) return Optional.empty();
        try {
            HttpResponse<byte[]> response = HTTP.send(HttpRequest.newBuilder(at.uri(CoreServer.INFO))
                    .header("Authorization", "Bearer " + at.token()).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) return Optional.empty();
            return Optional.of(JSON.readValue(response.body(), CoreServer.Info.class));
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Whether the port answers the hello with the token's proof: what keeps the token from a process that took the
     * port after a core was killed.
     */
    private static boolean proven(Address at) {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        String nonce = HexFormat.of().formatHex(bytes);
        try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                    at.uri(McpEndpoint.HELLO + "?nonce=" + nonce)).GET().timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200
                    && response.body().strip().equals(McpEndpoint.proof(at.token(), nonce));
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static int send(HttpRequest.Builder request) throws IOException {
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private static void pause() throws IOException {
        try {
            Thread.sleep(PAUSE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    /** The sentence a client is refused with while a core of another version is in use, naming both. */
    static String inUse(String core, String client) {
        return "BotMaker's core " + core + " is still in use by another window, and this one is " + client
                + ". Close the other BotMaker windows, then try again.";
    }

    private static File nullFile() {
        return new File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
    }
}
