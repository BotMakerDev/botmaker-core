// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.run.claims;

import com.botmaker.core.run.claims.RunRegistry.Hold;
import com.botmaker.core.run.claims.RunRegistry.LiveRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunRegistryTest {

    private static final String ACCOUNT = "com.refbot.params.bot.Farm#account";
    private static final String INSTANCE = "com.botmaker.sdk.Sdk#instance";
    private static final String SDK = "com.github.BotMakerDev:botmaker-sdk";

    @TempDir
    Path dir;

    private RunRegistry registry() {
        return new RunRegistry(dir, RunRegistry.Alive.PROCESSES);
    }

    private static LiveRun run(String id, String bot, Hold... holds) {
        return LiveRun.ofThisProcess(id, bot, List.of(holds));
    }

    @Test
    void aHeldElementIsRefusedUntilItsRunIsReleased() {
        RunRegistry registry = registry();
        assertTrue(registry.hold(run("r1", "farm", Hold.bot("farm", ACCOUNT, "GameAccount.MAIN"))).isEmpty());

        var conflict = registry.hold(run("r2", "farm", Hold.bot("farm", ACCOUNT, "GameAccount.MAIN")));
        assertTrue(conflict.isPresent());
        assertEquals("r1", conflict.get().by().orElseThrow().runId());
        assertEquals("GameAccount.MAIN is held by a run of farm (r1)", conflict.get().words());
        assertTrue(registry.hold(run("r3", "farm", Hold.bot("farm", ACCOUNT, "GameAccount.ALT"))).isEmpty());

        registry.release("r1");
        assertTrue(registry.hold(run("r2", "farm", Hold.bot("farm", ACCOUNT, "GameAccount.MAIN"))).isEmpty());
        assertEquals(List.of("r2", "r3"), registry.live().stream().map(LiveRun::runId).toList());
    }

    @Test
    void aRefusedRowHoldsNothingAndALiveRunIdIsRefused() {
        RunRegistry registry = registry();
        registry.hold(run("r1", "farm", Hold.bot("farm", ACCOUNT, "\"b\"")));
        assertTrue(registry.hold(run("r2", "farm", Hold.bot("farm", ACCOUNT, "\"a\""),
                Hold.bot("farm", ACCOUNT, "\"b\""))).isPresent());
        assertTrue(registry.hold(run("r3", "farm", Hold.bot("farm", ACCOUNT, "\"a\""))).isEmpty());
        assertThrows(IllegalStateException.class, () -> registry.hold(run("r1", "farm")));
    }

    @Test
    void anElementSharedByEachIsHeldByThatManyRuns() {
        RunRegistry registry = registry();
        Hold hero = Hold.bot("farm", "com.refbot.params.bot.Farm#team", "Hero.CYRA").sharedBy(2);
        assertTrue(registry.hold(run("r1", "farm", hero)).isEmpty());
        assertTrue(registry.hold(run("r2", "farm", hero)).isEmpty());
        assertTrue(registry.hold(run("r3", "farm", hero)).isPresent());
    }

    @Test
    void theSmallestShareAnyHoldAllowsIsTheLimitAndEveryHoldCounts() {
        RunRegistry registry = registry();
        Hold alone = Hold.bot("farm", ACCOUNT, "GameAccount.MAIN");
        registry.hold(run("r1", "farm", alone));
        assertTrue(registry.hold(run("r2", "farm", alone.sharedBy(2))).isPresent(), "r1 holds it alone");

        Hold hero = Hold.bot("farm", ACCOUNT, "Hero.CYRA").sharedBy(2);
        var twice = registry.hold(run("r3", "farm", hero, hero, hero));
        assertEquals("The row asks for Hero.CYRA more often than it may be held", twice.orElseThrow().words());
        assertTrue(registry.hold(run("r4", "farm", hero, hero)).isEmpty());
        assertTrue(registry.hold(run("r5", "farm", hero)).isPresent(), "r4 holds both");
    }

    @Test
    void anotherVersionsRunIsKeptAndAFileThatIsNoRunIsDropped() throws IOException {
        RunRegistry registry = registry();
        registry.hold(run("r1", "farm", Hold.bot("farm", ACCOUNT, "GameAccount.MAIN")));
        Path file = dir.resolve("r1.json");
        Files.writeString(file, Files.readString(file).replaceFirst("\\{", "{\"later\":true,"));
        Files.writeString(dir.resolve("torn.json"), "{\"runId\":");

        assertEquals(List.of("r1"), registry.live().stream().map(LiveRun::runId).toList());
        assertFalse(Files.exists(dir.resolve("torn.json")));
    }

    @Test
    void twoBotsAreRefusedOneSdkInstanceButNotEachOthersOwnPools() {
        RunRegistry registry = registry();
        assertTrue(registry.hold(run("r1", "farm", Hold.plugin(SDK, INSTANCE, "Instances.LDPLAYER_1"),
                Hold.bot("farm", ACCOUNT, "\"main\""))).isEmpty());

        var conflict = registry.hold(run("r2", "arena", Hold.plugin(SDK, INSTANCE, "Instances.LDPLAYER_1")));
        assertTrue(conflict.isPresent());
        assertEquals("farm", conflict.get().by().orElseThrow().bot());
        assertTrue(registry.hold(run("r3", "arena", Hold.plugin(SDK, INSTANCE, "Instances.LDPLAYER_2"),
                Hold.bot("arena", ACCOUNT, "\"main\""))).isEmpty());
    }

    @Test
    void aClaimOfAPooledElementIsRefusedWhileItIsHeldAndAHoldWhileItIsClaimed() {
        RunRegistry registry = registry();
        // As the SDK pool's hold of a Pixel is written; the SDK's PlaceClaimsTest pins its side of the same key.
        String id = "com.botmaker.sdk.api.bot.SdkValue$Id.PLACE";
        Hold pixel = Hold.plugin(SDK, id, "com.botmaker.sdk.api.capture.Place.android(\"Pixel 10\")");
        assertEquals("com.github.BotMakerDev:botmaker-sdk/" + id + "/com.botmaker.sdk.api.capture.Place"
                + ".android(\"Pixel 10\")", pixel.key());
        registry.hold(run("pooled", "farm", pixel));
        registry.hold(run("alone", "arena"));

        assertFalse(registry.claim("alone", pixel.key()), "a pooled run plays there");
        assertTrue(registry.claim("pooled", pixel.key()), "its own hold is no other run's");
        registry.release("pooled");
        assertTrue(registry.claim("alone", pixel.key()));

        var conflict = registry.hold(run("dealt", "farm", pixel));
        assertTrue(conflict.isPresent(), "a run dealt nothing claimed it");
        assertEquals("arena", conflict.get().by().orElseThrow().bot());
        assertTrue(registry.hold(run("other", "farm", Hold.plugin(SDK, id, "x"))).isEmpty());
    }

    @Test
    void aHeldRunIsLiveWithNothingHeldUntilItIsClosed() {
        RunRegistry registry = registry();
        HeldRun held = HeldRun.open(registry, "farm").orElseThrow();
        LiveRun live = registry.live().getFirst();
        assertEquals(held.runId(), live.runId());
        assertEquals(List.of(), live.holds());
        assertEquals(dir.toAbsolutePath().normalize(), held.registry());

        held.movedTo(ProcessHandle.current());
        assertTrue(registry.claim(held.runId(), "k"));
        held.close();
        held.close();
        assertEquals(List.of(), registry.live());
    }

    @Test
    void aClaimIsRefusedToAnotherRunAndFreedWithItsRun() {
        RunRegistry registry = registry();
        registry.hold(run("r1", "farm"));
        registry.hold(run("r2", "farm"));

        assertTrue(registry.claim("r1", "\"guild-7\""));
        assertTrue(registry.claim("r1", "\"guild-7\""));
        assertFalse(registry.claim("r2", "\"guild-7\""));
        assertFalse(registry.claim("gone", "\"other\""));

        registry.unclaim("r1", "\"guild-7\"");
        assertTrue(registry.claim("r2", "\"guild-7\""));
        registry.release("r2");
        assertTrue(registry.claim("r1", "\"guild-7\""));
    }

    @Test
    void aRunWhoseProcessIsGoneIsDropped() throws Exception {
        Process child = child("hold-and-exit", "dead", "GameAccount.MAIN");
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the child ends");
        assertEquals(0, child.exitValue());
        assertTrue(Files.exists(dir.resolve("dead.json")), "the child held its row");

        RunRegistry registry = registry();
        assertEquals(List.of(), registry.live());
        assertFalse(Files.exists(dir.resolve("dead.json")));
        assertTrue(registry.hold(run("r1", "farm", Hold.bot("farm", ACCOUNT, "GameAccount.MAIN"))).isEmpty());
    }

    @Test
    void aRowMovedToTheRunsProcessGoesWithIt() throws Exception {
        RunRegistry registry = registry();
        registry.hold(run("r1", "farm", Hold.bot("farm", ACCOUNT, "GameAccount.MAIN")));
        Process child = new ProcessBuilder(java(), "-version").redirectErrorStream(true).start();
        boolean moved = registry.locked(locked -> locked.moveTo("r1", child.toHandle()));
        assertTrue(moved);
        child.getInputStream().readAllBytes();
        assertTrue(child.waitFor(60, TimeUnit.SECONDS));
        assertEquals(List.of(), registry.live());
    }

    @Test
    void twoProcessesAtOnceNeverHoldOneElementTwice() throws Exception {
        Process child = child("race", "child", "-");
        try (BufferedReader out = new BufferedReader(new InputStreamReader(child.getInputStream(),
                StandardCharsets.UTF_8));
             Writer in = child.outputWriter(StandardCharsets.UTF_8)) {
            assertEquals("ready", out.readLine());
            in.write("go\n");
            in.flush();
            race(new RunRegistry(dir, RunRegistry.Alive.PROCESSES), "parent");
            assertEquals("done", out.readLine());

            List<LiveRun> live = registry().live();
            Map<String, Long> byElement = live.stream()
                    .collect(Collectors.groupingBy(run -> run.holds().getFirst().element(), Collectors.counting()));
            assertEquals(RACED, byElement.size(), "every element dealt");
            assertTrue(byElement.values().stream().allMatch(count -> count == 1), "none twice: " + byElement);
        } finally {
            child.destroy();
            child.waitFor(60, TimeUnit.SECONDS);
        }
    }

    private static final int RACED = 10;

    /** Tries every run of the race in turn; each takes one of {@link #RACED} elements, so some are refused. */
    private static void race(RunRegistry registry, String who) {
        for (int i = 0; i < 4 * RACED; i++) {
            registry.hold(run(who + "-" + i, "farm", Hold.bot("farm", ACCOUNT, "GameAccount.A" + (i % RACED))));
        }
    }

    private Process child(String mode, String id, String element) throws IOException {
        return new ProcessBuilder(java(), "-cp", System.getProperty("java.class.path"), Child.class.getName(),
                dir.toString(), mode, id, element).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** The other process: holds one row and ends, or races the test's process. */
    public static final class Child {
        public static void main(String[] args) throws IOException {
            RunRegistry registry = new RunRegistry(Path.of(args[0]), RunRegistry.Alive.PROCESSES);
            if (args[1].equals("hold-and-exit")) {
                registry.hold(run(args[2], "farm", Hold.bot("farm", ACCOUNT, args[3])));
                return;
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            System.out.println("ready");
            System.out.flush();
            if (!"go".equals(in.readLine())) return;
            race(registry, args[2]);
            System.out.println("done");
            System.out.flush();
            // Stays alive, its runs with it, until the test has read them and closes this input.
            while (in.readLine() != null) {
                Thread.onSpinWait();
            }
        }
    }
}
