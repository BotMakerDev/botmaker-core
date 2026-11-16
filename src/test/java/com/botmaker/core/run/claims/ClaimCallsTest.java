package com.botmaker.core.run.claims;

import com.botmaker.core.run.claims.fixture.Claiming;
import com.botmaker.core.run.pooled.PooledBot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bot's claims found in its compile, which a start of several runs says are decided while running. */
class ClaimCallsTest {

    @Test
    void theMethodsThatClaimAreFoundInTheCompile() throws Exception {
        Path classes = Path.of(PooledBot.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> sites = ClaimCalls.in(classes);
        assertTrue(sites.contains("PooledBot.main"), sites.toString());
        assertFalse(sites.contains("PooledParams.account"), sites.toString());
        assertFalse(sites.stream().anyMatch(site -> site.startsWith("ClaimCallsTest")), "a call of in() is no claim");
    }

    @Test
    void aReferenceALambdaAndAnAnonymousClassAreNamedWhereTheyAreWritten() throws Exception {
        Path classes = Path.of(Claiming.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> sites = ClaimCalls.in(classes).stream().filter(site -> site.startsWith("Claiming")).toList();
        assertEquals(List.of("Claiming.byReference", "Claiming.get", "Claiming.inALambda"), sites,
                "an anonymous class's method keeps its own name, under the class it is written in; release is no claim");
    }

    @Test
    void aMissingCompileClaimsNothing(@TempDir Path dir) {
        assertEquals(List.of(), ClaimCalls.in(dir.resolve("absent")));
        assertEquals(List.of(), ClaimCalls.in(dir));
    }

    @Test
    void aStartSaysWhatIsDecidedWhileRunning() {
        PooledRuns.Outcome outcome = new PooledRuns.Outcome(new Dealer.Dealing(0, List.of(), List.of()), List.of(),
                List.of(), List.of(), List.of(), List.of());
        assertEquals("0 of 0 started", outcome.words());
        assertEquals("0 of 0 started: decided while running: the keys claimed in Farm.run",
                outcome.claiming(List.of("Farm.run")).words());
    }
}
