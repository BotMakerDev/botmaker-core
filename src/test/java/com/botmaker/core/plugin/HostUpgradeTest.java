package com.botmaker.core.plugin;

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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A newer plugin version repairs its host folder as a bot's values are repaired on its upgrade (review item 5). */
public class HostUpgradeTest {

    @ManagedMarker
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface PcSetting {

        Id value();

        enum Id implements ManagedId<Holder> { GREETING, SPEED, RETIRED }

        enum Holder implements HostHolder { EMULATORS }
    }

    static final ManagedMethod<String> GREETING = ManagedValue.method(PcSetting.Id.GREETING)
            .in(PcSetting.Holder.EMULATORS).holds(String.class, "hello").because("This PC's greeting.");
    static final ManagedMethod<Integer> SPEED = ManagedValue.method(PcSetting.Id.SPEED)
            .in(PcSetting.Holder.EMULATORS).holds(Integer.class, 3).because("This PC's speed.");
    static final ManagedMethod<String> RETIRED = ManagedValue.method(PcSetting.Id.RETIRED)
            .in(PcSetting.Holder.EMULATORS).holds(String.class, "old").because("Gone in 2.0.0.");

    private static final PluginCoordinate PLUGIN = new PluginCoordinate("com.example", "upgraded");

    private static final List<Path> CLASSPATH = Arrays.stream(System.getProperty("java.class.path")
            .split(File.pathSeparator)).map(Path::of).toList();

    @TempDir
    Path root;

    private HostValues at(String version, ManagedValue<?>... declared) {
        return new HostValues(root, PLUGIN, version, List.of(declared), ValueGrammar.empty(), null, () -> CLASSPATH);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
        try (Stream<Path> kept = Files.list(next.folder().resolve(HostValues.HISTORY))) {
            assertTrue(kept.anyMatch(each -> read(each).contains("GoneIn2.SPEED")), "the class before the repair is kept");
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
