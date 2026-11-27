// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot.transport;

import java.util.List;

/**
 * The ways a phone can reach the Remote Pilot, each free: no paid plan and no domain of one's own.
 *
 * <p>The {@link #id()} is what the pilot's preferences remember, so it never changes once shipped; the
 * {@link #displayName()} is what a client shows.
 */
public enum TransportKind {

    /** A direct bind on this computer's Tailscale address; the phone runs Tailscale on the same account. */
    TAILNET("tailnet", "Tailscale", false),
    /** Tailscale Funnel: public HTTPS on the machine's {@code ts.net} name; the phone needs nothing. */
    FUNNEL("funnel", "Tailscale Funnel", true),
    /** A Cloudflare quick tunnel: a public {@code trycloudflare.com} address, no account, new at every start. */
    QUICK_TUNNEL("quick-tunnel", "Cloudflare quick tunnel", true),
    /** A direct bind on this computer's local network address; the phone must be on the same Wi-Fi. */
    LAN("lan", "Local network", false),
    /** An id this build does not know, read from the preferences of a newer one. */
    UNKNOWN("unknown", "Unknown", false);

    private final String id;
    private final String displayName;
    private final boolean publicInternet;

    TransportKind(String id, String displayName, boolean publicInternet) {
        this.id = id;
        this.displayName = displayName;
        this.publicInternet = publicInternet;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** Whether the address is reachable from the whole internet, so the pairing token is the only lock. */
    public boolean publicInternet() {
        return publicInternet;
    }

    /** The kind with this id, or {@link #UNKNOWN}; never {@code null}. */
    public static TransportKind fromId(String id) {
        for (TransportKind kind : values()) {
            if (kind.id.equals(id)) return kind;
        }
        return UNKNOWN;
    }

    /** The kinds a user can pick, in the order a client lists them. */
    public static List<TransportKind> offered() {
        return List.of(TAILNET, FUNNEL, QUICK_TUNNEL, LAN);
    }
}
