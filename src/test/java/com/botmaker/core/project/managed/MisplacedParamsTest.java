package com.botmaker.core.project.managed;

import com.botmaker.core.project.ProjectConfig;
import com.botmaker.java.grammar.ParamsFitter;
import com.botmaker.java.index.BotIndex;
import com.botmaker.plugin.api.params.Param;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.host.PluginCoordinate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A plugin's parameter written in another group than its holder's moves there whole on bind (2026-11-05): its
 * method, comments and value included, in {@code params/} and in {@code starting-params/}.
 */
class MisplacedParamsTest {

    private static final PluginCoordinate TEST = new PluginCoordinate("com.github.BotMakerDev", "botmaker-test");

    static final ManagedValue<Boolean> SETUP = ManagedValue.param(TestValue.Id.DO_SETUP).in(TestValue.Holder.VALUES)
            .startsAs(MisplacedParamsTest::setup).shown("Run", "Setup", null).because("New.");
    static final ManagedValue<Integer> PAUSE = ManagedValue.param(TestValue.Id.PAUSE).in(TestValue.Holder.SDK)
            .startsAs(MisplacedParamsTest::pause).shown("Run", "Pause", null).because("New.");

    private static final Map<PluginCoordinate, List<ManagedValue<?>>> BY_PLUGIN = Map.of(TEST, List.of(SETUP, PAUSE));

    public static boolean setup() {
        return true;
    }

    public static int pause() {
        return 1;
    }

    /** A group holding {@code doSetup}, whose value is {@code setUp}, and {@code pause} when {@code withPause}. */
    private static String group(ProjectConfig config, String pkg, String className, String setUp, boolean withPause) {
        String pause = withPause ? """

                    @TestValue(TestValue.Id.PAUSE)
                    public static int pause() {
                        return 2;
                    }
                """ : "";
        return """
                package %s.params.%s;

                import %s;
                import %s;

                @Param
                public class %s {
                    @TestValue(TestValue.Id.DO_SETUP)
                    public static boolean doSetup() {
                        // ask once
                        return %s;
                    }
                %s}
                """.formatted(config.mainPackage(), pkg, TestValue.class.getCanonicalName(),
                Param.class.getCanonicalName(), className, setUp, pause);
    }

    private static String segment() {
        return ManagedHolders.segment(TEST);
    }

    private static Path params(ProjectConfig config, String folder, String className) {
        return config.parametersPackageDir().resolve(folder.replace('.', '/')).resolve(className + ".java")
                .toAbsolutePath().normalize();
    }

    private static Path starting(ProjectConfig config, String folder, String className) {
        return config.projectPath().resolve(ParamsFitter.STARTING).resolve(folder.replace('.', '/'))
                .resolve(className + ".java").toAbsolutePath().normalize();
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void aParamMovesToItsHoldersGroupWithItsBody(@TempDir Path dir) throws IOException {
        ProjectConfig config = ProjectConfig.forDirectory(dir.resolve("bot"));
        Path sdk = params(config, segment(), "Sdk");
        write(starting(config, segment(), "Sdk"), group(config, segment(), "Sdk", "true", true));

        MisplacedParams.Moves moves = MisplacedParams.plan(config,
                index(config, Map.of(sdk, group(config, segment(), "Sdk", "false", true))), BY_PLUGIN);

        String values = moves.rewrites().get(params(config, segment(), "Values"));
        assertTrue(values != null && values.contains("// ask once") && values.contains("return false;")
                && values.contains("@TestValue(TestValue.Id.DO_SETUP)") && values.contains("class Values"),
                moves.left() + "\n" + values);
        String left = moves.rewrites().get(sdk);
        assertTrue(left != null && !left.contains("doSetup") && left.contains("pause()"), left);
        String startingValues = moves.starting().get(starting(config, segment(), "Values"));
        assertTrue(startingValues != null && startingValues.contains("return true;"), startingValues);
        assertFalse(moves.starting().get(starting(config, segment(), "Sdk")).contains("doSetup"));
        assertTrue(moves.deleted().isEmpty(), moves.deleted().toString());
        assertEquals(1, moves.moved().size(), moves.moved().toString());
    }

    @Test
    void theEmptiedGroupPairIsDeleted(@TempDir Path dir) throws IOException {
        ProjectConfig config = ProjectConfig.forDirectory(dir.resolve("bot"));
        Path sdk = params(config, segment(), "Sdk");
        write(starting(config, segment(), "Sdk"), group(config, segment(), "Sdk", "true", false));

        MisplacedParams.Moves moves = MisplacedParams.plan(config,
                index(config, Map.of(sdk, group(config, segment(), "Sdk", "false", false))), BY_PLUGIN);

        assertEquals(Set.of(sdk, starting(config, segment(), "Sdk")), moves.deleted(), moves.left().toString());
        assertTrue(moves.rewrites().containsKey(params(config, segment(), "Values")));
        assertFalse(moves.rewrites().containsKey(sdk));
    }

    @Test
    void aMissingStartingCopySkipsTheMove(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir.resolve("bot"));
        Path sdk = params(config, segment(), "Sdk");

        MisplacedParams.Moves moves = MisplacedParams.plan(config,
                index(config, Map.of(sdk, group(config, segment(), "Sdk", "false", true))), BY_PLUGIN);

        assertTrue(moves.isEmpty(), moves.rewrites().keySet().toString());
        assertTrue(moves.left().getFirst().contains("starting copy"), moves.left().toString());
    }

