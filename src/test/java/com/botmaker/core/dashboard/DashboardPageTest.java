// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.dashboard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashboardPageTest {

    private static final List<String> CARDS = List.of("Core", "Clients", "Project", "Runs", "Companions", "Plugins",
            "MCP calls", "Last edit");

    @Test
    void pageNamesEveryCardAndFetchesNothingOutside() throws IOException {
        String page = page();
        for (String card : CARDS) {
            assertTrue(page.contains("data-card=\"" + card + "\""), "no card " + card);
        }
        assertTrue(page.contains("EventSource(\"" + DashboardEvents.PATH + "\")"), "the page opens no stream");
        for (String outside : List.of("http://", "https://", "src=", "href=", "url(", "@import", "fetch(")) {
            assertFalse(page.contains(outside), "the page may reach outside: " + outside);
        }
    }

    @Test
    void endedStreamSaysSoAndNeverRetries() throws IOException {
        String page = page();
        assertTrue(page.contains("Core ended — open the dashboard again from Studio or core_status"), page);
        assertTrue(page.contains("stream.addEventListener(\"ended\", ended)"), "ended is not heard");
        assertTrue(page.contains("stream.onerror = ended"), "an error would let EventSource retry");
        assertTrue(page.contains("stream.close()"), "the stream is never closed, so it retries");
        assertTrue(page.contains("prefers-color-scheme: dark"), "no dark colours");
    }

    private static String page() throws IOException {
        try (InputStream in = DashboardPageTest.class.getResourceAsStream("dashboard.html")) {
            assertNotNull(in, "no dashboard.html beside DashboardServlet");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
