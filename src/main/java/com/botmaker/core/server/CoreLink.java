package com.botmaker.core.server;

import com.botmaker.core.event.CoreEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * A client's lasting tie to the user's core (plan phase 10d): one {@link CoreClient} at a time, and a new core when that
 * one's channel ends without {@link #close}. The core may be killed, or crash: the link says so, finds or starts a core
 * through {@link CoreLauncher} as the first dial did, and hands the new client to {@link Rejoin}, which attaches it to
 * what the old one was on (the project, the open file, the client's tools). What the core saved stays: every accepted
 * change was on disk before it was answered.
 */
public final class CoreLink implements AutoCloseable {

    /** How many times a lost core is dialled again before the link gives up. */
    static final int TRIES = 3;

    /** Attaches a new core's client to what the old one was on; it throws when that cannot be done. */
    @FunctionalInterface
    public interface Rejoin {
        void rejoin(CoreClient client) throws IOException;
    }

    /** What a link tells its client of the core it is tied to; each may be called on any thread. */
    public interface Listener {
        /** The core stopped; a new one is being started. */
        void stopped();

        /** A new core answers, and {@link Rejoin} attached it. */
        void restarted();

        /** No core could be had again; the link is closed. */
        void lost(IOException why);
    }

    private final Path dir;
    private final String version;
    private final CoreLauncher.Starter starter;
    private final Consumer<CoreEvent> told;
    private final Consumer<Reply.Call> called;
    private final Listener listener;
    private volatile Rejoin rejoin = client -> { };
    private volatile CoreClient client;
    /** Where the core tied to now answers. */
    private volatile CoreLauncher.Address address;
    private volatile boolean closed;
    /** The last client whose end was taken up, so one end starts one restart. Under this. */
    private CoreClient handled;

    private CoreLink(Path dir, String version, CoreLauncher.Starter starter, Consumer<CoreEvent> told,
                     Consumer<Reply.Call> called, Listener listener) {
        this.dir = dir;
        this.version = version;
        this.starter = starter;
        this.told = told;
        this.called = called;
        this.listener = listener;
    }

    /**
     * Ties to the core for {@code dir} of {@code version}, started by {@code starter} when none runs.
     *
     * @throws CoreLauncher.Refused when a core of another version is in use
     */
    public static CoreLink connect(Path dir, String version, CoreLauncher.Starter starter, Consumer<CoreEvent> told,
                                   Consumer<Reply.Call> called, Listener listener) throws IOException {
        CoreLink link = new CoreLink(dir, version, starter, told, called, listener);
        link.client = link.dial();
        // A channel that ended before it was current was passed over by ended(); nothing is on it yet to rejoin.
        if (!link.client.isOpen()) {
            link.close();
            throw new IOException("BotMaker's core ended the channel as it opened");
        }
        return link;
    }

    /** What a new core's client is attached to from now on; nothing until set. */
    public void rejoin(Rejoin then) {
        this.rejoin = then;
    }

    /** The client of the core tied to now. */
    public CoreClient client() {
        return client;
    }

    /** Where the core tied to now answers, its MCP too ({@code McpEndpoint.url}), and the token it takes. */
    public CoreLauncher.Address address() {
        return address;
    }

    private CoreClient dial() throws IOException {
        CoreLauncher.Address at = CoreLauncher.connect(dir, version, starter);
        CoreClient[] self = new CoreClient[1];
        self[0] = CoreClient.connect(at, version, told, called, () -> ended(self[0]));
        address = at;
        return self[0];
    }

    /** {@code which}'s channel ended: when it is the current one and the link is not closed, a new core is had. */
    private synchronized void ended(CoreClient which) {
        if (closed || which == null || which != client || which == handled) return;
        handled = which;
        listener.stopped();
        Thread again = new Thread(this::again, "core-restart");
        again.setDaemon(true);
        again.start();
    }

    private void again() {
        IOException last = null;
        for (int attempt = 0; attempt < TRIES && !closed; attempt++) {
            CoreClient next = null;
            try {
                next = dial();
                // Current only once attached: a new core that ends while it is attached is dialled again here.
                rejoin.rejoin(next);
                client = next;
                listener.restarted();
                // Its channel may have ended before it was current, which ended() passed over then.
                if (!next.isOpen()) ended(next);
                return;
            } catch (IOException e) {
                last = e;
                if (next != null) next.close();
            }
        }
        if (closed) return;
        closed = true;
        listener.lost(last == null ? new IOException("the link was closed") : last);
    }

    /** Lets the core go: no new one is started. */
    @Override
    public void close() {
        closed = true;
        CoreClient now = client;
        if (now != null) now.close();
    }
}
