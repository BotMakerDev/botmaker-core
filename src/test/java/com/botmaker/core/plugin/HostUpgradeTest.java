package com.botmaker.core.plugin;

import com.botmaker.core.project.vcs.ProjectVcs;
import com.botmaker.java.grammar.ValueGrammar;
import com.botmaker.plugin.api.managed.HostHolder;
import com.botmaker.plugin.api.managed.ManagedId;
import com.botmaker.plugin.api.managed.ManagedMarker;
import com.botmaker.plugin.api.source.ManagedMethod;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.host.PluginCoordinate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A newer plugin version repairs its host folder as a bot's values are repaired on its upgrade (review item 5). */
public class HostUpgradeTest {

    @ManagedMarker
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface PcSetting {

        Id value();

        enum Id implements ManagedId<Holder> { GREETING, SPEED, RETIRED, ADDED }

        enum Holder implements HostHolder { EMULATORS }
    }

    static final ManagedMethod<String> GREETING = ManagedValue.method(PcSetting.Id.GREETING)
            .in(PcSetting.Holder.EMULATORS).holds(String.class, "hello").because("This PC's greeting.");
    static final ManagedMethod<Integer> SPEED = ManagedValue.method(PcSetting.Id.SPEED)
            .in(PcSetting.Holder.EMULATORS).holds(Integer.class, 3).because("This PC's speed.");
    static final ManagedMethod<String> RETIRED = ManagedValue.method(PcSetting.Id.RETIRED)
            .in(PcSetting.Holder.EMULATORS).holds(String.class, "old").because("Gone in 2.0.0.");
    static final ManagedMethod<String> ADDED = ManagedValue.method(PcSetting.Id.ADDED)
            .in(PcSetting.Holder.EMULATORS).holds(String.class, "new").because("New in 2.0.0.");

    private static final PluginCoordinate PLUGIN = new PluginCoordinate("com.example", "upgraded");

    private static final List<Path> CLASSPATH = Arrays.stream(System.getProperty("java.class.path")
            .split(File.pathSeparator)).map(Path::of).toList();

    @TempDir
    Path root;

    private HostValues at(String version, ManagedValue<?>... declared) {
        return new HostValues(root, PLUGIN, version, List.of(declared), ValueGrammar.empty(), null, () -> CLASSPATH);
    }

    /** Version 1.0.0's folder, its speed then broken by what 2.0.0 no longer has. */
    private Path written() throws Exception {
        HostValues first = at("1.0.0", GREETING, SPEED, RETIRED);
        for (ManagedMethod<?> each : List.of(GREETING, SPEED, RETIRED)) {
            assertEquals(Optional.empty(), first.create(each));
        }
        Path file = first.sourcesDir().resolve("Emulators.java");
        String text = Files.readString(file);
        assertTrue(text.contains("return 3;"), text);
        Files.writeString(file, text.replace("return 3;", "return GoneIn2.SPEED;"));
        return file;
    }

    @Test
    void aNewerVersionRemovesAGoneIdAndResetsWhatNoLongerCompiles() throws Exception {
        Path file = written();

        HostValues next = at("2.0.0", GREETING, SPEED);
        assertEquals(List.of(GREETING.id(), SPEED.id()), next.ids());
        String text = Files.readString(file);
        assertFalse(text.contains("RETIRED"), text);
        assertTrue(text.contains("return 3;"), "the speed takes its first value again: " + text);
        assertTrue(text.contains("@Refactor"), "and is marked for review: " + text);
        assertTrue(text.contains("\"hello\""), "a value that still compiles is left: " + text);
        assertEquals("2.0.0", next.recorded());
        ProjectVcs vcs = new ProjectVcs(next.folder());
        List<String> titles = vcs.history().stream().map(ProjectVcs.CommitInfo::title).toList();
        assertEquals(List.of("Upgraded to 2.0.0", "Before the upgrade to 2.0.0"), titles.subList(0, 2),
                "the class changed by hand is committed before the repair, and the repair is a commit: " + titles);
        assertTrue(vcs.status().isClean(), "nothing the repair changed is left out of its commit");
    }

    @Test
    void theCommitBeforeTheUpgradeHoldsTheOldValues() throws Exception {
        Path file = written();
        String old = Files.readString(file);
        HostValues next = at("2.0.0", GREETING, SPEED);
        next.ids();
        ProjectVcs vcs = new ProjectVcs(next.folder());
        ProjectVcs.CommitInfo before = vcs.history().stream()
                .filter(each -> each.title().equals("Before the upgrade to 2.0.0")).findFirst().orElseThrow();

        vcs.restoreTo(before.sha());
        assertEquals(old, Files.readString(file), "the retired id and the broken speed, as 1.0.0 left them");
        assertEquals("1.0.0", next.recorded(), "and the pom names the version that wrote them");
    }

    @Test
    void aRepairThatFailsPutsBackWhatItChangedAndServesReadOnly() throws Exception {
        Path file = written();
        String old = Files.readString(file);
        assumeTrue(file.toFile().setWritable(false) && !Files.isWritable(file), "this file system cannot refuse a write");
        try {
            HostValues next = at("2.0.0", GREETING, SPEED, ADDED);
            next.ids();
            assertEquals(old, Files.readString(file), "a repair that fails changes nothing");
            assertEquals("1.0.0", next.recorded(), "and records nothing, so the next load repairs again");

            file.toFile().setWritable(true);
            Optional<String> refused = next.create(ADDED);
            assertTrue(refused.orElse("").contains("read-only"), "a new value is refused, saying why: " + refused);
            next.open(GREETING).orElseThrow().write("after");
            assertEquals(old, Files.readString(file), "nor is a write taken");
            ProjectVcs vcs = new ProjectVcs(next.folder());
            String head = vcs.head();
            next.history().undo();
            assertEquals(head, vcs.head(), "nor an undo");
            assertEquals(old, Files.readString(file));
        } finally {
            file.toFile().setWritable(true);
        }
    }

    @Test
    void theSameVersionLeavesTheFolderAlone() throws Exception {
        Path file = written();
        String before = Files.readString(file);

        HostValues same = at("1.0.0", GREETING, SPEED);
        same.ids();
        assertEquals(before, Files.readString(file));
        assertEquals("1.0.0", same.recorded());
    }

    @Test
    void anOlderJarBesideTheNewOneGivesTheRulesTheirClasspath(@TempDir Path repo) throws Exception {
        Path newer = repo.resolve("upgraded/2.0.0/upgraded-2.0.0.jar");
        Path older = repo.resolve("upgraded/1.0.0/upgraded-1.0.0.jar");
        Files.createDirectories(newer.getParent());
        Files.createDirectories(older.getParent());
        Files.writeString(newer, "");
        List<String> classpath = List.of("other.jar", newer.toString());
        assertEquals(Optional.empty(), HostUpgrade.olderClasspath(classpath, "upgraded", "1.0.0", "2.0.0"),
                "no older jar on this PC: no rules");

        Files.writeString(older, "");
        assertEquals(Optional.of(List.of("other.jar", older.toString())),
                HostUpgrade.olderClasspath(classpath, "upgraded", "1.0.0", "2.0.0"));
    }
}
