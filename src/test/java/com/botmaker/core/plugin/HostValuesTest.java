package com.botmaker.core.plugin;

import com.botmaker.java.grammar.ValueGrammar;
import com.botmaker.plugin.api.managed.HostHolder;
import com.botmaker.plugin.api.managed.ManagedId;
import com.botmaker.plugin.api.managed.ManagedMarker;
import com.botmaker.plugin.api.slot.ValueContext;
import com.botmaker.plugin.api.source.ManagedMethod;
import com.botmaker.plugin.api.source.ManagedOpenSet;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A plugin's values of this PC, kept as Java in its host folder. */
public class HostValuesTest {

    @ManagedMarker
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface PcValue {

        Id value();

        enum Id implements ManagedId<Holder> { GREETING, SPEED, NAMES }

        enum Holder implements HostHolder { EMULATORS, NAMES }
    }

    @ManagedMarker
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface BotValue {

        Id value();

        enum Id implements ManagedId<Holder> { SETTINGS }

        enum Holder { SDK }
    }

    public static final ManagedMethod<String> GREETING = ManagedValue.method(PcValue.Id.GREETING)
            .in(PcValue.Holder.EMULATORS).holds(String.class, "hello").because("This PC's greeting.");
    static final ManagedMethod<Integer> SPEED = ManagedValue.method(PcValue.Id.SPEED)
            .in(PcValue.Holder.EMULATORS).holds(Integer.class, 3).because("This PC's speed.");
    static final ManagedOpenSet.OfConstants<String> NAMES = ManagedValue.openSet(PcValue.Id.NAMES)
            .of(String.class).in(PcValue.Holder.NAMES).because("This PC's names.");
    static final ManagedMethod<String> SETTINGS = ManagedValue.method(BotValue.Id.SETTINGS)
            .in(BotValue.Holder.SDK).holds(String.class, "").because("The bot's.");

    static final PluginCoordinate PLUGIN = new PluginCoordinate("com.github.Example", "my-plugin");

    @TempDir
    Path root;

    private HostValues values(String version) {
        List<Path> classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(Path::of).toList();
        return new HostValues(root, PLUGIN, version, List.of(GREETING, SPEED, NAMES, SETTINGS),
                ValueGrammar.empty(), null, () -> classpath);
    }

    private static String text(Path file) throws Exception {
        return Files.readString(file);
    }

    @Test
    void createThenOpenThenWriteRoundTrips() throws Exception {
        HostValues values = values("1.0.0");
        assertEquals(List.of(), values.ids());
        assertTrue(values.open(GREETING).isEmpty());

        assertEquals(Optional.empty(), values.create(GREETING));
        Path file = values.folder().resolve("Emulators.java");
        assertTrue(Files.isRegularFile(file), "the holder is written");
        assertTrue(text(file).startsWith("package com_github_example.my_plugin;"), text(file));
        assertEquals(root.resolve("plugins/com_github_example/my_plugin"), values.folder());
        assertTrue(values.ids().contains(GREETING.id()), values.ids().toString());
        assertEquals("1.0.0", values.recorded());

        ValueContext opened = values.open(GREETING).orElseThrow();
        opened.write("goodbye");
        assertTrue(text(file).contains("\"goodbye\""), text(file));
        assertTrue(Files.isRegularFile(values.folder().resolve(".history/Emulators.1.java")),
                "the replaced class is kept");
    }

    @Test
    void aMissingMethodIsAddedToItsHolder() throws Exception {
        HostValues values = values("1.0.0");
        values.create(GREETING);
        Path file = values.folder().resolve("Emulators.java");
        if (values.ids().contains(SPEED.id())) {
            // The holder was written with every value it holds; drop one to see it come back.
            Files.writeString(file, text(file).replaceAll("(?s)\\s*@[\\w.]*PcValue\\([\\w.]*SPEED\\).*?}\\s*", "\n"));
        }
        assertEquals(Optional.empty(), values.create(SPEED));
        assertTrue(values.ids().contains(SPEED.id()), values.ids().toString());
    }

    @Test
    void wrongHolderRefused() {
        HostValues values = values("1.0.0");
        Optional<String> refused = values.create(SETTINGS);
        assertTrue(refused.orElseThrow().contains("bot's value"), refused.get());
        assertTrue(values.open(SETTINGS).isEmpty());
        assertFalse(Files.exists(values.folder()), "nothing is written");

        assertTrue(HostValues.notTheBots(GREETING).contains("hostValues()"));
    }

    @Test
    void anOpenSetIsRefusedWithWords() {
        Optional<String> refused = values("1.0.0").create(NAMES);
        assertTrue(refused.orElseThrow().contains("method values only"), refused.get());
    }

    @Test
    void uncompilableRefused() throws Exception {
        HostValues values = values("1.0.0");
        values.create(GREETING);
        Path file = values.folder().resolve("Emulators.java");
        String before = text(file);
        // A helper class beside it that breaks once the folder is compiled with the next write.
        Files.writeString(values.folder().resolve("Broken.java"),
                "package com_github_example.my_plugin; class Broken { int x = \"no\"; }");

        ValueContext opened = values.open(GREETING).orElseThrow();
        opened.write("again");
        assertEquals(before, text(file), "the file is put back");
        assertFalse(Files.exists(values.folder().resolve(HostValues.HISTORY)), "a refused write keeps no copy");
    }

    @Test
    void olderVersionRefused() throws Exception {
        values("2.0.0").create(GREETING);
        HostValues older = values("1.9.9");
        Path file = older.folder().resolve("Emulators.java");
        String before = text(file);

        older.open(GREETING).orElseThrow().write("older");
        assertEquals(before, text(file));
        Files.delete(file);
        Optional<String> refused = older.create(GREETING);
        assertTrue(refused.orElseThrow().contains("newer"), refused.toString());
        assertFalse(Files.exists(file));
        assertEquals("2.0.0", older.recorded());
        assertTrue(values("2.0.0-SNAPSHOT").create(GREETING).isPresent(), "a dev build never writes over its release");

        values("2.0.0").create(GREETING);

        values("2.1.0-SNAPSHOT").open(GREETING).orElseThrow().write("newer");
        assertEquals("2.1.0-SNAPSHOT", older.recorded());
    }

    @Test
    void aDeletedHolderOpensEmptyAndIsWrittenAgain() throws Exception {
        HostValues values = values("1.0.0");
        values.create(GREETING);
        Files.delete(values.folder().resolve("Emulators.java"));

        assertTrue(values.open(GREETING).isEmpty());
        assertEquals(Optional.empty(), values.create(GREETING));
        assertTrue(values.open(GREETING).isPresent());
    }

    @Test
    void historyKeepsTheLastTwenty() throws Exception {
        HostValues values = values("1.0.0");
        values.create(GREETING);
        for (int i = 0; i < 25; i++) values.open(GREETING).orElseThrow().write("v" + i);
        try (Stream<Path> kept = Files.list(values.folder().resolve(HostValues.HISTORY))) {
            assertEquals(HostValues.KEPT, kept.count());
        }
        assertTrue(text(values.folder().resolve("Emulators.java")).contains("\"v24\""));
    }

    @Test
    void versionsCompareByNumber() {
        assertTrue(HostValues.compare("1.10.0", "1.9.2") > 0);
        assertTrue(HostValues.compare("", "0.0.1") < 0);
        assertTrue(HostValues.compare("1.2.0-SNAPSHOT", "1.2.0") < 0);
        assertTrue(HostValues.compare("1.2.0-SNAPSHOT", "1.1.9") > 0);
        assertEquals(0, HostValues.compare("1.2.0", "1.2.0"));
    }
}
