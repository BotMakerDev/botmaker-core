// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DashboardAccessTest {

    /** A clock a test moves by hand. */
    private static final class Moved extends Clock {
        private Instant now = Instant.parse("2026-11-21T12:00:00Z");

        void move(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final Moved clock = new Moved();
    private final DashboardAccess access = new DashboardAccess(clock);

    @Test
    void keyWorksOnce() {
        String key = access.newKey();
        assertEquals(64, key.length());
        Optional<String> cookie = access.trade(key);
        assertTrue(cookie.isPresent());
        assertNotEquals(key, cookie.get());
        assertTrue(access.trade(key).isEmpty());
        assertTrue(access.trade("").isEmpty());
    }

    @Test
    void keyExpiresAfter60s() {
        String late = access.newKey();
        String inTime = access.newKey();
        clock.move(Duration.ofSeconds(59));
        assertTrue(access.trade(inTime).isPresent());
        clock.move(Duration.ofSeconds(2));
        assertTrue(access.trade(late).isEmpty());
    }

    private static final int PORT = 41000;

    @Test
    void cookieAdmitsOnlyDashboard() {
        String cookie = access.trade(access.newKey()).orElseThrow();
        String name = DashboardAccess.cookieName(PORT);
        String header = "other=1; " + name + "=" + cookie;
        assertTrue(access.admits("/dashboard", PORT, header, false));
        assertTrue(access.admits("/dashboard/events", PORT, header, false));
        assertFalse(access.admits("/mcp", PORT, header, false));
        assertFalse(access.admits("/core", PORT, header, false));
        assertFalse(access.admits(DashboardAccess.KEY_PATH, PORT, header, false));
        assertFalse(access.admits("/dashboard", PORT, name + "=forged", false));
        assertFalse(access.admits("/dashboard", PORT, null, false));
        // A key is checked by the page that trades it; only the page itself lets one through.
        assertTrue(access.admits("/dashboard", PORT, null, true));
        assertFalse(access.admits("/dashboard/events", PORT, null, true));
        assertFalse(access.admits("/mcp", PORT, null, true));
    }

    @Test
    void aStaleCookieBesideTheLiveOneStillAdmits() {
        String cookie = access.trade(access.newKey()).orElseThrow();
        String name = DashboardAccess.cookieName(PORT);
        assertTrue(access.admits("/dashboard", PORT, name + "=stale; " + name + "=" + cookie, false));
    }

    @Test
    void anotherCoresCookieIsNotThisOnes() {
        String cookie = access.trade(access.newKey()).orElseThrow();
        assertNotEquals(DashboardAccess.cookieName(PORT), DashboardAccess.cookieName(PORT + 1));
        assertFalse(access.admits("/dashboard", PORT, DashboardAccess.cookieName(PORT + 1) + "=" + cookie, false));
    }
}
