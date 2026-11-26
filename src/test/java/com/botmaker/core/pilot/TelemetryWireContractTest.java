// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.core.pilot.PilotMessages.RunState;
import com.botmaker.plugin.api.TraceLine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The host's half of the pilot wire contract. The pilot web app's {@code types.ts} names every field of every
 * message; this side writes records, and without this test the only thing claiming the two agree would be a
 * javadoc sentence.
 *
 * <p>Both halves read the <em>same</em> corpus — {@code pilot-wire/wire-golden.json} here,
 * {@code web/src/wire-golden.json} in the pilot repo — and both assert its SHA-256. Neither test can see
 * the other's repo, but the digest means neither copy can move alone: change the wire on one side and the
 * other side goes red until it is changed too.
 *
 * <p>The corpus's {@code telemetry.} cases are what a plugin draws over the picture
 * ({@link com.botmaker.plugin.api.run.RunView#overlay}), so the plugin that writes them asserts them.
 */
class TelemetryWireContractTest {

    private static final String GOLDEN = "/pilot-wire/wire-golden.json";

    /** The cases a plugin writes. */
    private static final String PLUGINS = "telemetry.";

    /**
     * The corpus, byte for byte. Update this together with {@code GOLDEN_SHA256} in the pilot repo's
     * {@code wire.test.ts} — and with the copy of the file itself, which must stay byte-identical.
     */
    private static final String GOLDEN_SHA256 = "0b751c506853a217a49ac73b19cefc89f1293f728943f67325c3cfcc4750874c";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode CORPUS = corpus();

    /** Every case this class asserted, so a case added to the corpus and never wired up fails the build. */
    private static final Set<String> COVERED = new HashSet<>();

    // --- The corpus, message by message ---

    @Test
    void aTraceLineCarriesWhenHowSeriousWhatWroteItWhatItSaidAndHowOften() {
        // The source line, the writer and the region stay on the host: a phone's log drawer shows the words.
        assertWire("trace.debug", PilotMessages.traceJson(new TraceLine(
                Instant.ofEpochMilli(1_700_000_000_123L), TraceLine.Level.DEBUG, "Vision", "find ore → (150,80)", 3,
                "com.botmaker.sdk.api.vision.ImageFinder", "find", "com.example.Collect", OptionalInt.of(12),
                Optional.of(new TraceLine.Region(150, 80, 32, 24)))));
    }

    @Test
    void aTraceLineOfALevelTheHostDidNotKnowGoesOutWithAnEmptyLevel() {
        assertWire("trace.unknown", PilotMessages.traceJson(new TraceLine(
                Instant.EPOCH, TraceLine.Level.UNKNOWN, "", "a line from a newer bot", 1, "", "", "",
                OptionalInt.empty(), Optional.empty())));
    }

    @Test
    void theRunStateMessageCarriesTheStateAndWhetherInputStaysOffTheHostsCursor() {
        assertWire("state.running.background", PilotMessages.stateJson(RunState.RUNNING, true, null));
        assertWire("state.paused.foreground", PilotMessages.stateJson(RunState.PAUSED, false, null));
        assertWire("state.stopped.background", PilotMessages.stateJson(RunState.STOPPED, true, null));
    }

    @Test
    void aStateMessageCarriesAReasonOnlyWhenThereIsOne() {
        // The key is omitted rather than sent as null when there is no reason — which is why the three cases
        // above and this one are separate corpus entries rather than one shape with a nullable field.
        assertWire("state.stopped.reason", PilotMessages.stateJson(RunState.STOPPED, true,
                "The background session is not showing any pixels on its X display."));
    }

    @Test
    void theVideoMessageCarriesTheCodecAndTheSurfaceRectTheStreamCovers() {
        // The rect lives here and not on each access unit: one encoder on one display means it changes only
        // when the stream does, and the client wants to hand the decoder a buffer with no header on it.
        assertWire("video.started", PilotMessages.videoJson("avc1.42E01E", 0, 0, 1920, 1080));
    }

    @Test
    void aNullCodecIsHowAStreamEnds() {
        // Sent on every way a stream can end — route change, dead encoder, last H.264 client gone — because
        // the client has to tear its decoder down and start reading JPEG frames again for any of them.
        assertWire("video.stopped", PilotMessages.videoStoppedJson());
    }

    @Test
    void onConnectTheHostSaysWhichInputKindsItTakes() {
        // A phone draws its keyboard only when "key" is here, so a host that never sends this has none.
        assertWire("input.kinds", PilotMessages.inputJson(PilotInputService.Kind.wireNames()));
    }

    @Test
    void aKeyThatWasNotSentComesBackAsANotice() {
        assertWire("notice.not-focused", PilotMessages.noticeJson(PilotInputService.Typed.NOT_FOCUSED.notice()));
    }

    @Test
    void theHeartbeatSaysNothingButItself() {
        assertWire("ping", PilotMessages.pingJson());
    }

    // --- The corpus itself ---

    @Test
    void theGoldenCorpusIsTheOneThePilotRepoHas() throws Exception {
        assertEquals(GOLDEN_SHA256, sha256(goldenBytes()),
                "pilot-wire/wire-golden.json changed. The pilot repo has a byte-identical copy and asserts the "
                        + "same digest — update both files and both constants, or the wire has silently forked.");
    }

    @AfterAll
    static void everyHostCaseInTheCorpusWasAsserted() {
        Set<String> uncovered = new TreeSet<>();
        for (Iterator<String> it = CORPUS.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            if (!name.startsWith("_") && !name.startsWith(PLUGINS) && !COVERED.contains(name)) uncovered.add(name);
        }
        assertEquals(Set.of(), uncovered, "corpus cases with no host-side assertion");
    }

    // --- Helpers ---

    private static void assertWire(String caseName, String actualJson) {
        JsonNode expected = CORPUS.get(caseName);
        assertNotNull(expected, "no such case in " + GOLDEN + ": " + caseName);
        assertEquals(expected, parse(actualJson), caseName + " no longer matches the corpus the pilot decodes");
        COVERED.add(caseName);
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new AssertionError("the serializer emitted invalid JSON: " + json, e);
        }
    }

    private static JsonNode corpus() {
        try {
            return JSON.readTree(goldenBytes());
        } catch (IOException e) {
            throw new AssertionError("cannot read " + GOLDEN, e);
        }
    }

    private static byte[] goldenBytes() throws IOException {
        try (InputStream in = TelemetryWireContractTest.class.getResourceAsStream(GOLDEN)) {
            assertNotNull(in, GOLDEN + " is missing from the test resources");
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
