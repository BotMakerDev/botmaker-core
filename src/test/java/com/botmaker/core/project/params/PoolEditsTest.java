// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.project.params;

import com.botmaker.core.TestSources;
import com.botmaker.java.grammar.ValueTypes;
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

/** The Parameters window's pool edits read back as they were written (2026-11-16). */
class PoolEditsTest {

    private static final String FARM = """
            package com.refbot.params.bot;

            import com.botmaker.plugin.api.params.Param;
            import java.util.List;

            @Param
            public final class Farm {
                public String guild() { return "Night Owls"; }
                public List<String> team() { return List.of("ARIA"); }
                public int retries() { return 3; }
            }
            """;

    @Test
    void aPutKeepsTheJavadocAndTheStartingCopyItsListedElements(@TempDir Path root) {
        String group = PoolEdits.emptyGroup("com.refbot.params.bot", "FarmPools");
        PoolEdits.Shape guild = new PoolEdits.Shape("FarmPools", "guild", "Farm", "guild", String.class, String.class,
                false, List.of("\"A\"", "\"B\""), List.of(), 0, 1, false);
        String written = PoolEdits.put(group, guild).replace("    public Pool<String> guild()",
                "    /** The guilds of this PC. */\n    public Pool<String> guild()");

        String starting = PoolEdits.putKeepingListed(written, guild.blank().withEach(4));

        assertTrue(starting.contains("The guilds of this PC."), starting);
        PoolReader.Reading read = read(root, starting).getFirst();
        assertTrue(read.reads(), read.problem());
        assertEquals(4, read.each(), "the setting followed");
        assertEquals(List.of("\"A\"", "\"B\""), PoolEdits.Shape.of(read).orElseThrow().elements(),
                "what the starting copy listed stays");
    }

    @Test
    void aPrimitiveParameterIsDealtBoxedAndATakeNeverPassesWhatIsListed(@TempDir Path root) {
        String group = PoolEdits.emptyGroup("com.refbot.params.bot", "FarmPools");
        String retries = PoolEdits.put(group, new PoolEdits.Shape("FarmPools", "retries", "Farm", "retries",
                int.class, int.class, false, List.of("3", "5"), List.of(), 0, 1, false));
        PoolEdits.Shape team = new PoolEdits.Shape("FarmPools", "team", "Farm", "team",
                ValueTypes.listOf(String.class), String.class, false, List.of("\"A\"", "\"B\"", "\"C\""), List.of(),
                3, 1, false).withElements(List.of("\"A\"", "\"B\""), List.of());
        String both = PoolEdits.put(retries, team);

        List<PoolReader.Reading> pools = read(root, both);
        assertTrue(pools.getFirst().reads(), pools.getFirst().problem());
        assertTrue(both.contains("Pool<Integer> retries()"), both);
        assertEquals(2, team.take());
        assertTrue(pools.get(1).reads(), pools.get(1).problem());
    }

    @Test
    void aPoolPutIsReadBackWithItsSettings(@TempDir Path root) {
        String group = PoolEdits.emptyGroup("com.refbot.params.bot", "FarmPools");
        String guilds = PoolEdits.put(group, new PoolEdits.Shape("FarmPools", "guild", "Farm", "guild", String.class,
                String.class, false, List.of("\"Night Owls\"", "\"Dawn Riders\""), List.of(), 0, 3, false));
        String both = PoolEdits.put(guilds, new PoolEdits.Shape("FarmPools", "team", "Farm", "team",
                ValueTypes.listOf(String.class), String.class, false, List.of("\"ARIA\"", "\"BRAM\"", "\"CYRA\""),
                List.of(), 2, 1, true));

        List<PoolReader.Reading> pools = read(root, both);

        assertEquals(List.of("guild", "team"), pools.stream().map(PoolReader.Reading::method).toList());
        PoolReader.Reading guild = pools.getFirst();
        assertTrue(guild.reads(), guild.problem());
        assertEquals(3, guild.each());
        assertFalse(guild.rotates());
        PoolReader.Reading team = pools.get(1);
        assertTrue(team.reads(), team.problem());
        assertEquals(2, team.take());
        assertTrue(team.rotates());
        assertEquals(3, team.elements().size());
        assertEquals(PoolEdits.Shape.of(team).orElseThrow().elements(), List.of("\"ARIA\"", "\"BRAM\"", "\"CYRA\""));
    }

    @Test
    void aPutReplacesThePoolInPlaceAndARemoveTakesItOut(@TempDir Path root) {
        String group = PoolEdits.emptyGroup("com.refbot.params.bot", "FarmPools");
        PoolEdits.Shape guild = new PoolEdits.Shape("FarmPools", "guild", "Farm", "guild", String.class, String.class,
                false, List.of("\"A\""), List.of(), 0, 1, false);
        String once = PoolEdits.put(PoolEdits.put(group, guild), new PoolEdits.Shape("FarmPools", "team", "Farm",
                "team", ValueTypes.listOf(String.class), String.class, false, List.of(), List.of(), 1, 1, false));
        String again = PoolEdits.put(once, new PoolEdits.Shape("FarmPools", "guild", "Farm", "guild", String.class,
                String.class, false, List.of("\"B\""), List.of(), 0, 1, true));

        List<PoolReader.Reading> pools = read(root, again);
        assertEquals(List.of("guild", "team"), pools.stream().map(PoolReader.Reading::method).toList(),
                "replaced where it was");
        assertTrue(pools.getFirst().rotates());
        assertTrue(pools.get(1).reads(), pools.get(1).problem());
        assertTrue(pools.get(1).elements().isEmpty(), "a blank pool reads, dealing nothing");

        String removed = PoolEdits.remove(again, "FarmPools", "guild");
        assertFalse(PoolEdits.has(removed, "FarmPools", "guild"));
        assertTrue(PoolEdits.has(removed, "FarmPools", "team"));
        assertEquals(removed, PoolEdits.remove(removed, "FarmPools", "guild"));
    }

    private static List<PoolReader.Reading> read(Path root, String pools) {
        Map<Path, String> sources = new LinkedHashMap<>();
        sources.put(root.resolve("com/refbot/params/bot/Farm.java"), FARM);
        sources.put(root.resolve("com/refbot/params/bot/FarmPools.java"), pools);
        BotIndex index = BotIndex.uncached(sources, List.of(TestSources.contractOnClasspath()), root);
        return JavaParameters.pools(index, TestValues.GRAMMAR);
    }
}
