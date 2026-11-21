// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.dashboard;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Who may open the core's dashboard (spec {@code 2026-11-21-core-dashboard-design.md} § Access). A browser cannot send
 * the bearer token, so a client that holds it asks {@link #KEY_PATH} for a link carrying a one-time key; the page
 * trades the key, once and within {@link #KEY_LIFE}, for a cookie that opens {@link #PATH} and nothing else. A cookie
 * lives as long as this core.
 */
public final class DashboardAccess {

    public static final String PATH = "/dashboard";
    /** Where a client with the bearer token asks for a link, by {@code POST}. */
    public static final String KEY_PATH = "/dashboard-key";
    /** The cookie's name before its port: a browser keeps one cookie jar for every port of {@code 127.0.0.1}. */
    public static final String COOKIE = "bm-dashboard";
    public static final Duration KEY_LIFE = Duration.ofSeconds(60);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Clock clock;
    private final Map<String, Instant> keys = new ConcurrentHashMap<>();
    private final Set<String> cookies = ConcurrentHashMap.newKeySet();

    public DashboardAccess(Clock clock) {
        this.clock = clock;
    }

    /** A new one-time key: 32 random bytes in hex. */
    public String newKey() {
        Instant now = forgetExpired();
        String key = random();
        keys.put(key, now.plus(KEY_LIFE));
        return key;
    }

    /** The cookie {@code key} opens, once and before it expires; empty for any other key. */
    public Optional<String> trade(String key) {
        forgetExpired();
        if (key == null) return Optional.empty();
        Instant expiry = keys.remove(key);
        if (expiry == null || !clock.instant().isBefore(expiry)) return Optional.empty();
        String cookie = random();
        cookies.add(cookie);
        return Optional.of(cookie);
    }

    /**
     * Whether {@code request} may pass without the bearer token: a dashboard path with a live cookie, or a {@code GET}
     * of the page itself carrying a key, which {@link #tradeIn} then checks.
     */
    public boolean admits(HttpServletRequest request) {
        return admits(path(request), request.getLocalPort(), request.getHeader("Cookie"), keyed(request));
    }

    boolean admits(String path, int port, String cookieHeader, boolean keyed) {
        if (keyed && PATH.equals(path)) return true;
        return isDashboard(path) && cookies(cookieHeader, cookieName(port)).anyMatch(cookies::contains);
    }

    /** This core's cookie name on {@code port}, so opening another core's dashboard leaves this one's cookie. */
    public static String cookieName(int port) {
        return COOKIE + "-" + port;
    }

    /**
     * Answers a {@code GET} of the page with a {@code ?key=}: the cookie and a redirect to the bare page, or 401 for a
     * key spent, expired or unknown. Returns whether it answered; otherwise the caller serves the page. Only a
     * {@code GET} spends a key: a {@code HEAD} that checks the link does not.
     */
    public boolean tradeIn(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!keyed(request)) return false;
        Optional<String> cookie = trade(request.getParameter("key"));
        if (cookie.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return true;
        }
        response.setHeader("Set-Cookie", cookieName(request.getLocalPort()) + "=" + cookie.get() + "; Path=" + PATH
                + "; HttpOnly; SameSite=Strict");
        response.setHeader("Location", PATH);
        response.setStatus(HttpServletResponse.SC_SEE_OTHER);
        return true;
    }

    /** Whether {@code request} is for the page or below it, which keep no core up. */
    public static boolean isDashboard(HttpServletRequest request) {
        return isDashboard(path(request));
    }

    private static boolean isDashboard(String path) {
        return PATH.equals(path) || path.startsWith(PATH + "/");
    }

    /** The path the context routes by, decoded and with its dot segments resolved, unlike the request URI. */
    private static String path(HttpServletRequest request) {
        String info = request.getPathInfo();
        return request.getServletPath() + (info == null ? "" : info);
    }

    private static boolean keyed(HttpServletRequest request) {
        return "GET".equals(request.getMethod()) && request.getParameter("key") != null;
    }

    /** Every value {@code header} gives {@code name}: a browser may send a stale one beside the live one. */
    private static Stream<String> cookies(String header, String name) {
        if (header == null) return Stream.empty();
        return Arrays.stream(header.split(";")).map(String::strip).filter(pair -> pair.startsWith(name + "="))
                .map(pair -> pair.substring(name.length() + 1));
    }

    private Instant forgetExpired() {
        Instant now = clock.instant();
        keys.values().removeIf(expiry -> !now.isBefore(expiry));
        return now;
    }

    private static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
