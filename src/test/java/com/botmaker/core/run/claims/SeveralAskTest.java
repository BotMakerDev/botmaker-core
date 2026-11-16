package com.botmaker.core.run.claims;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A start of several runs, its pins written as {@code botmaker bot run --pin} and {@code run_bot}'s pins take them. */
class SeveralAskTest {

    @Test
    void aPinIsForTheFirstRunUnlessItNamesOneAndAPoolPinnedTwiceTakesBoth() {
        SeveralAsk ask = SeveralAsk.pinned(3, SeveralAsk.How.START, List.of("Farm#account=com.example.Account.MAIN",
                "3:INSTANCE=Instance.named(\"ld-2\")", "3:Farm#heroes=\"a=b\"", "3:Farm#heroes=\"c\""));
        assertEquals(Map.of(0, Map.of("Farm#account", List.of("com.example.Account.MAIN")),
                2, Map.of("INSTANCE", List.of("Instance.named(\"ld-2\")"),
                        "Farm#heroes", List.of("\"a=b\"", "\"c\""))), ask.pins());
    }

    @Test
    void aPinWrittenOtherwiseOrPastTheRunsIsRefused() {
        for (String bad : List.of("account", "=x", "account=", "4:account=x", "0:account=x",
                "99999999999:account=x")) {
            assertThrows(IllegalArgumentException.class,
                    () -> SeveralAsk.pinned(3, SeveralAsk.How.START, List.of(bad)), bad);
        }
        Map<String, List<String>> nullPick = new HashMap<>();
        nullPick.put("account", null);
        assertThrows(IllegalArgumentException.class,
                () -> new SeveralAsk(2, SeveralAsk.How.START, Map.of(0, nullPick)));
        assertThrows(IllegalArgumentException.class, () -> SeveralAsk.of(0));
        assertThrows(IllegalArgumentException.class, () -> SeveralAsk.of(SeveralRuns.MOST + 1));
    }
}
