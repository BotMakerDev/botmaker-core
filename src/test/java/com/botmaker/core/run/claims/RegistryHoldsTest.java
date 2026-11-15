package com.botmaker.core.run.claims;

import com.botmaker.core.project.managed.TestValue;
import com.botmaker.core.run.claims.RunRegistry.Hold;
import com.botmaker.core.run.claims.RunRegistry.LiveRun;
import com.botmaker.java.grammar.ValueGrammar;
import com.botmaker.plugin.api.params.Param;
import com.botmaker.plugin.api.run.LiveRuns;
import com.botmaker.plugin.api.run.PluginPool;
import com.botmaker.plugin.api.source.ManagedParam;
import com.botmaker.plugin.api.source.ManagedValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryHoldsTest {

    private static final String SDK = "com.github.BotMakerDev:botmaker-sdk";

    public static boolean yes() {
        return true;
    }

    static final ManagedParam<Boolean> SETUP = ManagedValue.param(TestValue.Id.DO_SETUP).in(TestValue.Holder.SDK)
            .startsAs(RegistryHoldsTest::yes).shown("Setup", Param.Visibility.EDITOR).because("Mine.");

    @TempDir
    Path dir;

    private RunRegistry registry() {
        return new RunRegistry(dir, RunRegistry.Alive.PROCESSES);
    }

    private RegistryHolds holds(RunRegistry registry) {
        return new RegistryHolds(registry, ValueGrammar::empty,
                id -> id.equals(SETUP.id()) ? Optional.of(SDK) : Optional.empty());
    }

    /** The hold a dealing of the plugin's pool counts for {@code element}. */
    private static Hold dealt(String element) {
        PluginPool<Boolean> setups = PluginPool.fills(SETUP).from(services -> List.of(true, false));
        PoolSources.Read read = PoolSources.of(List.of(new PoolSources.Declared(SDK, List.of(setups))),
                id -> Optional.of(new PoolSources.Target("com.bot.params.sdk.SdkParams", "doSetup", "()Z", "")),
                ValueGrammar.empty(), null, "farm", List.of());
        return read.pools().getFirst().hold(element);
    }

    @Test
    void aHoldIsTheElementADealingOfThePluginsPoolCountsAndClosingFreesIt() throws LiveRuns.Taken {
        RunRegistry registry = registry();
        LiveRuns.Held held = holds(registry).hold(SETUP, true, "the Emulators window");

        LiveRun run = registry.live().getFirst();
        assertEquals("the Emulators window", run.bot());
        assertEquals(List.of(dealt("true")), run.holds());
        assertEquals(ProcessHandle.current().pid(), run.pid());

        held.close();
        held.close();
        assertTrue(registry.live().isEmpty());
    }

    @Test
    void aValueALiveRunHoldsIsRefusedWithTheRunsWordsAndAnotherIsNot() throws LiveRuns.Taken {
        RunRegistry registry = registry();
        assertTrue(registry.hold(LiveRun.ofThisProcess(RunRegistry.newRunId(), "farm", List.of(dealt("true"))))
                .isEmpty());

        LiveRuns.Taken taken = assertThrows(LiveRuns.Taken.class,
                () -> holds(registry).hold(SETUP, true, "the Emulators window"));
        assertTrue(taken.getMessage().contains("a run of farm"), taken.getMessage());
        try (LiveRuns.Held other = holds(registry).hold(SETUP, false, "the Emulators window")) {
            assertEquals(2, registry.live().size());
        }
    }

    @Test
    void aParameterNoPoolFillsHoldsNothing() throws LiveRuns.Taken {
        RunRegistry registry = registry();
        RegistryHolds none = new RegistryHolds(registry, ValueGrammar::empty, id -> Optional.empty());
        try (LiveRuns.Held held = none.hold(SETUP, true, "the Emulators window")) {
            assertTrue(registry.live().isEmpty());
        }
    }
}
