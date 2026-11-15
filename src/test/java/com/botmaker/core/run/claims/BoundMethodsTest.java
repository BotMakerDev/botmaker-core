package com.botmaker.core.run.claims;

import com.botmaker.core.project.params.JavaParameter;
import com.botmaker.core.project.params.ParameterRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where a plugin's pool lands: the group method that carries the pooled parameter's mark, as compiled. */
class BoundMethodsTest {

    @TempDir
    Path root;

    @Test
    void aPluginsPooledParameterIsTheGroupMethodCarryingItsIdAsCompiled() throws IOException {
        Path sources = root.resolve("src");
        Path classes = root.resolve("classes");
        Path group = sources.resolve("params/g/a/Game.java");
        Files.createDirectories(group.getParent());
        Files.writeString(group, """
                package params.g.a;
                public final class Game {
                    public static String instance() { return ""; }
                    public static String instance(int other) { return ""; }
                }
                """);
        Files.createDirectories(classes);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, "-d", classes.toString(), group.toString()));

        JavaParameter pooled = parameter(group, "instance", "INSTANCE", true);
        Function<String, Optional<PoolSources.Target>> bound = BoundMethods.of(List.of(pooled), sources, classes);

        assertEquals(Optional.of(new PoolSources.Target("params.g.a.Game", "instance", "()Ljava/lang/String;", "")),
                bound.apply("INSTANCE"));
        assertTrue(bound.apply("CAPTURE").isEmpty(), "an id the bot has no method for");
        assertTrue(BoundMethods.of(List.of(parameter(group, "instance", "INSTANCE", false)), sources, classes)
                .apply("INSTANCE").isEmpty(), "a field is no group method");
        assertTrue(BoundMethods.of(List.of(pooled), sources, root.resolve("none")).apply("INSTANCE").isEmpty(),
                "no compile: nothing to rewrite");
        assertTrue(BoundMethods.of(List.of(parameter(root.resolve("elsewhere/Game.java"), "instance", "INSTANCE", true)),
                sources, classes).apply("INSTANCE").isEmpty(), "a file outside the sources");
    }

    private static JavaParameter parameter(Path file, String name, String id, boolean group) {
        return new JavaParameter(file, "Game", ParameterRow.named(name, "String").build(), null, "", true, "",
                "g:a", id, group);
    }
}
