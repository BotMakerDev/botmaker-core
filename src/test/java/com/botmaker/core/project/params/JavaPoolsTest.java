package com.botmaker.core.project.params;

import com.botmaker.core.project.ProjectConfig;
import com.botmaker.java.grammar.ParamsFitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.botmaker.java.parse.JavaLevel.LATEST;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A pool made in the Parameters window lives in both copies, its elements in this PC's only (2026-11-16). */
class JavaPoolsTest {

    @Test
    void aPoolsElementsStayOutOfTheStartingCopyAndARemoveClearsBoth(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("refbot", root);
        Files.createDirectories(config.mainPackageDir());
        PoolEdits.Shape guilds = new PoolEdits.Shape(JavaPools.groupFor("Farm"), "guild", "Farm", "guild",
                String.class, String.class, false, List.of("\"Night Owls\"", "\"Dawn Riders\""), List.of(), 0, 3,
                false);

        assertTrue(JavaPools.put(config, null, guilds));

        Path mine = config.parametersPackageDir().resolve("bot/FarmPools.java");
        Path starting = config.projectPath().resolve(ParamsFitter.STARTING).resolve("bot/FarmPools.java");
        String written = Files.readString(mine);
        String blank = Files.readString(starting);
        assertTrue(written.contains("@Pools") && written.contains("\"Dawn Riders\"") && written.contains(".each(3)"),
                written);
        assertTrue(blank.contains("Pool.<String>of().each(3).fills(Farm::guild)"), blank);
        assertFalse(blank.contains("Night Owls"), blank);

        ParamsFitter.onDisk(LATEST, config.parametersPackageDir(), config.projectPath().resolve(ParamsFitter.STARTING),
                config.projectPath().resolve(".botmaker/values-removed"));
        assertTrue(Files.readString(mine).contains("\"Dawn Riders\""), "the fit keeps this PC's elements");

        assertTrue(JavaPools.remove(config, null, "FarmPools", "guild"));
        assertFalse(PoolEdits.has(Files.readString(mine), "FarmPools", "guild"));
        assertFalse(PoolEdits.has(Files.readString(starting), "FarmPools", "guild"));
    }
}
