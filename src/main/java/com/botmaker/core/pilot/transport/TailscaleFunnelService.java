// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot.transport;

import com.botmaker.shared.Diag;
import com.botmaker.shared.Executables;
import com.botmaker.shared.platform.Os;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper over the local {@code tailscale} CLI that exposes the pilot server's loopback HTTP port to
 * the public internet as {@code https://<machine>.<tailnet>.ts.net} via <b>Tailscale Funnel</b>, with a valid
 * Let's Encrypt certificate managed by Tailscale.
 *
 * <p>Why Funnel (not tailnet-only "serve"): the phone reaching the pilot needs <em>nothing</em> installed —
 * no Tailscale, no VPN — it just opens the public HTTPS URL. Tailscale terminates TLS and proxies to
 * {@code http://127.0.0.1:<port>}, so {@code PilotServer} can stay plain-HTTP on loopback and the
 * {@code ?token=} query param is the only guard (hence it must stay secret).
 *
 * <p>All methods are best-effort and never throw: a missing CLI, a Funnel not enabled in the tailnet ACL, or a
 * logged-out node all surface as {@link Result#ok()}{@code == false} with the CLI's stderr in
 * {@link Result#error()} so the UI can tell the user <em>why</em>.
 *
 * <p>Prerequisites (one-time, machine/admin side; documented in botmaker-pilot/README.md): Tailscale installed
 * and logged in, HTTPS certificates enabled for the tailnet, and the {@code funnel} node-attribute granted in
 * the tailnet ACL policy. Without the ACL grant {@code tailscale funnel} errors and we fall back to a direct
 * bind.
 */
public final class TailscaleFunnelService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Outcome of {@link #enable(int)}: the public {@code https://…} base on success, else the CLI error. */
    public record Result(boolean ok, String publicBase, String error) {
        static Result ok(String publicBase) { return new Result(true, publicBase, null); }
        static Result fail(String error) { return new Result(false, null, error); }
    }

    /** {@code true} if the {@code tailscale} CLI is present and responds to {@code version}. */
    public boolean isAvailable() {
        return run(List.of("version"), 5).exit == 0;
    }

    /** What {@code tailscale status --json} prints, or empty when the CLI is missing, fails or says nothing. */
    public static Optional<String> statusJson() {
        Exec e = run(List.of("status", "--json"), 8);
        return e.exit != 0 || e.out.isBlank() ? Optional.empty() : Optional.of(e.out);
    }

    /**
     * {@code true} if the node is logged in and the tailscaled backend is up — i.e. {@code BackendState} is
     * {@code "Running"} in {@code tailscale status --json}. {@code "NeedsLogin"}/{@code "Stopped"}/CLI-missing
     * all return {@code false}. This is the "installed &amp; logged in" gate in the setup wizard.
     */
    public boolean isLoggedIn() {
        Optional<String> status = statusJson();
        if (status.isEmpty()) return false;
        try {
            return "Running".equals(JSON.readTree(status.get()).path("BackendState").asText(null));
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * The node's fully-qualified tailnet DNS name (e.g. {@code machine.tailnet-1234.ts.net}) parsed from
     * {@code tailscale status --json} → {@code Self.DNSName} (trailing dot trimmed), or empty if the CLI is
     * unavailable / the node is logged out / the field is missing.
     */
    public Optional<String> dnsName() {
        Optional<String> status = statusJson();
        if (status.isEmpty()) return Optional.empty();
        try {
            JsonNode name = JSON.readTree(status.get()).path("Self").path("DNSName");
            if (name.isMissingNode() || !name.isTextual()) return Optional.empty();
            String dns = name.asText().trim();
            if (dns.endsWith(".")) dns = dns.substring(0, dns.length() - 1);
            return dns.isBlank() ? Optional.empty() : Optional.of(dns);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    /**
     * Exposes {@code http://127.0.0.1:<localPort>} publicly on the tailnet's {@code :443} Funnel. On success
     * returns the {@code https://<dnsName>} base (no trailing slash); on failure returns the CLI stderr (e.g.
     * "Funnel is not enabled on your tailnet").
     */
    public Result enable(int localPort) {
        Optional<String> dns = dnsName();
        if (dns.isEmpty()) return Result.fail("Tailscale node has no DNS name (logged out?).");
        // `--bg` detaches the funnel so it keeps running after this CLI call returns; 443 → localhost:<port>.
        // A properly enabled+authorized funnel returns in well under a second. When Funnel isn't enabled on the
        // tailnet, `tailscale funnel` prints the reason and then blocks polling — so abort the moment a known
        // failure line appears (fail-fast markers) rather than waiting out the timeout, keeping the fallback
        // to a direct bind near-instant.
        Exec e = run(List.of("funnel", "--bg", String.valueOf(localPort)), 12,
                "is not enabled on your tailnet", "Access denied", "not logged in");
        if (e.exit != 0) {
            String msg = e.err.isBlank() ? e.out : e.err;
            return Result.fail(msg.isBlank() ? "tailscale funnel exited " + e.exit : msg.trim());
        }
        return Result.ok("https://" + dns.get());
    }

    /**
     * Tears down the Funnel started by {@link #enable(int)}: the web handler on {@code :443} and the Funnel on
     * it, and nothing else the user serves. Best-effort, never throws.
     *
     * <p>Not {@code tailscale funnel reset}, which clears <em>every</em> serve and Funnel on the machine, the
     * user's own included. {@code tailscale funnel --https=443 off} is not the narrow form:
     * on 1.102 it stops at the "Funnel is not enabled" gate and blocks like {@code enable} does.
     * {@code tailscale serve --https=443 off} passes no such gate and returns at once; a handler that is
     * already gone is reported and counts as done. No {@code reset} fallback: this runs as a project closes,
     * where nobody would see that it cleared their other config.
     *
     * @return whether nothing of ours is served any more
     */
    public boolean disable() {
        Exec e = run(OFF, 10);
        if (turnedOff(e.exit, e.err + e.out)) return true;
        Diag.warn("Pilot", "Could not turn the Remote Pilot's Funnel off: " + (e.err + e.out).trim()
                + " — run `" + String.join(" ", command(OFF)) + "` to do it by hand.");
        return false;
    }

    /** The port {@code tailscale funnel --bg <port>} serves on when no {@code --https} is given. */
    static final int HTTPS_PORT = 443;

    /** Turns off only the web handler {@link #enable} made, and the Funnel on it: the CLI's arguments. */
    static final List<String> OFF = List.of("serve", "--https=" + HTTPS_PORT, "off");

    /** Whether {@link #OFF} left nothing of ours: it succeeded, or there was no handler to remove. */
    static boolean turnedOff(int exit, String output) {
        return exit == 0 || (output != null && output.contains("handler does not exist"));
    }

    // --- process plumbing ---

    /**
     * The Mac app's own CLI: the standalone and App Store Tailscale put no {@code tailscale} on {@code PATH},
     * and the binary inside the bundle answers the same commands.
     */
    static final String MAC_APP_CLI = "/Applications/Tailscale.app/Contents/MacOS/Tailscale";

    /**
     * The {@code tailscale} to run. On macOS the app's bundled CLI when the app is installed, since it talks to
     * the app's own daemon, where a Homebrew {@code tailscale} would ask a {@code tailscaled} that is not the
     * one logged in. Otherwise {@link Executables#resolve} (Homebrew's bin included on macOS), else the name.
     */
    public static String cli() {
        if (Os.current().isMac() && Files.isExecutable(Path.of(MAC_APP_CLI))) return MAC_APP_CLI;
        return Executables.resolve("tailscale");
    }

    /** The full argv for the CLI's {@code args}. */
    static List<String> command(List<String> args) {
        List<String> argv = new ArrayList<>();
        argv.add(cli());
        argv.addAll(args);
        return argv;
    }

    private record Exec(int exit, String out, String err) {}

    /**
     * Runs the CLI with {@code args}, capturing stdout/stderr, bounded three ways: normal exit, any {@code failFast} marker
     * appearing in the output (kill immediately — for commands that print a reason then block), or the
     * timeout. The timeout/kill is enforced by polling + {@code destroyForcibly}, never by reading a stream
     * (an inline read would itself hang on a blocking child).
     */
    private static Exec run(List<String> args, int timeoutSeconds, String... failFast) {
        Process p = null;
        try {
            p = new ProcessBuilder(command(args)).start();
            // Close the child's stdin so a command that would prompt gets EOF instead of blocking on input.
            p.getOutputStream().close();
            StringBuffer out = new StringBuffer();   // thread-safe: pumped by one thread, polled by this one
            StringBuffer err = new StringBuffer();
            Thread outPump = pump(p.getInputStream(), out, "tailscale-stdout");
            Thread errPump = pump(p.getErrorStream(), err, "tailscale-stderr");

            long deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
            boolean exited = false;
            boolean failedFast = false;
            while (System.nanoTime() < deadline) {
                if (!p.isAlive()) { exited = true; break; }
                if (containsAny(out, err, failFast)) { failedFast = true; break; }
                Thread.sleep(100);
            }
            if (!exited) {
                p.destroyForcibly();
                p.waitFor(3, TimeUnit.SECONDS);
            }
            outPump.join(1000);
            errPump.join(1000);
            if (exited) return new Exec(p.exitValue(), out.toString(), err.toString());

            // Killed early (marker) or timed out: report a non-zero exit with the captured reason.
            String captured = (out + "\n" + err).trim();
            String reason = failedFast
                    ? (captured.isBlank() ? "funnel unavailable" : captured)
                    : (captured.isBlank() ? "" : captured + " — ") + "timed out after " + timeoutSeconds + "s";
            return new Exec(-1, out.toString(), reason);
        } catch (Exception ex) {
            if (p != null) p.destroyForcibly();
            // CLI missing (IOException) or interrupted → treat as unavailable.
            return new Exec(-1, "", ex.getMessage() == null ? ex.toString() : ex.getMessage());
        }
    }

    private static boolean containsAny(CharSequence out, CharSequence err, String[] markers) {
        if (markers.length == 0) return false;
        String o = out.toString(), e = err.toString();
        for (String m : markers) {
            if (o.contains(m) || e.contains(m)) return true;
        }
        return false;
    }

    /** Reads {@code in} line-by-line into {@code sink} so partial output is visible to the fail-fast poll. */
    private static Thread pump(InputStream in, StringBuffer sink, String name) {
        Thread t = new Thread(() -> {
            try (var br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sink.append(line).append('\n');
            } catch (Exception ignored) {
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }
}
