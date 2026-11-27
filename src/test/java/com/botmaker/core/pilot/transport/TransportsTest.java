// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The free transports' pure parts, and a quick tunnel driven by a stand-in for {@code cloudflared}. */
class TransportsTest {

    @Test
    void every_kind_round_trips_its_id_and_a_stranger_is_unknown() {
        for (TransportKind kind : TransportKind.values()) assertEquals(kind, TransportKind.fromId(kind.id()));
        assertEquals(TransportKind.UNKNOWN, TransportKind.fromId("ngrok"));
        assertEquals(TransportKind.UNKNOWN, TransportKind.fromId(null));
        assertFalse(TransportKind.offered().contains(TransportKind.UNKNOWN));
    }

    /** Wording from cloudflared 2025.x: the address sits in a box, and its own API host must not be taken for it. */
    @Test
    void reads_the_quick_tunnel_address_from_cloudflareds_log() {
        String log = """
                INF Requesting new quick Tunnel on trycloudflare.com...
                INF +--------------------------------------------------------------------------------------------+
                INF |  Your quick Tunnel has been created! Visit it at (it may take some time to be reachable):  |
                INF |  https://calm-river-fox-1234.trycloudflare.com                                            |
                """;
        assertEquals(Optional.of("https://calm-river-fox-1234.trycloudflare.com"), QuickTunnelTransport.parseUrl(log));
        assertEquals(Optional.empty(), QuickTunnelTransport.parseUrl(
                "ERR failed to request quick Tunnel: Post \"https://api.trycloudflare.com/tunnel\": dial tcp"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_quick_tunnel_returns_the_address_it_printed_and_stops_its_process_on_close() {
        // "sh -c <script> cloudflared tunnel …": the script prints what cloudflared would, then stays up.
        QuickTunnelTransport tunnel = new QuickTunnelTransport(List.of("sh", "-c",
                "echo 'INF |  https://test-tunnel.trycloudflare.com  |'; echo 'INF Registered tunnel connection';"
                        + " exec sleep 30", "cloudflared"), 5_000, 5_000);
        PilotTransport.Opened opened = tunnel.open(8123);
        assertTrue(opened.ok(), opened.error());
        assertEquals("https://test-tunnel.trycloudflare.com", opened.baseUrl());
        tunnel.close();
        tunnel.close(); // idempotent
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_quick_tunnel_that_prints_no_address_fails_with_what_it_said() {
        QuickTunnelTransport tunnel = new QuickTunnelTransport(
                List.of("sh", "-c", "echo 'ERR no network'; exit 1", "cloudflared"), 5_000, 5_000);
        PilotTransport.Opened opened = tunnel.open(8123);
        assertFalse(opened.ok());
        assertTrue(opened.error().contains("no network"), opened.error());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_quick_tunnel_whose_process_dies_is_lost_and_one_that_is_closed_is_not() throws Exception {
        String registered = "echo 'INF |  https://test-tunnel.trycloudflare.com  |';"
                + " echo 'INF Registered tunnel connection';";
        QuickTunnelTransport dies = new QuickTunnelTransport(
                List.of("sh", "-c", registered + " sleep 1", "cloudflared"), 5_000, 5_000);
        assertTrue(dies.open(8123).ok());
        dies.lost().toCompletableFuture().get(10, TimeUnit.SECONDS);
        dies.close();

        QuickTunnelTransport stopped = new QuickTunnelTransport(
                List.of("sh", "-c", registered + " exec sleep 30", "cloudflared"), 5_000, 5_000);
        assertTrue(stopped.open(8123).ok());
        stopped.close();
        assertFalse(stopped.lost().toCompletableFuture().isDone());
    }

    @Test
    void a_funnel_is_left_over_only_when_its_process_is_gone() {
        assertFalse(FunnelTransport.leftover(0, pid -> false), "none recorded");
        assertFalse(FunnelTransport.leftover(42, pid -> true), "its process still runs");
        assertTrue(FunnelTransport.leftover(42, pid -> false));
    }

    @Test
    void a_missing_cloudflared_is_unavailable_with_an_install_line() {
        PilotTransport.Availability a =
                new QuickTunnelTransport(List.of("botmaker-no-such-cloudflared"), 1_000, 1_000).available();
        assertFalse(a.ok());
        assertTrue(a.fix().toLowerCase().contains("install"), a.fix());
    }

    /** On the dev box a Waydroid bridge (192.168.240.1) sits beside the Wi-Fi; the route must pick the Wi-Fi. */
    @Test
    void the_lan_address_found_here_is_never_a_virtual_bridge() throws Exception {
        String lan = DirectTransport.lanAddress();
        if (lan == null) return; // a build machine with no private network
        java.net.NetworkInterface nic = java.net.NetworkInterface.getByInetAddress(InetAddress.getByName(lan));
        assertTrue(DirectTransport.isLan(nic.getName(), InetAddress.getByName(lan), InetAddress.getByName(lan).getAddress()),
                nic.getName() + " " + lan);
    }

    /** Trimmed from {@code tailscale status --json} on the dev box, 2026-09-29: the phone nine days offline. */
    @Test
    void reads_the_phones_on_the_tailnet_and_when_they_were_last_seen() {
        List<TailnetPhones.Phone> phones = TailnetPhones.parse("""
                {"BackendState":"Running","Peer":{
                  "k1":{"HostName":"laptop","OS":"linux","Online":true},
                  "k2":{"HostName":"Pixel 10","OS":"android","Online":false,"LastSeen":"2026-09-19T16:11:03.1Z"},
                  "k3":{"HostName":"iPad","OS":"iOS","Online":true,"LastSeen":"0001-01-01T00:00:00Z"}}}
                """);
        assertEquals(2, phones.size());
        assertEquals("iPad", phones.get(0).name()); // online first
        java.time.Instant now = java.time.Instant.parse("2026-09-29T12:00:00Z");
        assertEquals("○ Pixel 10 — offline in Tailscale, last seen 9 days ago",
                TailnetPhones.describe(phones.get(1), now));
        assertEquals("● iPad — online in Tailscale", TailnetPhones.describe(phones.get(0), now));
        assertEquals(List.of(), TailnetPhones.parse("not json"));
    }

    @Test
    void tailscale_is_the_cgnat_range_and_the_lan_is_private_and_not_virtual() throws Exception {
        assertTrue(DirectTransport.isTailscale(new byte[] {100, 64, 0, 1}));
        assertTrue(DirectTransport.isTailscale(new byte[] {100, 127, 9, 9}));
        assertFalse(DirectTransport.isTailscale(new byte[] {100, (byte) 128, 0, 1}));
        assertFalse(DirectTransport.isTailscale(new byte[] {(byte) 192, (byte) 168, 1, 2}));

        InetAddress home = InetAddress.getByName("192.168.1.20");
        assertTrue(DirectTransport.isLan("wlp2s0", home, home.getAddress()));
        assertFalse(DirectTransport.isLan("docker0", home, home.getAddress()));
        assertFalse(DirectTransport.isLan("tailscale0", home, home.getAddress()));
        InetAddress waydroid = InetAddress.getByName("192.168.240.1");
        assertFalse(DirectTransport.isLan("waydroid0", waydroid, waydroid.getAddress()));
        InetAddress publicAddress = InetAddress.getByName("8.8.8.8");
        assertFalse(DirectTransport.isLan("eth0", publicAddress, publicAddress.getAddress()));
    }
}