    @Test
    void aGroupInAnotherPackageIsFoundByItsMarks(@TempDir Path dir) throws IOException {
        ProjectConfig config = ProjectConfig.forDirectory(dir.resolve("bot"));
        Path old = params(config, "test", "Old");
        write(starting(config, "test", "Old"), group(config, "test", "Old", "true", false));

        MisplacedParams.Moves moves = MisplacedParams.plan(config,
                index(config, Map.of(old, group(config, "test", "Old", "false", false))), BY_PLUGIN);

        assertTrue(moves.rewrites().get(params(config, segment(), "Values")).contains("return false;"),
                moves.left().toString());
        assertEquals(Set.of(old, starting(config, "test", "Old")), moves.deleted());
    }

    /** One move empties a group another move then fills: the emptied starting copy is not read back from disk. */
    @Test
    void aGroupOneMoveEmptiesAnotherFills(@TempDir Path dir) throws IOException {
        ProjectConfig config = ProjectConfig.forDirectory(dir.resolve("bot"));
        Path sdk = params(config, segment(), "Sdk");
        Path old = params(config, "test", "Old");
        String oldPause = """
                package %s.params.test;

                import %s;
                import %s;

                @Param
                public class Old {
                    @TestValue(TestValue.Id.PAUSE)
                    public static int pause() {
                        return 2;
                    }
                }
                """.formatted(config.mainPackage(), TestValue.class.getCanonicalName(), Param.class.getCanonicalName());
        write(starting(config, segment(), "Sdk"), group(config, segment(), "Sdk", "true", false));
        write(starting(config, "test", "Old"), oldPause);

        MisplacedParams.Moves moves = MisplacedParams.plan(config, index(config,
                Map.of(sdk, group(config, segment(), "Sdk", "false", false), old, oldPause)), BY_PLUGIN);

        String startingSdk = moves.starting().get(starting(config, segment(), "Sdk"));
        assertTrue(startingSdk != null && startingSdk.contains("pause()") && !startingSdk.contains("doSetup"),
                moves.left() + "\n" + startingSdk);
        String paramsSdk = moves.rewrites().get(sdk);
        assertTrue(paramsSdk != null && paramsSdk.contains("pause()") && !paramsSdk.contains("doSetup"), paramsSdk);
        assertEquals(Set.of(old, starting(config, "test", "Old")), moves.deleted());
    }

    private static BotIndex index(ProjectConfig config, Map<Path, String> sources) {
        return BotIndex.over(sources, List.of(locationOf(Param.class), locationOf(TestValue.class)),
                config.sourceRoot());
    }

    private static String locationOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
