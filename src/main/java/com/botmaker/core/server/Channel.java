package com.botmaker.core.server;

import jakarta.servlet.AsyncContext;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The core's end of one client's channel ({@link CoreServer#STUDIO}): the core writes a message as one JSON line on
 * the stream the client holds open, and the client posts each of its messages to {@code /studio/<id>}. A message is
 * {@code CoreJson}'s text; a blank line is a heartbeat the client skips.
 */
public final class Channel {

    private static final byte[] HEARTBEAT = {'\n'};

    private final String id;
    private final AsyncContext async;
    private final OutputStream out;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<Runnable> onClose = new CopyOnWriteArrayList<>();
    private volatile Consumer<String> inbound = message -> { };

    Channel(String id, AsyncContext async, OutputStream out) {
        this.id = id;
        this.async = async;
        this.out = out;
    }

    public String id() {
        return id;
    }

    /** Who hears the client's messages, each on the thread that received it, in the order the client sent them. */
    public void onMessage(Consumer<String> listener) {
        inbound = listener;
    }

    /** Runs {@code then} once the channel is closed, by either end or a write that failed. */
    public void onClose(Runnable then) {
        synchronized (onClose) {
            if (!closed.get()) {
                onClose.add(then);
                return;
            }
        }
        then.run();
    }

    /**
     * Sends one message, whole. False when the channel is closed or the write failed, which closes it.
     *
     * @throws IllegalArgumentException when {@code json} spans lines: a message is one line
     */
    public boolean send(String json) {
        if (json.indexOf('\n') >= 0 || json.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("A channel message is one line");
        }
        return write((json + '\n').getBytes(StandardCharsets.UTF_8));
    }

    public boolean isOpen() {
        return !closed.get();
    }

    /** Ends the client's stream; the client reads that as the core letting it go. */
    public void close() {
        synchronized (onClose) {
            if (!closed.compareAndSet(false, true)) return;
        }
        try {
            async.complete();
        } catch (IllegalStateException alreadyDone) {
            // The client went first: its stream is gone already.
        }
        onClose.forEach(Runnable::run);
    }

    /** A blank line: a client that has gone fails the write, and the channel closes. */
    boolean heartbeat() {
        return write(HEARTBEAT);
    }

    void received(String message) {
        if (isOpen()) inbound.accept(message);
    }

    private synchronized boolean write(byte[] bytes) {
        if (closed.get()) return false;
        try {
            out.write(bytes);
            out.flush();
            return true;
        } catch (IOException | IllegalStateException gone) {
            close();
            return false;
        }
    }
}
