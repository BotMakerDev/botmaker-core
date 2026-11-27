// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot.transport;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;

/**
 * The two transports with no tunnel: the server binds one of this computer's own addresses and the phone
 * connects to it over plain {@code http}/{@code ws}.
 *
 * <ul>
 *   <li>{@link TransportKind#TAILNET}: the Tailscale address ({@code 100.64.0.0/10}). The tailnet encrypts the
 *       traffic and only the account's own devices reach it.</li>
 *   <li>{@link TransportKind#LAN}: the local network address. Anyone on that network can reach the port, so
 *       the token is the only lock and a client says so.</li>
 * </ul>
 *
 * <p>Neither binds {@code 0.0.0.0} any more. The pilot bound every interface when Tailscale was down, which put
 * it on whatever else the computer was connected to as well.
 */
final class DirectTransport implements PilotTransport {

    /** Interfaces that are not the local network: tunnels, containers, virtual machines, Android containers. */
    private static final String[] VIRTUAL = {"tailscale", "docker", "br-", "veth", "virbr", "tun", "wg", "podman",
            "waydroid", "lxc", "vboxnet", "vmnet", "cni", "flannel"};

    private final TransportKind kind;
    private String host;

    DirectTransport(TransportKind kind) {
        this.kind = kind;
    }

    @Override
    public TransportKind kind() {
        return kind;
    }

    @Override
    public Availability available() {
        host = kind == TransportKind.TAILNET ? tailscaleAddress() : lanAddress();
        if (host != null) return Availability.yes();
        return kind == TransportKind.TAILNET
                ? Availability.no("Tailscale isn't connected on this computer.",
                        "Start Tailscale and sign in (tailscale up), or pick another way.")
                : Availability.no("This computer has no local network address.",
                        "Connect it to the same Wi-Fi or network as the phone.");
    }

    @Override
    public String bindHost() {
        if (host == null) available();
        return host;
    }

    @Override
    public Opened open(int port) {
        String bound = bindHost();
        return bound == null ? Opened.failed(available().reason()) : Opened.at("http://" + bound + ":" + port);
    }

    @Override
    public void close() {
        // Nothing was opened beyond the server's own bind, which the server closes.
    }

    /** This computer's Tailscale IPv4 address, or {@code null} when the tunnel is not up. */
    static String tailscaleAddress() {
        return firstAddress(true);
    }

    /**
     * This computer's local network IPv4: the source address of its default route when that is a private one
     * on a physical interface, else the first such address. The route comes first because a Waydroid or Docker
     * bridge also carries a private address, and may be listed before the Wi-Fi.
     */
    static String lanAddress() {
        try (DatagramSocket probe = new DatagramSocket()) {
            // A UDP connect sends nothing; it only asks the kernel which source address the route would use.
            probe.connect(InetAddress.getByName("1.1.1.1"), 9);
            InetAddress local = probe.getLocalAddress();
            NetworkInterface nic = NetworkInterface.getByInetAddress(local);
            if (local instanceof Inet4Address && nic != null && isLan(nic.getName(), local, local.getAddress())) {
                return local.getHostAddress();
            }
        } catch (Exception ignored) {
            // No default route (a network without internet): the interface scan below.
        }
        return firstAddress(false);
    }

    private static String firstAddress(boolean tailscale) {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (InetAddress addr : Collections.list(nic.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address)) continue;
                    byte[] b = addr.getAddress();
                    if (tailscale ? isTailscale(b) : isLan(nic.getName(), addr, b)) return addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {
            // No interface list means no address to offer, which the caller reports.
        }
        return null;
    }

    /** {@code 100.64.0.0/10}: the carrier-grade NAT range Tailscale hands out. */
    static boolean isTailscale(byte[] ipv4) {
        return (ipv4[0] & 0xFF) == 100 && (ipv4[1] & 0xC0) == 64;
    }

    /**
     * Whether {@code addr} on the interface named {@code nic} is a local network address a phone on the same
     * Wi-Fi could reach: private range, and not Tailscale, Docker, libvirt or a VPN's virtual interface.
     */
    static boolean isLan(String nic, InetAddress addr, byte[] ipv4) {
        String name = nic == null ? "" : nic.toLowerCase(Locale.ROOT);
        for (String virtual : VIRTUAL) {
            if (name.startsWith(virtual)) return false;
        }
        return addr.isSiteLocalAddress() && !isTailscale(ipv4);
    }
}
