// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The phones on this computer's tailnet, and whether Tailscale sees them online: the one fact that tells "the
 * pilot is broken" from "the phone's Tailscale is off".
 *
 * <p>Read from {@code tailscale status --json}, where each peer carries its {@code OS}, {@code Online} and
 * {@code LastSeen}. A client shows it under the Tailscale transport, so a phone that dropped off the tailnet days
 * ago is named before anyone scans a QR it cannot reach.
 */
public final class TailnetPhones {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One phone peer. {@code lastSeen} is empty when Tailscale never saw it, or sees it now. */
    public record Phone(String name, boolean online, Optional<Instant> lastSeen) {}

    private TailnetPhones() {
    }

    /** The phones {@code tailscale status --json} lists now; empty when the CLI is missing or says nothing. */
    public static List<Phone> probe() {
        return TailscaleFunnelService.statusJson().map(TailnetPhones::parse).orElse(List.of());
    }

    /** The Android and iOS peers in a {@code tailscale status --json} document, online ones first. */
    static List<Phone> parse(String json) {
        List<Phone> phones = new ArrayList<>();
        try {
            for (JsonNode peer : JSON.readTree(json).path("Peer")) {
                String os = peer.path("OS").asText("").toLowerCase(Locale.ROOT);
                if (!os.equals("android") && !os.equals("ios")) continue;
                phones.add(new Phone(peer.path("HostName").asText("phone"), peer.path("Online").asBoolean(false),
                        instant(peer.path("LastSeen").asText(null))));
            }
        } catch (Exception ignored) {
            // Not JSON: no phones to report.
        }
        phones.sort((a, b) -> Boolean.compare(b.online(), a.online()));
        return phones;
    }

    /** One line to show:{@code ● Pixel 10 — online} or {@code ○ Pixel 10 — offline, last seen 9 days ago}. */
    public static String describe(Phone phone, Instant now) {
        if (phone.online()) return "● " + phone.name() + " — online in Tailscale";
        return "○ " + phone.name() + " — offline in Tailscale, last seen " + ago(phone.lastSeen(), now);
    }

    static String ago(Optional<Instant> when, Instant now) {
        if (when.isEmpty() || when.get().getEpochSecond() <= 0) return "never";
        Duration d = Duration.between(when.get(), now);
        if (d.toDays() >= 1) return d.toDays() + (d.toDays() == 1 ? " day ago" : " days ago");
        if (d.toHours() >= 1) return d.toHours() + (d.toHours() == 1 ? " hour ago" : " hours ago");
        return Math.max(0, d.toMinutes()) + " min ago";
    }

    private static Optional<Instant> instant(String text) {
        try {
            return text == null || text.isBlank() ? Optional.empty() : Optional.of(Instant.parse(text));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
