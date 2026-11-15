package com.botmaker.core.project.params;

import com.botmaker.core.TestSources;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.index.BotIndex;
import com.botmaker.java.index.PoolReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The core lists the bot's pools, an element of the bot's own records included (2026-11-15). */
class JavaParametersPoolsTest {

    @Test
    void theBotsPoolsAreListedARecordOfItsOwnAnElement(@TempDir Path root) {
        Map<Path, String> sources = new LinkedHashMap<>();
        sources.put(root.resolve("com/refbot/Login.java"), """
                package com.refbot;

                public record Login(String name, int slot) {}
                """);
        sources.put(root.resolve("com/refbot/params/bot/Farm.java"), """
                package com.refbot.params.bot;

                import com.botmaker.plugin.api.params.Param;
                import com.refbot.Login;

                @Param
                public final class Farm {
                    public Login login() { return new Login("main", 1); }
                    public String note() { return ""; }
                }
                """);
        sources.put(root.resolve("com/refbot/params/bot/FarmPools.java"), """
                package com.refbot.params.bot;

                import com.botmaker.plugin.api.params.Pool;
                import com.botmaker.plugin.api.params.Pools;
                import com.refbot.Login;

                @Pools
                public final class FarmPools {
                    public Pool<Login> logins() {
                        return Pool.of(new Login("main", 1), new Login("alt", 2)).fills(Farm::login);
                    }

                    public Pool<String> notes() { return Pool.of("a").fills(Farm::login); }
                }
                """);
        BotIndex index = BotIndex.uncached(sources, List.of(TestSources.contractOnClasspath()), root);

        List<PoolReader.Reading> pools = JavaParameters.pools(index, TestValues.GRAMMAR);

        assertEquals(List.of("logins", "notes"), pools.stream().map(PoolReader.Reading::method).toList());
        PoolReader.Reading logins = pools.getFirst();
        assertTrue(logins.reads(), logins.problem());
        assertEquals(List.of("new Login(\"main\", 1)", "new Login(\"alt\", 2)"),
                logins.elements().stream().map(JavaValue::source).toList());
        assertEquals(List.of("com.refbot.Login"), logins.elements().getFirst().imports());
        assertEquals("()Lcom/refbot/Login;", logins.fills().orElseThrow().descriptor());
        assertFalse(pools.get(1).reads(), "a String pool filling a Login");
        assertTrue(pools.get(1).elements().isEmpty());
    }
}
