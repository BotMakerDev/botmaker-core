// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.server;

import com.botmaker.core.CoreJson;
import com.botmaker.core.edit.Edit;
import com.botmaker.core.edit.EditRequest;
import com.botmaker.core.edit.EditResult;
import com.botmaker.core.edit.HistoryStep;
import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.pilot.PilotServer;
import com.botmaker.core.pilot.PilotState;
import com.botmaker.core.pilot.transport.FunnelTransport;
import com.botmaker.core.pilot.transport.TransportKind;
import com.botmaker.core.plugin.PluginWrite;
import com.botmaker.core.run.claims.SeveralAsk;
import com.botmaker.core.run.trial.TrialAsk;
import com.botmaker.java.grammar.JavaValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Every message of the channel's protocol reads back equal to what was written. */
class RequestJsonTest {

    @Test
    void everyRequestRoundTrips() {
        List<Request> requests = List.of(
                new Request.Open(1, "/home/me/BotMakerProjects/MyBot"),
                new Request.Show(2, "com/mybot/MyBot.java"),
                new Request.Apply(3, new EditRequest("com/mybot/MyBot.java", List.of(new Edit.Delete("0.1.2")))),
                new Request.UserEdit(4, new Edit.Rename("0.1", "count")),
                new Request.Step(5, HistoryStep.REDO),
                new Request.Restore(5, "com/mybot/Miner.java", "mine", List.of("List.of is gone.")),
                new Request.Tools(6, List.of("{\"name\":\"ui\",\"inputSchema\":{\"type\":\"object\"}}"), true),
                new Request.Tools(22, List.of(), false),
                new Request.Called(8, 4, "{\"content\":[],\"isError\":false}"),
                new Request.PluginWriting(9, new PluginWrite.MoveBefore("sdk:SCREENS", "Home", "Shop")),
                new Request.Batch(19, "Record the walk", true),
                new Request.Batch(20, "", false),
                new Request.Reload(10),
                new Request.DevReload(33, "com.github.BotMakerDev", "botmaker-sdk", "Keep exploring"),
                new Request.Compile(11),
                new Request.Run(12),
                new Request.RunEntry(13, "Explore map", "com.example.ExploreMap"),
                new Request.RunEntry(32, "Keep exploring", "com.example.KeepExploring", "eyJtaW51dGVzIjoxfQ"),
                new Request.RunSeveral(30, SeveralAsk.of(3), "5f0c-ab12"),
                new Request.RunSeveral(31, SeveralAsk.pinned(3, SeveralAsk.How.COUNT,
                        List.of("2:INSTANCE=Instance.named(\"ld-1\")")), "5f0c-ab13"),
                new Request.Try(14, new TrialAsk.OnItsOwn("com.mybot", "Mybot", "ore", true)),
                new Request.Try(15, new TrialAsk.OnItsOwn("", "Mybot.Inner", "ore", false)),
                new Request.Try(21, new TrialAsk.OneStatement("com/mybot/MyBot.java", "0.1.2", Map.of(),
                        Set.of("ore"))),
                new Request.Debug(15, true, Map.of("com/mybot/MyBot.java", Set.of("0.1.2"))),
                new Request.Stop(16),
                new Request.Answer(17, 3, "Iron"),
                new Request.Debugging(18, Request.Debugging.Step.CONTINUE),
                new Request.Close(7, false),
                new Request.Close(8, true),
                new Request.WatchCalls(9),
                new Request.PilotWatch(40),
                new Request.PilotStart(41, null),
                new Request.PilotStart(42, TransportKind.QUICK_TUNNEL),
                new Request.PilotStop(43),
                new Request.PilotReset(44),
                new Request.PilotDisconnect(45, "ab12cd34"));
        for (Request request : requests) {
            assertEquals(request, CoreJson.read(CoreJson.write(request), Request.class));
        }
    }

    /**
     * Every plugin write, and a try given a value, read back writing the same JSON: a {@link JavaValue} holds a tree,
     * equal by what it writes.
     */
    @Test
    void aTryOnItsOwnNamesOnlyJavaNames() {
        // The names become the caller's path and its Java: a path or a splice is refused as the request is read.
        for (String[] bad : new String[][]{{"a/../../x", "Mybot", "ore"}, {"com.mybot", "Mybot;", "ore"},
                {"com.mybot", "Mybot", "ore()"}}) {
            assertThrows(IllegalArgumentException.class, () -> new TrialAsk.OnItsOwn(bad[0], bad[1], bad[2], false));
        }
    }

