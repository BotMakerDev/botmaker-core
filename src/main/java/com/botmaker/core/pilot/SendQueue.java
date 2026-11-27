// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import org.eclipse.jetty.websocket.api.Callback;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * One phone's outgoing messages: one write in flight at a time, in the order they were offered, and never
 * blocking the thread that offers them. A blocking send to a phone that stopped reading would hold the frame
 * loop, the run's telemetry and every other phone with it.
 *
 * <p>Both bounds are the point. Frames over the caller's limit are refused, so a slow link shows fewer pictures
 * rather than older ones. Text is not droppable (the run state and the video start are in it), so a phone more
 * than {@link #MAX_TEXTS} messages behind is given up on; it reconnects.
 */
final class SendQueue {

    /** The socket under the queue. Both writes return at once and report through {@code done}. */
    interface Link {
        void text(String text, Callback done);

        void binary(ByteBuffer bytes, Callback done);

        /** The phone is gone or hopelessly behind: drop it. */
        void giveUp();
    }

    static final int MAX_TEXTS = 500;

    private final Link link;
    private final ArrayDeque<Object> waiting = new ArrayDeque<>();
    private int texts;
    /** Frames offered and not yet written, the one in flight included. */
    private int frames;
    private boolean sending;
    private boolean dead;

    SendQueue(Link link) {
        this.link = link;
    }

    void text(String text) {
        boolean overflow;
        synchronized (this) {
            if (dead) return;
            overflow = texts >= MAX_TEXTS;
            if (!overflow) {
                texts++;
                waiting.add(text);
            }
        }
        if (overflow) die();
        else pump();
    }

    /**
     * Queues a text message unless {@code limit} texts are already waiting, so a message that can be missed (a
     * thumbnail) never grows the queue towards the overflow that ends the connection.
     *
     * @return false when it was refused
     */
    boolean textUnlessBusy(String text, int limit) {
        synchronized (this) {
            if (dead || texts >= limit) return false;
            texts++;
            waiting.add(text);
        }
        pump();
        return true;
    }

    /**
     * Queues a binary message unless {@code limit} are already waiting.
     *
     * @return false when it was refused: the phone is that far behind, or gone
     */
    boolean frame(byte[] payload, int limit) {
        synchronized (this) {
            if (dead || frames >= limit) return false;
            frames++;
            waiting.add(payload);
        }
        pump();
        return true;
    }

    private void pump() {
        Object next;
        synchronized (this) {
            if (dead || sending) return;
            next = waiting.poll();
            if (next == null) return;
            sending = true;
        }
        Callback done = new Callback() {
            @Override
            public void succeed() {
                synchronized (SendQueue.this) {
                    sending = false;
                    if (next instanceof String) texts--;
                    else frames--;
                }
                pump();
            }

            @Override
            public void fail(Throwable x) {
                die();
            }
        };
        try {
            if (next instanceof String text) link.text(text, done);
            else link.binary(ByteBuffer.wrap((byte[]) next), done);
        } catch (Exception e) {
            die();
        }
    }

    private void die() {
        synchronized (this) {
            if (dead) return;
            dead = true;
            waiting.clear();
        }
        link.giveUp();
    }
}
