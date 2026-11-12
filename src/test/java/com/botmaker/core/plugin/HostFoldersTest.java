package com.botmaker.core.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.source.PluginValues;
import com.botmaker.plugin.host.LoadedPlugin;
import com.botmaker.plugin.host.PluginCoordinate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The newest version of a plugin bound on this PC serves its values of this PC alone. */
class HostFoldersTest {

    private static final List<String> CLASSPATH =
            Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator));

    @TempDir
    Path root;

    @BeforeEach
    void at() {
        HostFolders.rootAt(root);
    }

    @AfterEach
    void back() {
        HostFolders.rootAt(null);
    }

    /** The test plugin at {@code version}, its jar in the repository layout so its version is read off the path. */
    private static LoadedPlugin at(String version) {
        StudioPlugin plugin = TestPlugins.plugin(StudioPlugin.declare().values(TestPlugins.holder(
                () -> List.of(HostValuesTest.GREETING))));
        Path jar = Path.of("repo", "com", "example", "pc", version, "pc-" + version + ".jar").toAbsolutePath();
        return new LoadedPlugin(TestPlugins.coordinate("pc"), "pc", jar, plugin);
    }

    @Test
    void aHomeBindServesWithNoProject() {
        LoadedPlugin home = at("2.0.0");
        HostFolders.bound(List.of(home), CLASSPATH);

        PluginValues values = HostFolders.of(home);
        assertEquals(Optional.empty(), values.create(HostValuesTest.GREETING));
        assertTrue(values.ids().contains(HostValuesTest.GREETING.id()), values.ids().toString());
        assertEquals("2.0.0", ((HostValues) values).recorded());
    }

    @Test
    void aProjectsOlderVersionGetsTheNewestCopy() {
        LoadedPlugin newest = at("2.0.0");
        HostFolders.bound(List.of(newest), CLASSPATH);
        PluginValues served = HostFolders.of(newest);

        LoadedPlugin older = at("1.4.0");
        HostFolders.bound(List.of(older), CLASSPATH);
        assertSame(served, HostFolders.of(older), "the older version reads and writes through the newest's copy");
        assertEquals("2.0.0", HostFolders.servingVersion(older.coordinate()));

        HostFolders.bound(List.of(at("2.1.0")), CLASSPATH);
        assertEquals("2.1.0", HostFolders.servingVersion(older.coordinate()), "a newer bind takes over");
    }

    @Test
    void aNewerVersionKeepingNothingEndsTheOlderCopy() {
        HostFolders.bound(List.of(at("2.0.0")), CLASSPATH);
        StudioPlugin none = TestPlugins.plugin(StudioPlugin.declare());
        Path jar = Path.of("repo", "com", "example", "pc", "3.0.0", "pc-3.0.0.jar").toAbsolutePath();
        LoadedPlugin newer = new LoadedPlugin(TestPlugins.coordinate("pc"), "pc", jar, none);
        HostFolders.bound(List.of(newer), CLASSPATH);
        assertSame(PluginValues.NONE, HostFolders.of(newer));
    }

    @Test
    void botMakersTwoGroupIdsKeepOneFolder() {
        PluginCoordinate old = new PluginCoordinate("com.github.LiQiyeDev", "sdk");
        PluginCoordinate now = new PluginCoordinate("com.github.BotMakerDev", "sdk");
        assertEquals(HostFolders.canonical(now), HostFolders.canonical(old));
        assertEquals(new PluginCoordinate("com.example", "pc"), HostFolders.canonical(TestPlugins.coordinate("pc")));
    }

    @Test
    void aPluginWithNoValuesOfThisPcIsServedNone() {
        StudioPlugin plain = TestPlugins.plugin(StudioPlugin.declare());
        LoadedPlugin loaded = TestPlugins.loaded("plain", plain);
        HostFolders.bound(List.of(loaded), CLASSPATH);
        assertSame(PluginValues.NONE, HostFolders.of(loaded));
    }
}
