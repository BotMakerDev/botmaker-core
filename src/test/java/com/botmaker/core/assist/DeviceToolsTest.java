package com.botmaker.core.assist;

import com.botmaker.plugin.api.assist.AgentReply;
import com.botmaker.plugin.api.assist.AssistantTool;
import com.botmaker.plugin.api.assist.ToolSchema;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorState;
import com.botmaker.shared.emulator.PlatformId;
import com.botmaker.shared.emulator.Platforms;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceToolsTest {

    @Test
    void theToolOffersEveryAction() {
        AssistantTool<?> tool = DeviceTools.tool();
        assertEquals("device", tool.name());
        String schema = McpTools.schemaOf(tool.schema());
        for (String action : List.of("list", "start", "stop", "checks", "fix", "apps", "search_store", "install",
                "launch_app", "stop_app", "restart_app", "focus", "screenshot", "tap", "swipe", "key", "shell")) {
            assertTrue(schema.contains("\"" + action + "\""), action + " in " + schema);
        }
        assertTrue(tool.schema().actions().stream().map(ToolSchema.Action::id).toList().contains("search_store"));
    }

    @Test
    void shellRunsOnlyAReadWithNothingChainedOn() {
        assertEquals("dumpsys window", DeviceTools.readOnly(" dumpsys window "));
        assertEquals("getprop", DeviceTools.readOnly("getprop"));
        assertEquals("wm size", DeviceTools.readOnly("wm size"));
        assertEquals("dumpsys battery", DeviceTools.readOnly("dumpsys battery"));
        assertEquals("pm list packages -3", DeviceTools.readOnly("pm list packages -3"));
        for (String refused : List.of("rm -rf /sdcard", "pm uninstall com.x", "dumpsys; reboot", "getprop | sh",
                "dumpsys > /sdcard/x", "getprop $(reboot)", "pidof `reboot`", "dumpsys\nreboot", "getpropx",
                "settings put global x 1", "wm size 720x1280", "wm size reset", "wm density 160",
                "dumpsys battery set level 5", "dumpsys battery unplug", "dumpsys deviceidle force-idle", "")) {
            assertThrows(IllegalArgumentException.class, () -> DeviceTools.readOnly(refused), refused);
        }
    }

    @Test
    void aBadArgumentIsARefusalNotAThrow() {
        AgentReply reply = DeviceTools.tool().invoke(Map.of("action", "shell", "instance", "nowhere",
                "command", "reboot"), null);
        assertTrue(reply.isRefused());
        assertTrue(reply.parts().getFirst().text().startsWith("REFUSED: shell runs only a read"),
                reply.parts().getFirst().text());
    }

    @Test
    void theListSaysEachProductThenEachInstance() {
        EmulatorInstance running = new EmulatorInstance(PlatformId.MUMU, "MuMuPlayer-12.0-0", "127.0.0.1", 16384)
                .withState(EmulatorState.RUNNING);
        Platforms.DiscoveryReport report = new Platforms.DiscoveryReport(List.of(running), List.of(
                new Platforms.PlatformStatus(PlatformId.MUMU, true, 1, null),
                new Platforms.PlatformStatus(PlatformId.BLUESTACKS, false, 0, null)));
        String listed = DeviceTools.listed(report);
        assertTrue(listed.startsWith("MuMu"), listed);
        assertTrue(listed.contains("BlueStacks: not installed"), listed);
        assertTrue(listed.contains("MuMuPlayer-12.0-0  (MuMu"), listed);
        assertTrue(listed.contains("running, adb 127.0.0.1:16384"), listed);
        assertEquals("No instance found.", DeviceTools.listed(new Platforms.DiscoveryReport(List.of(), List.of()))
                .strip());
    }
}
