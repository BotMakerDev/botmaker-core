// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.botmaker.core.dashboard.DashboardFeed;
import com.botmaker.core.dashboard.Inside;
import com.botmaker.core.edit.EditResult;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreWatchTest {

    private final CoreWatch watch = new CoreWatch(Clock.systemUTC());

    @Test
    void anEditIsSeenWithItsKindAndOutcome() {
        watch.attached("ch1");
        Request together = new Request.Together(7, "Tidy", List.of());
        watch.received("ch1", together);
        watch.answered("ch1", together, new EditResult.Refused("no"), System.nanoTime());
        List<DashboardFeed.Seen> kept = watch.feed().kept();
        assertEquals(2, kept.size());
        DashboardFeed.EditSeen edit = (DashboardFeed.EditSeen) kept.get(1);
        assertEquals("Together", edit.kind());
        assertFalse(edit.accepted());
        assertEquals("no", edit.label());
        assertTrue(CoreWatch.isEdit(together));
    }

    @Test
    void insideWithNoServerNorProjectStillSaysTheProcess() {
        watch.attached("ch1");
        watch.received("ch1", new Request.WatchCalls(1));
        Inside inside = watch.inside(null, null);
        assertTrue(inside.pid() > 0);
        assertTrue(inside.threads() > 0);
        assertTrue(inside.project().isEmpty());
        assertEquals(1, inside.clients().size());
        assertTrue(inside.clients().getFirst().watchingCalls());
        assertEquals(1, inside.clients().getFirst().requests());
    }

    @Test
    void aChannelThatLeftIsSeenGoing() {
        watch.attached("ch1");
        watch.left("ch1");
        assertTrue(watch.inside(null, null).clients().isEmpty());
        assertFalse(((DashboardFeed.ClientSeen) watch.feed().kept().get(1)).opened());
    }

    @Test
    void threadPrefixes() {
        assertEquals("core-idle", CoreWatch.prefix("core-idle"));
        assertEquals("qtp", CoreWatch.prefix("qtp1234-56"));
        assertEquals("ForkJoinPool", CoreWatch.prefix("ForkJoinPool-1-worker-3"));
        assertEquals("(unnamed)", CoreWatch.prefix(""));
    }
}
