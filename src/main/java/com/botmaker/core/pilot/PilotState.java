// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.core.pilot.transport.FunnelTransport;
import com.botmaker.core.pilot.transport.TransportKind;

import java.util.List;

/**
 * The Remote Pilot as a client shows it. {@link #url} and {@link #qr} carry the pairing token, so a state goes only
 * to a client that asked for it, never into a log or the events every client hears.
 *
 * @param phase   whether it is off, coming up or reachable
 * @param asked   the way to reach it the user picked
 * @param kind    the way that carries it, null unless {@link Phase#ON}; another than {@code asked} when that one
 *                failed and a direct one took over, and then {@code problem} says why
 * @param baseUrl the address without the token, null unless on
 * @param url     the pairing address, token included, null unless on
 * @param qr      {@code url} as a QR code, one string of {@code '1'} (dark) and {@code '0'} per row, the quiet zone
 *                included; empty unless on
 * @param problem why the picked way is not live, or why the pilot stopped; null when there is nothing to say
 * @param fix     what to do about {@code problem}, when something is known
 * @param funnel  Tailscale's state when {@code asked} is Funnel, for its setup steps; else null
 * @param options whether each way can be used, as last probed; empty before the first start
 * @param phones  the phones connected now
 * @param tailnet the phones on this computer's tailnet, probed when {@code asked} is Tailscale
 * @param background Background mode of the open project; {@link Background#NONE} with no project
 */
public record PilotState(Phase phase, TransportKind asked, TransportKind kind, String baseUrl, String url,
                         List<String> qr, String problem, String fix, FunnelTransport.Diag funnel,
                         List<Option> options, List<PilotServer.Connected> phones, List<TailnetPhone> tailnet,
                         Background background) {

    public enum Phase { OFF, STARTING, ON }

    /**
     * Background mode as a client shows it: the open bot's game in a private display the pilot routes through.
     *
     * @param offered whether the bot has something to start there; false for a bot whose settings keep the game on
     *                the desktop, with no launch target, or with a target that runs off the desktop already
     * @param running whether a private display of this project is up now
     * @param display the display, for instance {@code :3}; null unless running
     * @param game    the title of the window it attached; null unless running and attached
     * @param size    the display's size as a sentence, null unless running
     * @param message the outcome of the last start or stop, or the progress of the one under way; null when none
     * @param failed  whether {@code message} says a start failed
     */
    public record Background(boolean offered, boolean running, String display, String game, String size,
                             String message, boolean failed) {

        /** No project, so nothing to offer. */
        public static final Background NONE = new Background(false, false, null, null, null, null, false);
    }

    /** Without background mode: for a state that is built with none to tell. */
    public PilotState(Phase phase, TransportKind asked, TransportKind kind, String baseUrl, String url,
                      List<String> qr, String problem, String fix, FunnelTransport.Diag funnel, List<Option> options,
                      List<PilotServer.Connected> phones, List<TailnetPhone> tailnet) {
        this(phase, asked, kind, baseUrl, url, qr, problem, fix, funnel, options, phones, tailnet, Background.NONE);
    }

    /** The same state telling {@code background}. */
    public PilotState with(Background background) {
        return new PilotState(phase, asked, kind, baseUrl, url, qr, problem, fix, funnel, options, phones, tailnet,
                background);
    }

    /** Whether a way to reach the pilot can be used; when not, why and what to do. */
    public record Option(TransportKind kind, boolean available, String reason, String fix) {}

    /** A phone on the tailnet; {@code lastSeenMillis} is {@code 0} when Tailscale sees it now, or never did. */
    public record TailnetPhone(String name, boolean online, long lastSeenMillis) {}

    public PilotState {
        qr = qr == null ? List.of() : List.copyOf(qr);
        options = options == null ? List.of() : List.copyOf(options);
        phones = phones == null ? List.of() : List.copyOf(phones);
        tailnet = tailnet == null ? List.of() : List.copyOf(tailnet);
        background = background == null ? Background.NONE : background;
    }

    /** Without {@link #url} and {@link #qr}: a record's own text would put the token in any log it reaches. */
    @Override
    public String toString() {
        return "PilotState[" + phase + ", asked=" + asked + ", kind=" + kind + ", baseUrl=" + baseUrl + ", phones="
                + phones.size() + (problem == null ? "" : ", problem=" + problem) + "]";
    }
}
