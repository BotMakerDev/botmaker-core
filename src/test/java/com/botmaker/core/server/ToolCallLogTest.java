package com.botmaker.core.server;

import com.botmaker.core.CoreJson;
import com.botmaker.core.assist.PluginTools;
import com.botmaker.core.assist.ReplyMeta;
import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.event.CoreEvent.ToolCallEnded;
import com.botmaker.core.event.CoreEvent.ToolCallEnded.Changed;
import com.botmaker.core.event.CoreEvent.ToolCallEnded.Extras;
import com.botmaker.core.event.CoreEvent.ToolCallEnded.Outcome;
import com.botmaker.core.event.CoreEvent.ToolCallEnded.Part;
import com.botmaker.core.event.CoreEvent.ToolCallEnded.Point;
import com.botmaker.core.event.CoreEvent.ToolCallProgress;
import com.botmaker.core.event.CoreEvent.ToolCallStarted;
import com.botmaker.core.event.CoreEvent.ToolCallStep;
import com.botmaker.shared.steps.DeviceSteps;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallLogTest {

    private static final Instant NOW = Instant.parse("2026-11-11T20:41:12Z");

    private final ToolCallLog log = new ToolCallLog(new JacksonMcpJsonMapper(new ObjectMapper()),
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final List<CoreEvent> heard = new CopyOnWriteArrayList<>();

    @Test
    void anAnsweredCallIsToldAsItBeginsAndEnds() {
        log.watch(heard::add);
        SyncToolSpecification tool = log.logged(pluginTool("botmaker_sdk_click", "BotMaker SDK",
                request -> text(false, "The Notice closed.")));

        CallToolResult result = call(tool, Map.of("x", 450));
        settle();

        assertEquals("The Notice closed.", ((McpSchema.TextContent) result.content().getFirst()).text());
        ToolCallStarted started = assertInstanceOf(ToolCallStarted.class, heard.get(0));
        assertEquals("botmaker_sdk_click", started.tool());
        assertEquals("BotMaker SDK", started.plugin());
        assertEquals("{\"x\":450}", started.arguments());
        assertEquals(NOW.toEpochMilli(), started.atMillis());
        ToolCallEnded ended = assertInstanceOf(ToolCallEnded.class, heard.get(1));
        assertEquals(started.call(), ended.call());
        assertEquals(Outcome.OK, ended.outcome());
        assertEquals(List.of(new Part(Part.Kind.TEXT, "The Notice closed.")), ended.reply());
    }

    @Test
    void aCoreToolNamesNoPluginWhateverItsDescriptionSays() {
        log.watch(heard::add);
        call(log.logged(tool("read_value", "Reads a value (from the open file)", request -> text(false, "ok"))),
                Map.of());
        settle();

        assertEquals("", ((ToolCallStarted) heard.getFirst()).plugin());
    }

    @Test
    void aRefusedCallAndAFailedOneAreToldApart() {
        log.watch(heard::add);
        call(log.logged(tool("edit_code", "", request -> text(true, "REFUSED: Pictures.ORE is not a number."))),
                Map.of());
        call(log.logged(tool("run_bot", "", request -> text(true, "No project is open."))), Map.of());
        settle();

        assertEquals(Outcome.REFUSED, ((ToolCallEnded) heard.get(1)).outcome());
        assertEquals(Outcome.ERROR, ((ToolCallEnded) heard.get(3)).outcome());
    }

    @Test
    void aThrownCallEndsAsAnErrorAndStillThrows() {
        log.watch(heard::add);
        SyncToolSpecification tool = log.logged(tool("device", "", request -> {
            throw new IllegalStateException("adb went away");
        }));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> call(tool, Map.of()));
        settle();

        assertEquals("adb went away", thrown.getMessage());
        ToolCallEnded ended = (ToolCallEnded) heard.get(1);
        assertEquals(Outcome.ERROR, ended.outcome());
        assertEquals(List.of(new Part(Part.Kind.TEXT, "adb went away")), ended.reply());
    }

    @Test
    void aThrowWithNoMessageSaysWhatWasThrown() {
        log.watch(heard::add);
        SyncToolSpecification tool = log.logged(tool("device", "", request -> {
            throw new NullPointerException();
        }));

        assertThrows(NullPointerException.class, () -> call(tool, Map.of()));
        settle();

        assertEquals("java.lang.NullPointerException", ((ToolCallEnded) heard.get(1)).reply().getFirst().value());
    }

    @Test
    void aWatcherThatFailsNeverFailsTheCallNorTheOthers() {
        log.watch(event -> {
            throw new IllegalStateException("the channel is gone");
        });
        log.watch(heard::add);
        CallToolResult result = call(log.logged(tool("edit_code", "", request -> text(false, "ok"))), Map.of());
        settle();

        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertEquals(2, heard.size());
    }

    @Test
    void onlyTheLastFiveHundredCallsAreKept() {
        SyncToolSpecification tool = log.logged(tool("which_screen", "", request -> text(false, "title")));
        for (int i = 0; i < ToolCallLog.KEPT + 3; i++) call(tool, Map.of());

        List<CoreEvent> kept = log.kept();
        assertEquals(2 * ToolCallLog.KEPT, kept.size());
        assertEquals(4, ((ToolCallStarted) kept.getFirst()).call());
    }

    @Test
    void aLateWatcherHearsTheKeptCallsFirstThenTheLiveOnesOnce() {
        SyncToolSpecification tool = log.logged(tool("which_screen", "", request -> text(false, "title")));
        call(tool, Map.of());

        Runnable stop = log.watch(heard::add);
        settle();
        assertEquals(2, heard.size());
        call(tool, Map.of());
        settle();
        assertEquals(4, heard.size());
        assertEquals(2, ((ToolCallStarted) heard.get(2)).call());

        stop.run();
        call(tool, Map.of());
        settle();
        assertEquals(4, heard.size());
    }

    @Test
    void anImageTravelsAsAJpegThumbnail() throws IOException {
        log.watch(heard::add);
        String png = png(1920, 1080);
        call(log.logged(tool("screenshot", "", request -> image(png))), Map.of());
        settle();

        Part part = ((ToolCallEnded) heard.get(1)).reply().getFirst();
        assertEquals(Part.Kind.IMAGE, part.kind());
        BufferedImage thumbnail = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(part.value())));
        assertEquals(ToolCallLog.THUMBNAIL_SIDE, thumbnail.getWidth());
        assertEquals(540, thumbnail.getHeight());
        assertEquals(1920, part.width(), "the image's own size, which a tap's point is in");
        assertEquals(1080, part.height());
    }

    @Test
    void aRepliesMetaToldAsItsExtras() {
        log.watch(heard::add);
        Map<String, Object> meta = Map.of(
                ReplyMeta.TAP, Map.of("path", List.of(Map.of("x", 1, "y", 2), Map.of("x", 30, "y", 40))),
                ReplyMeta.CHANGED, List.of(Map.of("file", "/bot/Main.java", "class", "Main", "method", "run"),
                        Map.of("file", "/bot/Main.java", "method", "no class, left out")),
                "other/key", "ignored");
        call(log.logged(tool("device", "", request -> CallToolResult.builder().addTextContent("Swiped.")
                .meta(meta).build())), Map.of());
        settle();

        ToolCallEnded ended = (ToolCallEnded) heard.get(1);
        assertEquals(List.of(new Point(1, 2), new Point(30, 40)), ended.extras().tap());
        assertEquals(List.of(new Changed("/bot/Main.java", "Main", "run")), ended.extras().changed());
        assertEquals(Extras.NONE, ToolCallLog.extras(Map.of(ReplyMeta.TAP, Map.of("x", "not a number", "y", 2))));
    }

    @Test
    void onlyTheNewestCallsKeepTheirImages() throws IOException {
        String png = png(64, 64);
        SyncToolSpecification tool = log.logged(tool("screenshot", "", request -> image(png)));
        for (int i = 0; i < ToolCallLog.IMAGES_KEPT + 1; i++) call(tool, Map.of());

        List<CoreEvent> kept = log.kept();
        assertEquals(new Part(Part.Kind.TEXT, ToolCallLog.IMAGE_GONE), ((ToolCallEnded) kept.get(1)).reply().getFirst());
        assertEquals(Part.Kind.IMAGE, ((ToolCallEnded) kept.get(3)).reply().getFirst().kind());
    }

    @Test
    void anUnreadableImageIsSaidInText() {
        log.watch(heard::add);
        call(log.logged(tool("screenshot", "", request -> image("bm90IGFuIGltYWdl"))), Map.of());
        settle();

        assertEquals(Part.Kind.TEXT, ((ToolCallEnded) heard.get(1)).reply().getFirst().kind());
    }

    @Test
    void aCallsEventsCrossTheChannelWhole() {
        log.watch(heard::add);
        call(log.logged(pluginTool("botmaker_sdk_click", "BotMaker SDK", request -> text(false, "ok"))),
                Map.of("x", 450));
        settle();

        assertEquals(2, heard.size());
        for (CoreEvent event : heard) {
            String told = CoreJson.write(new Reply.Told(event));
            assertEquals(event, ((Reply.Told) CoreJson.read(told, Reply.class)).event());
        }
    }

    @Test
    void aCallsDeviceStepsAreToldUnderItAndKept() {
        SyncToolSpecification tool = log.logged(tool("device", "", request -> {
            DeviceSteps.step("127.0.0.1:5555", "input tap 450 1520", () -> null);
            return text(false, "ok");
        }));
        call(tool, Map.of());

        log.watch(heard::add);
        settle();

        ToolCallStep step = assertInstanceOf(ToolCallStep.class, heard.get(1));
        assertEquals(((ToolCallStarted) heard.get(0)).call(), step.call());
        assertEquals("127.0.0.1:5555", step.device());
        assertEquals("input tap 450 1520", step.command());
        assertInstanceOf(ToolCallEnded.class, heard.get(2));
    }

    @Test
    void aStepOnAThreadWithoutTheScopeIsCountedElsewhere() {
        call(log.logged(tool("device", "", request -> {
            Thread other = Thread.ofPlatform().start(() -> DeviceSteps.step("d", "input tap 1 2", () -> null));
            try {
                other.join();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return text(false, "ok");
        })), Map.of());

        List<CoreEvent> kept = log.kept();
        assertEquals(2, kept.size());
        assertTrue(((ToolCallEnded) kept.get(1)).elsewhere() >= 1);
    }

    @Test
    void aStepsProgressReachesAWatcherLiveOnly() {
        log.watch(heard::add);
        call(log.logged(tool("device", "", request -> {
            DeviceSteps.progress("d", "install a.apk").report(5, 10);
            return text(false, "ok");
        })), Map.of());
        settle();

        assertEquals(new ToolCallProgress(1, "d", "install a.apk", 5, 10), heard.get(1));
        assertEquals(2, log.kept().size());
    }

    @Test
    void theLoggedToolIsServedUnderItsOwnDefinition() {
        SyncToolSpecification tool = tool("device", "Drives an emulator.", request -> text(false, "ok"));
        assertSame(tool.tool(), log.logged(tool).tool());
    }

    /** Waits until every call so far is told: the log tells on a thread of its own. */
    private void settle() {
        log.kept();
    }

    private static SyncToolSpecification pluginTool(String name, String plugin,
                                                    Function<CallToolRequest, CallToolResult> answer) {
        return spec(McpSchema.Tool.builder().name(name).description(PluginTools.described("Taps.", plugin))
                .meta(PluginTools.meta(plugin)), answer);
    }

    private static SyncToolSpecification tool(String name, String description,
                                              Function<CallToolRequest, CallToolResult> answer) {
        return spec(McpSchema.Tool.builder().name(name).description(description), answer);
    }

    private static SyncToolSpecification spec(McpSchema.Tool.Builder tool,
                                              Function<CallToolRequest, CallToolResult> answer) {
        return SyncToolSpecification.builder()
                .tool(tool.inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null))
                        .build())
                .callHandler((exchange, request) -> answer.apply(request)).build();
    }

    private static CallToolResult call(SyncToolSpecification tool, Map<String, Object> arguments) {
        return tool.callHandler().apply(null, new CallToolRequest(tool.tool().name(), arguments));
    }

    private static CallToolResult text(boolean error, String text) {
        return CallToolResult.builder().addTextContent(text).isError(error).build();
    }

    private static CallToolResult image(String base64) {
        return CallToolResult.builder().addContent(new McpSchema.ImageContent(null, base64, "image/png")).build();
    }

    private static String png(int width, int height) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
