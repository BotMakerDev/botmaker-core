package com.botmaker.core.server;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A client's end of its channel to the core ({@link Channel}): it holds the core's stream open and reads each line as
 * one message, and posts each of its own. Studio's {@code CoreClient} speaks the protocol over it (phase 10a).
 */
public final class ChannelClient implements AutoCloseable {

    /** How long a message may take to be taken; a core that answers nothing in that time is treated as gone. */
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CoreLauncher.Address at;
    private final String id;
    private final Stream<String> stream;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean ended = new AtomicBoolean();

    private ChannelClient(CoreLauncher.Address at, String id, Stream<String> stream) {
        this.at = at;
        this.id = id;
        this.stream = stream;
    }

    /**
     * Opens a channel as a client of {@code version}: {@code inbound} hears each of the core's messages on one reader
     * thread, in order, and {@code ended} runs once when the channel ends, by either side. A listener that throws
     * loses that message only: it is reported and the next one is read.
     *
     * @throws CoreLauncher.Refused when the core is of another version, with its sentence naming both
     */
    public static ChannelClient open(CoreLauncher.Address at, String version, Consumer<String> inbound,
                                     Runnable ended) throws IOException {
        HttpResponse<Stream<String>> response;
        try {
            response = CoreLauncher.HTTP.send(HttpRequest.newBuilder(at.uri(CoreServer.STUDIO))
                    .header("Authorization", "Bearer " + at.token())
                    .header(CoreServer.VERSION_HEADER, version)
                    .GET().build(), HttpResponse.BodyHandlers.ofLines());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        Stream<String> lines = response.body();
        ChannelClient client;
        Iterator<String> each;
        try {
            if (response.statusCode() == 409) {
                throw new CoreLauncher.Refused(lines.collect(Collectors.joining(" ")).strip());
            }
            if (response.statusCode() != 200) {
                throw new IOException("The core refused the channel (HTTP " + response.statusCode() + ").");
            }
            each = lines.iterator();
            String first = each.hasNext() ? each.next() : "";
            String id = first.isBlank() ? "" : JSON.readTree(first).path("channel").asText("");
            if (id.isEmpty()) throw new IOException("The core opened no channel.");
            client = new ChannelClient(at, id, lines);
        } catch (IOException | RuntimeException e) {
            lines.close();
            throw e;
        }
        Thread reader = new Thread(() -> client.read(each, inbound, ended), "core-channel-" + client.id);
        reader.setDaemon(true);
        reader.start();
        return client;
    }

    /**
     * Sends one message and waits until the core has taken it, so messages arrive in the order sent.
     *
     * @throws IOException when the channel is gone or the core took longer than {@link #SEND_TIMEOUT}
     */
    public synchronized void send(String json) throws IOException {
        if (closed.get()) throw new IOException("The channel to the core is closed.");
        int status = call(HttpRequest.newBuilder(at.uri(CoreServer.STUDIO + "/" + id))
                .POST(HttpRequest.BodyPublishers.ofString(json)));
        if (status != 204) throw new IOException("The core did not take the message (HTTP " + status + ").");
    }

    public boolean isOpen() {
        return !closed.get();
    }

    /** Ends the channel; the core hears it at once. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        letGo();
    }

    private void read(Iterator<String> each, Consumer<String> inbound, Runnable ended) {
        try {
            while (each.hasNext()) {
                String line = each.next();
                if (line.isBlank()) continue;
                try {
                    inbound.accept(line);
                } catch (RuntimeException fault) {
                    System.err.println("A message from the core was not handled: " + fault);
                }
            }
        } catch (RuntimeException cut) {
            // The stream broke: the core ended or the channel was closed.
        } finally {
            // Closed by this side: letGo told the core already. Ended by the core's: it knows, and its port may be
            // another process's by now, so the token is not sent to it again.
            closed.set(true);
            if (this.ended.compareAndSet(false, true)) stream.close();
            ended.run();
        }
    }

    /** Tells the core the channel is done and drops the stream, once, whichever end went first. */
    private void letGo() {
        if (!ended.compareAndSet(false, true)) return;
        try {
            call(HttpRequest.newBuilder(at.uri(CoreServer.STUDIO + "/" + id)).DELETE());
        } catch (IOException gone) {
            // The core is gone already: there is nothing to tell.
        }
        stream.close();
    }

    private int call(HttpRequest.Builder request) throws IOException {
        try {
            return CoreLauncher.HTTP.send(request.header("Authorization", "Bearer " + at.token())
                    .timeout(SEND_TIMEOUT).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }
}