    @Test
    void everyPluginWriteRoundTrips() {
        JavaValue value = JavaValue.parse("Pictures.ORE").orElseThrow();
        PluginWrite.AddRunParameter.Mark mark = new PluginWrite.AddRunParameter.Mark("com.example.Shown",
                Map.of("label", "Ore"), Map.of("order", 2),
                Map.of("screen", new PluginWrite.AddRunParameter.ClassOf("sdk:SCREENS", "Home")));
        List<PluginWrite> writes = List.of(
                new PluginWrite.SetValue("sdk:FLOW", value),
                new PluginWrite.SetMember("sdk:PICTURES", "ORE", value),
                new PluginWrite.SetSetup("sdk:SCREENS", "Home", value),
                new PluginWrite.Create("sdk:FLOW"),
                new PluginWrite.AddClass("sdk:SCREENS", "Shop", value),
                new PluginWrite.AddConstant("sdk:PICTURES", "GOLD"),
                new PluginWrite.Add("sdk:PICTURES", "GOLD", "com.example.Picture", value),
                new PluginWrite.Rename("sdk:PICTURES", "GOLD", "SILVER"),
                new PluginWrite.Repoint("sdk:PICTURES", "GOLD", "SILVER", "merged"),
                new PluginWrite.Remove("sdk:PICTURES", "GOLD"),
                new PluginWrite.MoveBefore("sdk:SCREENS", "Shop", "Home"),
                new PluginWrite.AddGroup("sdk:PICTURES", "Shop", List.of("GOLD", "ORE")),
                new PluginWrite.RenameGroup("sdk:PICTURES", "Shop", "Store"),
                new PluginWrite.RemoveGroup("sdk:PICTURES", "Store"),
                new PluginWrite.CreateMethod("sdk:ACTIONS", "Mine", "com.mybot.Actions", "mine"),
                new PluginWrite.CreateBooleanMethod("com.mybot.Checks", "hasOre"),
                new PluginWrite.AppendCall("com.mybot.Walk#walk", "com.example.Steps", "tap", List.of(value)),
                new PluginWrite.AddRunParameter("Mining", "ore", 3, List.of(mark), List.of()),
                new PluginWrite.AddRunParameter("Mining", "fast", true, List.of(), List.of(mark)),
                new PluginWrite.ForgetRunParameter("Mining", "ore"),
                new PluginWrite.ApplyPlan("Rename Ore", Map.of("/bots/a/Ore.java", "class Ore {}"),
                        Map.of("/bots/a/Old.java", "/bots/a/Ore.java")));
        for (PluginWrite write : writes) {
            String json = CoreJson.write(write);
            assertEquals(json, CoreJson.write(CoreJson.read(json, PluginWrite.class)));
        }
        assertEquals(Set.of(PluginWrite.class.getPermittedSubclasses()),
                writes.stream().map(Object::getClass).collect(Collectors.toSet()), "every kind of write is above");

        Request trial = new Request.Try(1, new TrialAsk.OneStatement("com/mybot/MyBot.java", "0.1",
                Map.of("ore", value), Set.of()));
        String json = CoreJson.write(trial);
        assertEquals(json, CoreJson.write(CoreJson.read(json, Request.class)));

        // A together carries its writes in order (2026-11-21).
        Request together = new Request.Together(34, "Tidy the map", List.of(
                new PluginWrite.SetSetup("sdk:SCREENS", "Home", value), new PluginWrite.SetValue("sdk:FLOW", value)));
        String togetherJson = CoreJson.write(together);
        assertEquals(togetherJson, CoreJson.write(CoreJson.read(togetherJson, Request.class)));
        assertEquals(List.of(), new Request.Together(35, "", null).writes());
    }

    @Test
    void everyReplyRoundTrips() {
        List<Reply> replies = List.of(
                new Reply.Answer(1, new EditResult.Refused("No project is open.")),
                new Reply.Answer(2, new EditResult.Accepted(List.of())),
                new Reply.Told(new CoreEvent.Status("Saved.")),
                new Reply.Call(3, Reply.Call.Kind.RUN, "ui", "{\"action\":\"look\"}"),
                new Reply.Call(4, Reply.Call.Kind.ADMIT, "edit_code", "{}"),
                new Reply.Pilot(new PilotState(PilotState.Phase.ON, TransportKind.LAN, TransportKind.LAN,
                        "http://10.0.0.5:4000", "http://10.0.0.5:4000/?token=t", List.of("10", "01"), null, null,
                        new FunnelTransport.Diag(true, true, FunnelTransport.Issue.NONE),
                        List.of(new PilotState.Option(TransportKind.LAN, true, null, null)),
                        List.of(new PilotServer.Connected("ab12cd34", "10.0.0.9", 5L, true)),
                        List.of(new PilotState.TailnetPhone("pixel", true, 0L)))),
                new Reply.Pilot(new PilotState(PilotState.Phase.OFF, TransportKind.TAILNET, null, null, null, null,
                        "Tailscale is off", "Turn it on", null, null, null, null)));
        for (Reply reply : replies) {
            assertEquals(reply, CoreJson.read(CoreJson.write(reply), Reply.class));
        }
    }
}
