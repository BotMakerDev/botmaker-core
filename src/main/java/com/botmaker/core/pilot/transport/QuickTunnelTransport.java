// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot.transport;

import com.botmaker.shared.Executables;
import com.botmaker.shared.platform.Os;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * A Cloudflare quick tunnel: {@code cloudflared tunnel --url http://127.0.0.1:<port>} asks Cloudflare for a
 * random {@code https://…trycloudflare.com} address and forwards it, WebSockets included, to the loopback
 * server. No account, no domain, nothing on the phone.
 *
 * <p>What it costs: the address is new at every start, so a phone pairs again after a restart; and it is on the
 * public internet, so the token is the only lock, exactly as with Funnel. Cloudflare offers quick tunnels for
 * testing, with no uptime promise.
 *
 * <p>The child process is this transport's: {@link #close()} stops it, and a shutdown hook stops it if the core
 * exits first, so a tunnel never outlives the pilot it fronted.
 */
public final class QuickTunnelTransport implements PilotTransport {

    /** The address cloudflared prints once the tunnel exists. {@code api.trycloudflare.com} is its own API. */
    private static final Pattern URL = Pattern.compile("https://(?!api\\.)[a-z0-9-]+\\.trycloudflare\\.com");

    /** The line cloudflared prints once an edge connection is up, after which the address answers. */
    private static final String REGISTERED = "Registered tunnel connection";

    /** The cloudflared argv, looked up at each use: one installed after this transport was made is found. */
    private final Supplier<List<String>> command;
    private final long urlTimeoutMs;
    private final long readyTimeoutMs;

    private volatile Process process;
    private Thread shutdownHook;
    private volatile CompletableFuture<Void> lost = new CompletableFuture<>();

    public QuickTunnelTransport() {
        this(() -> List.of(Executables.resolve("cloudflared")), 30_000, 15_000);
    }

    /** For tests: {@code command} stands in for {@code cloudflared}, and is given the same arguments. */
    QuickTunnelTransport(List<String> command, long urlTimeoutMs, long readyTimeoutMs) {
        this(() -> command, urlTimeoutMs, readyTimeoutMs);
    }

    private QuickTunnelTransport(Supplier<List<String>> command, long urlTimeoutMs, long readyTimeoutMs) {
        this.command = command;
        this.urlTimeoutMs = urlTimeoutMs;
        this.readyTimeoutMs = readyTimeoutMs;
    }

    @Override
    public TransportKind kind() {
        return TransportKind.QUICK_TUNNEL;
    }

    @Override
    public Availability available() {
        try {
            List<String> version = new ArrayList<>(command.get());
            version.add("--version");
            Process p = new ProcessBuilder(version).redirectErrorStream(true).start();
            p.getOutputStream().close();
            if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) return Availability.yes();
            p.destroyForcibly();
        } catch (Exception ignored) {
            // Not on the PATH: the answer below.
        }
        return Availability.no("cloudflared isn't installed on this computer.", installHint());
    }

    @Override
    public String bindHost() {
        return "127.0.0.1";
    }

    @Override
    public synchronized Opened open(int port) {
        close();
        List<String> cmd = new ArrayList<>(command.get());
        cmd.addAll(List.of("tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:" + port));
        StringBuffer log = new StringBuffer();
        try {
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            process.getOutputStream().close();
        } catch (Exception e) {
            process = null;
            return Opened.failed("Couldn't start cloudflared: " + e.getMessage());
        }
        pump(process.getInputStream(), log);
        shutdownHook = new Thread(this::stopProcess, "quick-tunnel-stop");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        Optional<String> url = waitFor(() -> parseUrl(log), urlTimeoutMs);
        if (url.isEmpty()) {
            String tail = lastLines(log.toString(), 3);
            close();
            return Opened.failed("cloudflared gave no address" + (tail.isEmpty() ? "." : ": " + tail));
        }
        // The address is printed before the edge connection is up; a phone opening it in that gap gets an error
        // page. Waiting for the registration is best-effort: without it the address still works a moment later.
        waitFor(() -> log.indexOf(REGISTERED) >= 0 ? Optional.of(true) : Optional.empty(), readyTimeoutMs);
        watch(process);
        return Opened.at(url.get());
    }

    /** Completes {@link #lost} when {@code running} ends without {@link #close} having stopped it. */
    private void watch(Process running) {
        CompletableFuture<Void> gone = new CompletableFuture<>();
        lost = gone;
        running.onExit().thenRun(() -> {
            // stopProcess drops the field before it destroys, so a deliberate stop is not a loss.
            if (process == running) gone.complete(null);
        });
    }

    @Override
    public CompletionStage<Void> lost() {
        return lost;
    }

    @Override
    public synchronized void close() {
        stopProcess();
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // Already shutting down: the hook is running or has run.
            }
            shutdownHook = null;
        }
    }

    private void stopProcess() {
        Process p = process;
        process = null;
        if (p == null) return;
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
        try {
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /** The tunnel address in cloudflared's output so far, if it has printed one. */
    static Optional<String> parseUrl(CharSequence output) {
        Matcher m = URL.matcher(output);
        return m.find() ? Optional.of(m.group()) : Optional.empty();
    }

    /** How to install cloudflared on this operating system. */
    static String installHint() {
        Os os = Os.current();
        if (os.isWindows()) return "Install it with: winget install --id Cloudflare.cloudflared";
        if (os.isMac()) return "Install it with: brew install cloudflared";
        return "Install it from Cloudflare's package repository (pkg.cloudflare.com) or its GitHub releases "
                + "(github.com/cloudflare/cloudflared), or with: brew install cloudflared";
    }

    /** Polls {@code probe} until it answers, the child exits, or {@code timeoutMs} passes. */
    private <T> Optional<T> waitFor(java.util.function.Supplier<Optional<T>> probe, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            Optional<T> found = probe.get();
            if (found.isPresent()) return found;
            Process p = process;
            if (p == null || !p.isAlive()) return probe.get();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return probe.get();
    }

    private static String lastLines(String text, int n) {
        String[] lines = text.strip().split("\n");
        return String.join(" ", java.util.Arrays.copyOfRange(lines, Math.max(0, lines.length - n), lines.length))
                .strip();
    }

    /** Reads the child's output into {@code sink} for as long as it runs, so its pipe never fills and blocks it. */
    private static void pump(InputStream in, StringBuffer sink) {
        Thread t = new Thread(() -> {
            try (var br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sink.append(line).append('\n');
                    // A tunnel logs for as long as it runs; only the start is read, so keep the tail bounded.
                    if (sink.length() > 64 * 1024) sink.delete(0, sink.length() - 16 * 1024);
                }
            } catch (Exception ignored) {
                // The process ended.
            }
        }, "quick-tunnel-output");
        t.setDaemon(true);
        t.start();
    }
}
