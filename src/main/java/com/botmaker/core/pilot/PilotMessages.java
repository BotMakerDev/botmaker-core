// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.plugin.api.TraceLine;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Every text message the core itself sends a pilot's phone — {@code state}, {@code video}, {@code trace},
 * {@code input}, {@code notice} and {@code ping} — as one record per shape, written by one {@link ObjectMapper}.
 * What a plugin draws over the picture ({@code telemetry}) is its view's to write
 * ({@link com.botmaker.plugin.api.run.RunView#overlay}), so it has no record here.
 *
 * <p>The pilot web app's {@code types.ts} mirrors it field for field, and what keeps that true is
 * {@code TelemetryWireContractTest} plus its counterpart in the pilot repo: both read the same
 * {@code pilot-wire/wire-golden.json} corpus, and both assert its digest, so neither copy can move alone.
 *
 * <p>A null in a record is written as {@code null} — the client types {@code codec} as nullable and reads the key.
 * The one exception is the {@code state} message's {@code reason}, which is <b>omitted</b> when there is none, so
 * the three no-reason cases in the corpus stay byte-identical to what shipped before the field existed.
 */
final class PilotMessages {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PilotMessages() {}

    /** Where a run stands, as the {@code state} message's {@code run} key spells it. A closed set on both ends. */
    enum RunState {
        RUNNING("running"),
        PAUSED("paused"),
        STOPPED("stopped");

        private final String id;

        RunState(String id) {
            this.id = id;
        }

        /** The token on the wire; the phone client switches on exactly these three strings. */
        String id() {
            return id;
        }
    }

    // --- The messages ---

    /**
     * {@code runId} is the core's id of the run this message is about. It is omitted when the phone is shown the
     * open project with no run listed, which is every message the released app has ever been sent.
     */
    record State(String type, String run, boolean backgroundInput,
                 @JsonInclude(JsonInclude.Include.NON_NULL) String reason,
                 @JsonInclude(JsonInclude.Include.NON_NULL) String runId) {}

    record Video(String type, String codec, int sx, int sy, int sw, int sh,
                 @JsonInclude(JsonInclude.Include.NON_NULL) String runId) {}

    record VideoStopped(String type, String codec, @JsonInclude(JsonInclude.Include.NON_NULL) String runId) {}

    record Trace(String type, Line line, @JsonInclude(JsonInclude.Include.NON_NULL) String runId) {}

    record Input(String type, List<String> kinds) {}

    record Notice(String type, String text, @JsonInclude(JsonInclude.Include.NON_NULL) String runId) {}

    /**
     * The live runs this phone may pick from and the one it is shown. A phone's pick is its own, so each socket is
     * sent its own {@code selected}, {@code null} when there is no run to show.
     */
    record Runs(String type, String selected, List<Listed> runs) {}

    /** One run of the list: the core's id (opaque), what it plays, its state, and when it started. */
    record Listed(String id, String bot, String state, long startedMillis) {}

    record Ping(String type) {}

    /**
     * One trace line as a phone shows it: when (the bot's clock, ms), how serious ({@code TraceLine.Level}'s
     * id, {@code ""} for one the host did not know), what wrote it, what it said, and how many times.
     */
    record Line(long ts, String level, String source, String text, int count) {}

    // --- Writers ---

    /**
     * The full {@code state} message. {@code backgroundInput} tells the client whether Interact will leave the
     * host's real cursor alone, so it can warn before the user's pointer visibly gets hijacked; {@code reason}, a
     * short human sentence or {@code null}, is why the client is not receiving frames, so a blank canvas says
     * something instead of nothing.
     */
    static String stateJson(RunState run, boolean backgroundInput, String reason) {
        return stateJson(run, backgroundInput, reason, null);
    }

    /** The {@code state} message of the run {@code runId}, or of the open project's idle views when it is null. */
    static String stateJson(RunState run, boolean backgroundInput, String reason, String runId) {
        return write(new State("state", run.id(), backgroundInput, reason, runId));
    }

    /**
     * The {@code runs} message: every live run of this PC and which one this phone is shown. Sent on connect, and
     * again when the list or the phone's pick changes.
     */
    static String runsJson(String selected, List<Listed> runs) {
        return write(new Runs("runs", selected, runs));
    }

    /**
     * The {@code video} message: "binary frames from here on are H.264 access units for this codec, covering
     * this surface rect". Sent to a client that declared H.264 support, immediately before the first packet.
     *
     * <p>The rect is here rather than on each frame, which is the point of the message existing at all. A JPEG
     * frame carries a 16-byte header because its surface can change between any two frames; a video stream is
     * one encoder on one display, so the rect changes only when the stream does.
     */
    static String videoJson(String codec, int sx, int sy, int sw, int sh) {
        return videoJson(codec, sx, sy, sw, sh, null);
    }

    static String videoJson(String codec, int sx, int sy, int sw, int sh, String runId) {
        return write(new Video("video", codec, sx, sy, sw, sh, runId));
    }

    /**
     * The {@code video} message that ends a stream — a null codec. The client tears its decoder down and goes
     * back to drawing the JPEG frames that resume in its place, so this is sent on <em>every</em> way a stream
     * can end (route change, encoder death, last H.264 client leaving) and not only on a tidy shutdown.
     */
    static String videoStoppedJson() {
        return videoStoppedJson(null);
    }

    static String videoStoppedJson(String runId) {
        return write(new VideoStopped("video", null, runId));
    }

    /**
     * The {@code input} message, sent once on connect: which {@code {"cmd":"input","kind":…}} kinds this host
     * takes. A phone shows its keyboard only when {@code key} is among them, so an older host that never sends
     * this simply has no keyboard button rather than one that silently does nothing.
     */
    static String inputJson(List<String> kinds) {
        return write(new Input("input", kinds));
    }

    /** The {@code notice} message: one sentence the phone shows briefly, such as why a key was not sent. */
    static String noticeJson(String text) {
        return noticeJson(text, null);
    }

    static String noticeJson(String text, String runId) {
        return write(new Notice("notice", text, runId));
    }

    /**
     * The heartbeat. A browser cannot see the socket's own pings, and an unchanged picture is not resent, so
     * without it a phone cannot tell a dead link from a quiet game.
     */
    static String pingJson() {
        return write(new Ping("ping"));
    }

    /** The log drawer's message. The source line, the writer and the region stay on the host. */
    static String traceJson(TraceLine line) {
        return traceJson(line, null);
    }

    static String traceJson(TraceLine line, String runId) {
        return write(new Trace("trace", new Line(line.at().toEpochMilli(), line.level().id(), line.source(),
                line.text(), line.count()), runId));
    }

    private static String write(Object message) {
        try {
            return JSON.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            // Every message here is records of strings and numbers; a failure is a bug in this class.
            throw new IllegalStateException("pilot message did not serialize: " + message, e);
        }
    }
}
