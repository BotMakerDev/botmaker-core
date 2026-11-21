// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.plugin;

import com.botmaker.cli.catalog.Advisory;
import com.botmaker.core.sharing.Advisories;
import com.botmaker.core.sharing.CatalogIndex;
import com.botmaker.core.sharing.PluginRegistry;
import com.botmaker.core.sharing.PublisherRank;
import com.botmaker.core.sharing.UnpackedBots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A plugin release an advisory names is refused until this computer's user runs it anyway (spec 2026-11-21 § 5). */
class AdvisedPluginTest {

    private static final Path REPO = Path.of("/home/u/.m2/repository");
    private static final String SDK_JAR = REPO.resolve(
            "com/github/BotMakerDev/botmaker-sdk/v1.0.0/botmaker-sdk-v1.0.0.jar").toString();
    private static final PluginRegistry.Plugin SDK = new PluginRegistry.Plugin("com.botmaker.sdk",
            "com.github.BotMakerDev:botmaker-sdk", "BotMakerDev/botmaker-sdk", "", List.of(), "", List.of(), "",
            "v1.0.0", PublisherRank.ADMIN, 0);
    private static final Advisory ADVISED = new Advisory("BMA-2026-0001", "BotMakerDev/botmaker-sdk",
            List.of("v1.0.0"), Advisory.Severity.CRITICAL, "Runs any jar a screen names.", null, "2026-11-21", null);
    private static final CatalogIndex LISTED = new CatalogIndex(List.of(SDK), List.of(), List.of(ADVISED));

    @TempDir
    Path config;

    private PluginGate gate(List<PluginGate.Asked> asked, AtomicBoolean answer) {
        return new PluginGate(() -> LISTED, REPO, () -> "", () -> CommunityConsent.of(""), kept -> {}, question -> {
            asked.add(question);
            return answer.get();
        }, jar -> true, jar -> false, (jar, row, version) -> ReleasePins.Verdict.APPROVED, UnpackedBots::libOf,
                new Advisories(config.resolve(Advisories.FILE)));
    }

    @Test
    void pluginRefused() {
        List<PluginGate.Asked> asked = new ArrayList<>();
        PluginGate.Admitted admitted = gate(asked, new AtomicBoolean(false)).admit(LISTED, List.of(SDK_JAR), List.of());
        assertEquals(List.of(), admitted.loadable());
        String why = admitted.refused().getFirst().cause().getMessage();
        assertTrue(why.contains("critical security advisory, BMA-2026-0001"), why);
        assertTrue(asked.getFirst().advised());
        assertTrue(asked.getFirst().question().contains("Run it anyway"), asked.getFirst().question());
    }

    @Test
    void overrideAdmits() {
        List<PluginGate.Asked> asked = new ArrayList<>();
        AtomicBoolean answer = new AtomicBoolean(true);
        assertEquals(List.of(SDK_JAR), gate(asked, answer).admit(LISTED, List.of(SDK_JAR), List.of()).loadable());
        answer.set(false);
        assertEquals(List.of(SDK_JAR), gate(asked, answer).admit(LISTED, List.of(SDK_JAR), List.of()).loadable(),
                "the yes is kept for this release");
        assertEquals(1, asked.size());
    }
}
