package com.botmaker.core.event;

import com.botmaker.core.CoreJson;
import com.botmaker.core.edit.ChangedMethod;
import com.botmaker.core.run.debug.DebugSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Every event crosses the protocol as JSON and comes back equal. */
class CoreEventJsonTest {

    private static final String FILE = "com/mybot/Main.java";

    private static final List<CoreEvent> EVERY_EVENT = List.of(
            new CoreEvent.MethodsChanged(FILE, List.of(new ChangedMethod("types[0]/bodyDeclarations[0]", "main",
                    "public static void main(String[] args) {\n}",
                    List.of(new ChangedMethod.Node("types[0]/bodyDeclarations[0]/body", 39, 3)))),
                    List.of("types[0]/bodyDeclarations[3]"),
                    "package com.mybot;\n\npublic class Main {\n    public static void main(String[] args) {\n}\n}\n", true),
            new CoreEvent.HistoryChanged(true, false, "Delete the line", ""),
            new CoreEvent.Diagnostics(FILE, List.of(
                    new CoreEvent.Diagnostics.Problem("types[0]/bodyDeclarations[0]/body/statements[0]",
                            CoreEvent.Diagnostics.Severity.ERROR, 4, "ore cannot be resolved"),
                    new CoreEvent.Diagnostics.Problem("", CoreEvent.Diagnostics.Severity.WARNING, 1,
                            "The import java.util.List is never used"),
                    new CoreEvent.Diagnostics.Problem("", CoreEvent.Diagnostics.Severity.INFO, 3,
                            "main never starts the bot"))),
            new CoreEvent.RunState(CoreEvent.RunState.Phase.ENDED, "stopped by the user"),
            new CoreEvent.RunOutput(CoreEvent.RunOutput.Stream.ERR, "Ore not found\n"),
            new CoreEvent.RunLogged("/home/me/BotMakerProjects/MyBot/runs/20261105-101500"),
            new CoreEvent.RunTraced(1_000, "warn", "Vision", "no ore", 3, "com.mybot.Mouse", "click", "com.mybot.Main",
                    14, new CoreEvent.RunTraced.Region(1, 2, 3, 4), Map.of("screen", "Home")),
            new CoreEvent.RunTraced(0, "", "", "hi", 1, "", "", "", -1, null, Map.of()),
            new CoreEvent.RunFrame(new byte[]{1, 2, -3}),
            new CoreEvent.RunProcess(4242),
            new CoreEvent.DebugSession(CoreEvent.DebugSession.Phase.RESUMED),
            new CoreEvent.DebugPaused(FILE, 18, new DebugSnapshot("main", List.of(
                    new DebugSnapshot.Frame("play", "com.mybot.Main", Path.of("/bots/mybot/Main.java"), 18, List.of(
                            new DebugSnapshot.Variable("ore", "int", "3", List.of(
                                    new DebugSnapshot.Variable("left", "int", "1", List.of(), 2))))),
                    new DebugSnapshot.Frame("… 3 more", "", null, 0, List.of())))),
            new CoreEvent.DebugPaused("", 7, DebugSnapshot.empty()),
            new CoreEvent.DebugFollowed(new CoreEvent.DebugFollowed.Place(FILE, 4),
                    List.of(new CoreEvent.DebugFollowed.Place(FILE, 4), new CoreEvent.DebugFollowed.Place(FILE, 5)), true),
            new CoreEvent.SourcesChanged(List.of("params/bot/Bot.java")),
            new CoreEvent.InputAsked(3, "choice", "Which ore?", List.of("Iron", "Gold"), 12),
            new CoreEvent.Status("Compiling..."),
            new CoreEvent.Opened(List.of("Restored 1 missing project file: pom.xml")),
            new CoreEvent.LibrariesResolved(List.of("/m2/sdk.jar"),
                    List.of("com.github.BotMakerDev:botmaker-sdk:9.9.9 — not found")),
            new CoreEvent.Refusal(FILE, "Pictures.ORE is not a number."),
            new CoreEvent.ToolCallStarted(7, "botmaker_sdk_click", "BotMaker SDK", "{\"x\":450,\"y\":1520}",
                    Instant.parse("2026-11-11T20:41:12Z").toEpochMilli()),
            new CoreEvent.ToolCallStep(7, "127.0.0.1:5555", "input tap 450 1520", true, "",
                    Instant.parse("2026-11-11T20:41:12Z").toEpochMilli(), 38),
            new CoreEvent.ToolCallProgress(7, "127.0.0.1:5555", "install base.apk", 1_000, 3_000_000_000L),
            new CoreEvent.ToolCallEnded(7, CoreEvent.ToolCallEnded.Outcome.OK, List.of(
                    new CoreEvent.ToolCallEnded.Part(CoreEvent.ToolCallEnded.Part.Kind.TEXT, "The Notice closed."),
                    new CoreEvent.ToolCallEnded.Part(CoreEvent.ToolCallEnded.Part.Kind.IMAGE, "/9j/4AAQ", 1080, 1920)),
                    412, 2, new CoreEvent.ToolCallEnded.Extras(List.of(new CoreEvent.ToolCallEnded.Point(450, 1520)),
                    List.of(new CoreEvent.ToolCallEnded.Changed("/bot/src/Main.java", "Main", "run")))));

    @Test
    void everyEventRoundTrips() {
        for (CoreEvent event : EVERY_EVENT) {
            assertEquals(event, CoreJson.read(CoreJson.write(event), CoreEvent.class), CoreJson.write(event));
        }
    }

    /** A new kind of event is a protocol change: it joins the list above, or this fails. */
    @Test
    void theListAboveHoldsEveryKindOfEvent() {
        Set<Class<?>> kinds = Arrays.stream(CoreEvent.class.getPermittedSubclasses()).collect(Collectors.toSet());
        assertEquals(kinds, EVERY_EVENT.stream().map(Object::getClass).collect(Collectors.toSet()));
    }

    @Test
    void anEventIsNamedOnTheWire() {
        assertEquals("{\"event\":\"runState\",\"phase\":\"RUNNING\",\"detail\":\"\"}",
                CoreJson.write(new CoreEvent.RunState(CoreEvent.RunState.Phase.RUNNING, null)));
    }
}
