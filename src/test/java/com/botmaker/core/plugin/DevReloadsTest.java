// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.run.DevReload;
import com.botmaker.plugin.api.run.RunEntry;
import com.botmaker.plugin.host.PluginCoordinate;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A plugin's restart of its own run on its rebuilt jar: what is checked before anything stops, then the order. */
class DevReloadsTest {

    /** A run's main, for the entries. */
    public static final class Explore {
        public static void main(String[] args) {
        }
    }

    private static final RunEntry EXPLORE = RunEntry.of("Keep exploring", Explore::main).offBar();
    private static final PluginCoordinate SDK = new PluginCoordinate("com.github.BotMakerDev", "botmaker-sdk");
    private static final DevReloads.Bound SERVES = new DevReloads.Bound(
            List.of(new DevReloads.Served(SDK, "BotMaker SDK", List.of(EXPLORE))), List.of());

    /** A project that writes down what it is asked, its run going until it is stopped unless {@code stops} is false. */
    private static final class Project implements DevReloads.Project {
        final List<String> did = new CopyOnWriteArrayList<>();
        final boolean dev;
        final boolean stops;
        final DevReloads.Bound rebinds;
        volatile boolean running = true;

        Project(boolean dev, boolean stops, DevReloads.Bound rebinds) {
            this.dev = dev;
            this.stops = stops;
            this.rebinds = rebinds;
        }

        @Override public boolean devMode() { return dev; }
        @Override public List<String> classpath() { return List.of("sdk.jar"); }
        @Override public boolean running() { return running; }

        @Override
        public void stop() {
            did.add("stop");
            if (stops) running = false;
        }

        @Override
        public DevReloads.Bound rebind() {
            did.add("rebind");
            return rebinds;
        }

        @Override public void start(String title, String mainClass) { did.add("start " + title + " " + mainClass); }
        @Override public void status(String message) { did.add("status " + message); }
    }

    private static DevReload reload(Project project, DevReloads.Bound trial) {
        return DevReloads.reload(project, Optional.of(SDK), EXPLORE, classpath -> trial, Runnable::run,
                Duration.ofMillis(300));
    }

    @Test
    void outOfDevModeItRefuses() {
        Project project = new Project(false, true, SERVES);

        DevReload answer = reload(project, SERVES);

        assertTrue(assertInstanceOf(DevReload.Refused.class, answer).why().contains("Dev mode is off"));
        assertEquals(List.of(), project.did);
    }

    @Test
    void aReloadThatDoesNotLoadKeepsTheRunHeld() {
        Project project = new Project(true, true, SERVES);
        DevReloads.Bound broken = new DevReloads.Bound(List.of(),
                List.of("com.botmaker.sdk.plugin.SdkPlugin — p/Gone is not on the classpath"));

        DevReload answer = reload(project, broken);

        String why = assertInstanceOf(DevReload.Refused.class, answer).why();
        assertTrue(why.contains("botmaker-sdk did not load: com.botmaker.sdk.plugin.SdkPlugin — p/Gone"), why);
        assertTrue(why.endsWith("Nothing was stopped."), why);
        assertEquals(List.of(), project.did, "the run goes on, held on its fault");
        assertTrue(project.running);
    }

    @Test
    void anUnknownEntryIsRefused() {
        Project project = new Project(true, true, SERVES);
        DevReloads.Bound none = new DevReloads.Bound(
                List.of(new DevReloads.Served(SDK, "BotMaker SDK", List.of())), List.of());

        DevReload answer = reload(project, none);

        String why = assertInstanceOf(DevReload.Refused.class, answer).why();
        assertTrue(why.contains("BotMaker SDK declares no run \"Keep exploring\""), why);
        assertEquals(List.of(), project.did);
    }

    @Test
    void aCallOfTheHostsOwnIsRefused() {
        Project project = new Project(true, true, SERVES);

        DevReload answer = DevReloads.reload(project, Optional.empty(), EXPLORE, classpath -> SERVES, Runnable::run,
                Duration.ofMillis(300));

        assertInstanceOf(DevReload.Refused.class, answer);
        assertEquals(List.of(), project.did);
    }

    @Test
    void itStopsRebindsAndStartsTheEntry() {
        Project project = new Project(true, true, SERVES);

        DevReload answer = reload(project, SERVES);

        assertInstanceOf(DevReload.Started.class, answer);
        assertEquals(List.of("stop", "rebind", "start Keep exploring " + Explore.class.getName()), project.did);
    }

    @Test
    void aRunThatDoesNotStopIsNotReloaded() {
        Project project = new Project(true, false, SERVES);

        reload(project, SERVES);

        assertEquals("stop", project.did.getFirst());
        assertEquals(2, project.did.size(), project.did.toString());
        assertTrue(project.did.get(1).startsWith("status Dev reload: the run did not stop"), project.did.toString());
    }

    @Test
    void aRebindThatLosesThePluginSaysSoAndStartsNothing() {
        Project project = new Project(true, true, new DevReloads.Bound(List.of(), List.of("p.SdkPlugin — boom")));

        reload(project, SERVES);

        assertEquals("rebind", project.did.get(1));
        assertTrue(project.did.get(2).contains("the plugins reloaded, but com.github.BotMakerDev:botmaker-sdk did not "
                + "load: p.SdkPlugin — boom"), project.did.toString());
        assertEquals(3, project.did.size());
    }

    @Test
    void aSecondReloadWhileTheFirstGoesIsRefused() {
        Project project = new Project(true, true, SERVES);
        List<Runnable> held = new CopyOnWriteArrayList<>();

        DevReload first = DevReloads.reload(project, Optional.of(SDK), EXPLORE, classpath -> SERVES, held::add,
                Duration.ofMillis(300));
        DevReload second = reload(project, SERVES);
        held.forEach(Runnable::run);
        DevReload third = reload(project, SERVES);

        assertInstanceOf(DevReload.Started.class, first);
        assertTrue(assertInstanceOf(DevReload.Refused.class, second).why().contains("under way already"));
        assertInstanceOf(DevReload.Started.class, third);
    }

    @Test
    void noProjectOpenRefusesHere() {
        DevReloads.clear();

        assertEquals(DevReload.REFUSED_HERE, DevReloads.reload(Optional.empty(), EXPLORE));
    }

    @Test
    void aClientsCopyForwardsItsReloadToTheCore() {
        DevReloads.clear();
        List<String> forwarded = new CopyOnWriteArrayList<>();
        DevReloads.installForwarding((caller, entry) -> {
            forwarded.add(caller.artifactId() + " " + entry.title());
            return new DevReload.Started();
        });
        try {
            DevReload answer = DevReloads.reload(
                    Optional.of(TestPlugins.loaded("sdk", StudioPlugin.declare())), EXPLORE);

            assertInstanceOf(DevReload.Started.class, answer);
            assertEquals(List.of("sdk Keep exploring"), forwarded);
            assertEquals(DevReload.REFUSED_HERE, DevReloads.reload(Optional.empty(), EXPLORE));
        } finally {
            DevReloads.installForwarding(null);
        }
    }
}
