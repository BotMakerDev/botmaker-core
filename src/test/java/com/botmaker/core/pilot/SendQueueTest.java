// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import org.eclipse.jetty.websocket.api.Callback;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SendQueueTest {

    /** A socket that writes only when told to, so "stalled" is just never completing. */
    private static final class HeldLink implements SendQueue.Link {
        final List<String> written = new ArrayList<>();
        Callback pending;
        int gaveUp;

        @Override public void text(String text, Callback done) { written.add(text); pending = done; }
        @Override public void binary(ByteBuffer bytes, Callback done) { written.add("binary"); pending = done; }
        @Override public void giveUp() { gaveUp++; }

        void complete() {
            Callback done = pending;
            pending = null;
            done.succeed();
        }
    }

    @Test
    void oneWriteAtATimeInTheOrderOffered() {
        HeldLink link = new HeldLink();
        SendQueue queue = new SendQueue(link);

        queue.text("video");
        assertTrue(queue.frame(new byte[1], 1));
        queue.text("state");
        assertEquals(List.of("video"), link.written, "nothing overtakes the write in flight");

        link.complete();
        link.complete();
        assertEquals(List.of("video", "binary", "state"), link.written);
    }

    @Test
    void framesPastTheLimitAreRefusedUntilOneIsWritten() {
        HeldLink link = new HeldLink();
        SendQueue queue = new SendQueue(link);

        assertTrue(queue.frame(new byte[1], 1));
        assertFalse(queue.frame(new byte[1], 1));
        link.complete();
        assertTrue(queue.frame(new byte[1], 1));
    }

    @Test
    void aPhoneTooFarBehindIsGivenUpOnce() {
        HeldLink link = new HeldLink();
        SendQueue queue = new SendQueue(link);

        for (int i = 0; i <= SendQueue.MAX_TEXTS + 5; i++) queue.text("line");

        assertEquals(1, link.gaveUp);
        assertFalse(queue.frame(new byte[1], 1));
    }
}
