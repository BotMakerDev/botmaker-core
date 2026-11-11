package com.botmaker.core.assist;

import com.botmaker.plugin.api.assist.AgentReply;
import com.botmaker.plugin.api.assist.AssistantTool;
import com.botmaker.plugin.api.assist.ToolSchema;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorState;
import com.botmaker.shared.emulator.PlatformId;
import com.botmaker.shared.emulator.Platforms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
                "launch_app", "stop_app", "restart_app", "focus", "screenshot", "tap", "swipe", "key", "shell",
                "logcat", "processes", "crash", "push", "pull")) {
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
    void logcatDumpsItsLastLinesOfOneProcessAtALevel() {
        assertEquals("logcat -d | tail -n 200", DeviceTools.logcatCommand(200, null, null));
        assertEquals("logcat -d --pid=4321 '*:W' | tail -n 50", DeviceTools.logcatCommand(50, "4321", "W"));
        assertEquals("logcat -d '*:E'", DeviceTools.logcatCommand(0, null, "E"));
        assertEquals("b Unity\nd unity", DeviceTools.lastHolding("a x\nb Unity\nc y\nd unity\n", "UNITY", 2));
        assertEquals("d unity", DeviceTools.lastHolding("b Unity\nd unity", "unity", 1));
        assertEquals("20 bytes", DeviceTools.size(20));
        assertEquals("3.0 MB", DeviceTools.size(3 * 1024 * 1024));
        assertEquals("E", DeviceTools.logLevel("error"));
        assertEquals("V", DeviceTools.logLevel(" v "));
        assertEquals(null, DeviceTools.logLevel(""));
        assertThrows(IllegalArgumentException.class, () -> DeviceTools.logLevel("x; reboot"));
    }

    @Test
    void processesAreTheRowsWhoseNameHoldsTheFilter() {
        String ps = "  PID   RSS NAME\n    1  9000 init\n 4321 812000 com.zjcs.android.us\n 4400 60000 "
                + "com.zjcs.android.us:webview\n";
        assertEquals(List.of("1  9000 init", "4321 812000 com.zjcs.android.us",
                "4400 60000 com.zjcs.android.us:webview"), DeviceTools.processRows(ps, ""));
        assertEquals(List.of("1  9000 init"), DeviceTools.processRows(ps, "init"));
        assertEquals(List.of(), DeviceTools.processRows(ps, "812"), "the name only, not the pid or memory");
        assertEquals(List.of(), DeviceTools.processRows(null, ""));
    }

    @Test
    void aCopyNamesOnlyAPlainAbsoluteDevicePath() {
        assertEquals("/sdcard/Download/a.png", DeviceTools.devicePath(" /sdcard/Download/a.png "));
        for (String refused : List.of("sdcard/a", "/sdcard/../data/x", "/sdcard/a b", "/sdcard/a;reboot",
                "/sdcard/$(x)", "/sdcard/'a'", "")) {
            assertThrows(IllegalArgumentException.class, () -> DeviceTools.devicePath(refused), refused);
        }
    }

    @Test
    void pushWritesOnlyUnderSharedStorageOrAdbsScratch() {
        for (String to : List.of("/system/bin/x", "/data/data/com.x/files/a", "/vendor/x")) {
            AgentReply reply = DeviceTools.tool().invoke(Map.of("action", "push", "instance", "nowhere",
                    "file", System.getProperty("java.home") + "/release", "to", to), null);
            assertTrue(reply.parts().getFirst().text().startsWith("REFUSED: push writes only under"),
                    reply.parts().getFirst().text());
        }
    }

    @Test
    void aPullIsNumberedPastAFileAlreadyThere(@TempDir Path dir) throws IOException {
        assertEquals(dir.resolve("shot.png"), DeviceTools.pulledTo(dir, "/sdcard/shot.png"));
        Files.writeString(dir.resolve("shot.png"), "x");
        Files.writeString(dir.resolve("shot-2.png.part"), "x");
        assertEquals(dir.resolve("shot-3.png"), DeviceTools.pulledTo(dir, "/sdcard/shot.png"));
        Files.writeString(dir.resolve("log"), "x");
        assertEquals(dir.resolve("log-2"), DeviceTools.pulledTo(dir, "/data/local/tmp/log"));
        assertThrows(IllegalArgumentException.class, () -> DeviceTools.pulledTo(dir, "/sdcard/"));
    }

    @Test
    void pushCopiesNoHiddenFileNorBotMakersSettings(@TempDir Path dir) throws IOException {
        Path shot = Files.writeString(dir.resolve("shot.png"), "x");
        Path settings = Files.createDirectories(dir.resolve("config"));
        Path credentials = Files.writeString(settings.resolve("credentials.json"), "{}");
        Path key = Files.writeString(Files.createDirectories(dir.resolve(".ssh")).resolve("id_ed25519"), "k");
        assertEquals(shot.toRealPath(), DeviceTools.pushable(shot.toString(), settings));
        for (Path refused : List.of(credentials, key, dir.resolve("missing.png"))) {
            assertThrows(IllegalArgumentException.class, () -> DeviceTools.pushable(refused.toString(), settings),
                    refused.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> DeviceTools.pushable("shot.png", settings));
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
