// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot.transport;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * One way of making the Remote Pilot's server reachable from a phone.
 *
 * <p>The server is the same whatever the transport: it binds {@link #bindHost()}, and the transport then
 * makes that port reachable and says at which address ({@link #open(int)}). A tunnel binds loopback, so only
 * the tunnel reaches the server; a direct transport binds one interface, never all of them. The pairing token
 * is checked on every connection whichever transport carried it.
 *
 * <p>Every method may block (a CLI call, a child process), so none runs on a thread that answers a client. None
 * throws: a failure is a value a client can show.
 */
public interface PilotTransport extends AutoCloseable {

    TransportKind kind();

    /** Whether this transport can be used now, and if not, why and what to do about it. */
    Availability available();

    /** The address the server binds for this transport. */
    String bindHost();

    /**
     * Makes {@code port}, already bound on {@link #bindHost()}, reachable, and returns the base address the
     * phone opens (scheme, host and port, no path or query), or the reason it could not.
     */
    Opened open(int port);

    /**
     * Completes when what {@link #open} made stops by itself, and the address no longer answers. Never for a
     * {@link #close}, and never for a transport with nothing of its own running.
     */
    default CompletionStage<Void> lost() {
        return new CompletableFuture<>();
    }

    /** Undoes {@link #open}: stops a tunnel, turns a Funnel off. Idempotent, and never throws. */
    @Override
    void close();

    /** Whether a transport can be used; when not, a sentence saying why and one saying what to do. */
    record Availability(boolean ok, String reason, String fix) {
        public static Availability yes() {
            return new Availability(true, null, null);
        }

        public static Availability no(String reason, String fix) {
            return new Availability(false, reason, fix);
        }
    }

    /** What {@link #open} did: the base address, or the error that stopped it. */
    record Opened(String baseUrl, String error) {
        public static Opened at(String baseUrl) {
            return new Opened(baseUrl, null);
        }

        public static Opened failed(String error) {
            return new Opened(null, error);
        }

        public boolean ok() {
            return baseUrl != null;
        }
    }

    /**
     * A new transport of {@code kind}; {@link TransportKind#UNKNOWN} is the default one, the tailnet.
     *
     * @param marker where a Funnel records its process
     */
    static PilotTransport of(TransportKind kind, FunnelMarker marker) {
        return switch (kind) {
            case FUNNEL -> new FunnelTransport(marker);
            case QUICK_TUNNEL -> new QuickTunnelTransport();
            case LAN -> new DirectTransport(TransportKind.LAN);
            case TAILNET, UNKNOWN -> new DirectTransport(TransportKind.TAILNET);
        };
    }
}
