package com.botmaker.core.run.claims;

import com.botmaker.core.run.claims.RunRegistry.Hold;
import com.botmaker.core.run.claims.RunRegistry.LiveRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Runs window's view of the live runs (2026-11-16, plan phase 14). */
class LiveRunsViewTest {

    private static final String ACCOUNT = "com.refbot.params.bot.Farm#account";
    private static final String INSTANCE = "com.botmaker.sdk.Sdk#instance";
    private static final String SDK = "com.github.BotMakerDev:botmaker-sdk";

    @Test
    void runsAreGroupedByBotWithTheirPicksAndLastLine(@TempDir Path root) throws IOException {
        Path farm = Files.createDirectories(root.resolve("farm"));
        String bot = SeveralRuns.bot(farm);
        Files.createDirectories(SeveralRuns.outputs(farm));
        Files.writeString(SeveralRuns.log(farm, "r1"), "started\nDaily quests done\n");
        List<LiveRun> live = List.of(
                LiveRun.ofThisProcess("r1", bot, List.of(Hold.plugin(SDK, INSTANCE, "Instance.of(\"LDPlayer-1\")"),
                        Hold.bot(bot, ACCOUNT, "GameAccount.MAIN"))),
                LiveRun.ofThisProcess("s1", "Google sign-in", List.of(Hold.plugin(SDK, INSTANCE,
                        "Instance.of(\"LDPlayer-11\")"))),
                LiveRun.ofThisProcess("r2", bot, List.of(Hold.plugin(SDK, INSTANCE, "Instance.of(\"LDPlayer-2\")"))));

        LiveRunsView view = new RunRegistryStub(live).view();

        assertEquals(3, view.count());
        assertEquals(List.of("farm", "Google sign-in"), view.groups().stream().map(LiveRunsView.Group::name).toList());
        LiveRunsView.Group mine = view.groupOf(farm).orElseThrow();
        assertEquals(LiveRunsView.Kind.MY_BOT, mine.kind());
        assertEquals(List.of("r1", "r2"), mine.runs().stream().map(LiveRunsView.Live::runId).toList());
        assertEquals("Daily quests done", mine.runs().getFirst().lastLine());
        assertEquals("", mine.runs().get(1).lastLine(), "a run that printed nothing");
        assertEquals(Map.of(INSTANCE, List.of("Instance.of(\"LDPlayer-1\")"), ACCOUNT, List.of("GameAccount.MAIN")),
                mine.runs().getFirst().picks());

        LiveRunsView.Group side = view.others(farm).getFirst();
        assertEquals(LiveRunsView.Kind.SIDE, side.kind());
        assertTrue(side.dir().isEmpty());
        assertTrue(side.runs().getFirst().holdsPluginElement(INSTANCE));
        assertFalse(side.runs().getFirst().holdsPluginElement(ACCOUNT));
    }

    /** The view over a fixed list, its last lines read as the registry's are. */
    private record RunRegistryStub(List<LiveRun> live) {
        LiveRunsView view() {
            return LiveRunsView.of(live, placed -> {
                try {
                    List<String> lines = SeveralRuns.lastLines(placed.dir(), placed.runId(), 1);
                    return lines.isEmpty() ? "" : lines.getFirst();
                } catch (IOException e) {
                    return "";
                }
            });
        }
    }
}
