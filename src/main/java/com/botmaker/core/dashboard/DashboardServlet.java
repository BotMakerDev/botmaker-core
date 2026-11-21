// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.dashboard;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The dashboard's page at {@link DashboardAccess#PATH}: one HTML resource, its CSS and script inline, fetching nothing
 * but its own {@link DashboardEvents} stream. A {@code GET} carrying a key is a trade, which {@link DashboardAccess}
 * answers.
 */
public final class DashboardServlet extends HttpServlet {

    /** Nothing but this core's own origin, and the page's inline style and script. */
    public static final String POLICY = "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'";

    private final transient DashboardAccess access;
    private final byte[] page;

    public DashboardServlet(DashboardAccess access) {
        this.access = access;
        try (InputStream in = DashboardServlet.class.getResourceAsStream("dashboard.html")) {
            if (in == null) throw new IllegalStateException("dashboard.html is missing from the core's jar");
            this.page = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (access.tradeIn(request, response)) return;
        response.setContentType("text/html;charset=utf-8");
        response.setHeader("Content-Security-Policy", POLICY);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.getOutputStream().write(page);
    }
}
