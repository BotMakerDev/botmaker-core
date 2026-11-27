// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.run.claims;

import com.botmaker.core.project.managed.TestValue;
import com.botmaker.core.run.claims.Dealer.Dealing;
import com.botmaker.core.run.claims.Dealer.Reason;
import com.botmaker.core.run.claims.Dealer.Request;
import com.botmaker.core.run.claims.Dealer.Row;
import com.botmaker.core.run.claims.Dealer.Source;
import com.botmaker.core.run.claims.RunRegistry.Hold;
import com.botmaker.core.run.claims.RunRegistry.LiveRun;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.grammar.ValueGrammar;
import com.botmaker.java.index.PoolReader;
import com.botmaker.plugin.api.run.PluginPool;
import com.botmaker.plugin.api.source.ManagedParam;
import com.botmaker.plugin.api.source.ManagedValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DealerTest {

    private static final String SDK = "com.example:sdk";
    private static final String INSTANCE = "Id.INSTANCE";
    private static final String ACCOUNT = "com.bot.params.bot.Farm#account";
    private static final String TEAM = "com.bot.params.bot.Farm#team";

    private static List<String> numbered(String prefix, int count) {
        return IntStream.rangeClosed(1, count).mapToObj(n -> prefix + n).toList();
    }

    private static Source instances(int count) {
        return Source.plugin(SDK, INSTANCE, "instance", numbered("i", count));
    }

    private static Source accounts(int count) {
        return Source.bot("farm", ACCOUNT, "accounts", numbered("a", count));
    }

    /** An account goes with the instance of its own number, and instances past {@code linked} have none. */
    private static Dealer.Narrowing linkedUpTo(int linked) {
        return (free, picks) -> {
            String number = picks.get(INSTANCE).getFirst().substring(1);
            return Integer.parseInt(number) > linked ? List.of() : free.stream().filter(("a" + number)::equals).toList();
        };
    }

    private static List<String> picked(Dealing dealing, String pool) {
        return dealing.rows().stream().map(row -> String.join("+", row.picks().get(pool))).toList();
    }

    private static List<Reason> ofKind(Dealing dealing, Reason.Kind kind) {
        return dealing.reasons().stream().filter(reason -> reason.kind() == kind).toList();
    }

    @Test
    void nineInstancesAndElevenAccountsMakeSevenRowsWhenTwoInstancesHaveNoAccount() {
        Dealing dealing = Dealer.deal(Request.of(
                List.of(instances(9), accounts(11).narrowedBy(linkedUpTo(7))), 11, List.of()));

        assertEquals(7, dealing.capacity());
        assertFalse(dealing.complete());
        assertEquals(numbered("i", 7), picked(dealing, INSTANCE));
        assertEquals(numbered("a", 7), picked(dealing, ACCOUNT));
        assertEquals("7 of 11 can run: 2 more of instance are needed;"
                + " 2 of instance have nothing free in accounts: i8, i9", dealing.words());
        Reason incomplete = ofKind(dealing, Reason.Kind.INCOMPLETE).getFirst();
        assertEquals(List.of("i8", "i9"), incomplete.elements());
        assertEquals(2, ofKind(dealing, Reason.Kind.TOO_FEW).getFirst().count());
    }

    @Test
    void aDeadEndGoesBackToThePoolBeforeItForItsNextElement() {
        Dealer.Narrowing onlySecond = (free, picks) -> picks.get(INSTANCE).contains("i2") ? free : List.of();
        Dealing dealing = Dealer.deal(Request.of(
                List.of(instances(2), accounts(3).narrowedBy(onlySecond)), 1, List.of()));

        assertTrue(dealing.complete());
        assertEquals(Map.of(INSTANCE, List.of("i2"), ACCOUNT, List.of("a1")), dealing.rows().getFirst().picks());
        assertEquals("1 of 1 can run", dealing.words());
        assertTrue(dealing.reasons().isEmpty());
    }

    @Test
    void sixHeroesAtThreeARunMakeTwoRuns() {
        Source heroes = Source.bot("farm", TEAM, "heroes", numbered("h", 6)).taking(3);
        Dealing dealing = Dealer.deal(Request.of(List.of(heroes), 3, List.of()));

        assertEquals(List.of("h1+h2+h3", "h4+h5+h6"), picked(dealing, TEAM));
        assertEquals("2 of 3 can run: 3 more of heroes are needed", dealing.words());
        assertEquals(6, dealing.rows().stream().flatMap(row -> row.holds(List.of(heroes)).stream()).distinct().count());
    }

    @Test
    void anElementSharedByTwoRunsIsDealtTwice() {
        Source accounts = accounts(2).sharedBy(2);
        Dealing dealing = Dealer.deal(Request.of(List.of(accounts), 5, List.of()));

        assertEquals(List.of("a1", "a2", "a1", "a2"), picked(dealing, ACCOUNT), "the one with most room first");
        assertEquals(1, ofKind(dealing, Reason.Kind.TOO_FEW).getFirst().count());
        assertEquals(2, dealing.rows().getFirst().holds(List.of(accounts)).getFirst().share());
    }

    @Test
    void anElementThePluginSharesWithAnyIsDealtToEveryRowLeftInItsOrder() {
        Source places = Source.plugin(SDK, INSTANCE, "place", List.of("i1", "i2", "desktop"))
                .sharedBy(Map.of("desktop", Integer.MAX_VALUE));
        LiveRun arena = LiveRun.ofThisProcess("r1", "Arena bot", List.of(places.hold("desktop")));
        Dealing dealing = Dealer.deal(Request.of(List.of(places), 5, List.of(arena)));

        assertTrue(dealing.complete(), dealing.words());
        assertEquals(List.of("i1", "i2", "desktop", "desktop", "desktop"), picked(dealing, INSTANCE),
                "the instances listed first are dealt first");
        assertEquals(Integer.MAX_VALUE, places.hold("desktop").share());
        assertEquals(1, places.hold("i1").share());

        Dealing short_ = Dealer.deal(Request.of(List.of(Source.plugin(SDK, INSTANCE, "place", List.of("i1"))
                .sharedBy(Map.of("i1", 2))), 3, List.of()));
        assertEquals("2 of 3 can run: 1 more of place is needed", short_.words());
    }

    private static final String POINTER = "pointer:this-pc/desktop";

    /** This desktop, shared by any number of runs; a run dealt it holds the pointer alone when {@code takesOver}. */
    private static Source desktop(boolean takesOver, String... others) {
        List<String> elements = new ArrayList<>(List.of(others));
        elements.add("desktop");
        Source places = Source.plugin(SDK, INSTANCE, "place", elements).sharedBy(Map.of("desktop", Integer.MAX_VALUE));
        return takesOver ? places.claiming(Map.of("desktop", List.of(POINTER))) : places;
    }

    @Test
    void twoTakeOverRowsOnOneDesktopMakeOneRun() {
        Dealing dealing = Dealer.deal(Request.of(List.of(desktop(true)), 2, List.of()));

        assertEquals(1, dealing.capacity());
        assertEquals(List.of(POINTER), dealing.rows().getFirst().claims(List.of(desktop(true))));
        assertEquals("1 of 2 can run: " + POINTER + " is held by one run at a time", dealing.words());

        Dealing elsewhere = Dealer.deal(Request.of(List.of(desktop(true, "display-1")), 2, List.of()));
        assertTrue(elsewhere.complete(), elsewhere.words());
        assertEquals(List.of("display-1", "desktop"), picked(elsewhere, INSTANCE), "the other goes to a display");
    }

    @Test
    void oneTakeOverRunLeavesThisDesktopToRunsThatPostTheirInput() {
        Source taking = desktop(true);
        LiveRun firestone = LiveRun.ofThisProcess("r1", "Firestone", List.of(taking.hold("desktop")),
                List.of(POINTER));

        Dealing posted = Dealer.deal(Request.of(List.of(desktop(false)), 2, List.of(firestone)));
        assertTrue(posted.complete(), "1 taking over and 2 posting: 3 runs on this desktop; " + posted.words());

        Dealing second = Dealer.deal(Request.of(List.of(taking), 1, List.of(firestone)));
        assertEquals(0, second.capacity());
        assertEquals("0 of 1 can run: " + POINTER + " is held by a run of Firestone", second.words());

        Dealing pinned = Dealer.deal(Request.of(List.of(taking), 1, List.of(firestone))
                .pinned(Map.of(0, Map.of(INSTANCE, List.of("desktop")))));
        assertTrue(pinned.words().startsWith("0 of 1 can run: Run 1's pin: " + POINTER
                + " is held by a run of Firestone"), pinned.words());

        LiveRun claimedWhileRunning = new LiveRun("r2", "Arena bot", 1, 0, List.of(), List.of(POINTER));
        assertEquals(0, Dealer.deal(Request.of(List.of(taking), 1, List.of(claimedWhileRunning))).capacity(),
                "a run that took over during its run holds the pointer too");

        // A key spelled as another pool's element: the run holding that element holds the key, as the registry reads it.
        Hold element = Hold.plugin(SDK, INSTANCE, "i1");
        Source keyed = desktop(false).claiming(Map.of("desktop", List.of(element.key())));
        LiveRun holder = LiveRun.ofThisProcess("r3", "Arena bot", List.of(element));
        assertEquals(0, Dealer.deal(Request.of(List.of(keyed), 1, List.of(holder))).capacity(),
                "the registry would refuse it, so it is not dealt");
    }

    @Test
    void anInstanceAnotherBotsRunHoldsIsNotDealt() {
        LiveRun arena = LiveRun.ofThisProcess("r1", "Arena bot", List.of(Hold.plugin(SDK, INSTANCE, "i1")));
        Dealing dealing = Dealer.deal(Request.of(List.of(instances(3), accounts(3)), 3, List.of(arena)));

        assertEquals(List.of("i2", "i3"), picked(dealing, INSTANCE));
        assertEquals("2 of 3 can run: i1 is held by a run of Arena bot", dealing.words());

        // A hold is no reason where its pool has room: here the accounts fall short, not the instances.
        Dealing short_ = Dealer.deal(Request.of(List.of(instances(10), accounts(2)), 3, List.of(arena)));
        assertEquals("2 of 3 can run: 1 more of accounts is needed", short_.words());

        // A bot's own pool is held under that bot: another bot's hold of the same words is no conflict.
        LiveRun other = LiveRun.ofThisProcess("r2", "arena", List.of(Hold.bot("arena", ACCOUNT, "a1")));
        assertTrue(Dealer.deal(Request.of(List.of(accounts(2)), 2, List.of(other))).complete());
    }

    @Test
    void anInstanceALoneRunClaimedIsNotDealt() {
        LiveRun lone = new LiveRun("r1", "Arena bot", ProcessHandle.current().pid(), 0, List.of(),
                List.of(Hold.plugin(SDK, INSTANCE, "i1").key()));
        Dealing dealing = Dealer.deal(Request.of(List.of(instances(3)), 3, List.of(lone)));

        assertEquals(List.of("i2", "i3"), picked(dealing, INSTANCE));
        assertEquals("2 of 3 can run: i1 is held by a run of Arena bot", dealing.words());
    }

    @Test
    void aPinIsDealtAsGivenAndKeptFromTheRowsBeforeIt() {
        Request pinned = Request.of(List.of(accounts(3)), 3, List.of())
                .pinned(Map.of(1, Map.of(ACCOUNT, List.of("a1"))));
        assertEquals(List.of("a2", "a1", "a3"), picked(Dealer.deal(pinned), ACCOUNT));
    }

    @Test
    void aPinThatConflictsLeavesItsRowOutAndDealsTheOthers() {
        LiveRun live = LiveRun.ofThisProcess("r1", "farm", List.of(Hold.bot("farm", ACCOUNT, "a1")));
        Dealing held = Dealer.deal(Request.of(List.of(accounts(3)), 2, List.of(live))
                .pinned(Map.of(0, Map.of(ACCOUNT, List.of("a1")))));
        assertEquals(List.of(1), held.rows().stream().map(Row::index).toList());
        assertEquals("Run 1's pin: a1 is held by a run of farm", ofKind(held, Reason.Kind.PIN).getFirst().words());

        Dealing unknown = Dealer.deal(Request.of(List.of(accounts(3)), 1, List.of())
                .pinned(Map.of(0, Map.of(ACCOUNT, List.of("a9")))));
        assertEquals(0, unknown.capacity());
        assertEquals("Run 1's pin: a9 is not in accounts", unknown.reasons().getFirst().words());

        Dealing twice = Dealer.deal(Request.of(List.of(accounts(3)), 2, List.of())
                .pinned(Map.of(0, Map.of(ACCOUNT, List.of("a2")), 1, Map.of(ACCOUNT, List.of("a2")))));
        assertEquals(List.of(0), twice.rows().stream().map(Row::index).toList());
        assertEquals("Run 2's pin: a2 is dealt to another run", ofKind(twice, Reason.Kind.PIN).getFirst().words());
    }

    @Test
    void aPinThatFailsGivesItsElementBackToTheRowsAroundIt() {
        // a1 goes with i1 only. Run 2 pins a1 on i2, which can never be; run 3 then takes a1.
        Dealer.Narrowing withFirst = (free, picks) -> picks.get(INSTANCE).contains("i1") ? free : List.of();
        Dealing dealing = Dealer.deal(Request.of(List.of(instances(2), accounts(1).narrowedBy(withFirst)), 3, List.of())
                .pinned(Map.of(1, Map.of(INSTANCE, List.of("i2"), ACCOUNT, List.of("a1")))));
        assertEquals(List.of(2), dealing.rows().stream().map(Row::index).toList());
        assertEquals(1, ofKind(dealing, Reason.Kind.PIN).size());

        // A pin a live run holds keeps nothing from the rows before it.
        LiveRun live = LiveRun.ofThisProcess("r1", "Arena bot", List.of(Hold.plugin(SDK, INSTANCE, "i1")));
        Dealing held = Dealer.deal(Request.of(List.of(instances(2), accounts(1)), 2, List.of(live))
                .pinned(Map.of(1, Map.of(INSTANCE, List.of("i1"), ACCOUNT, List.of("a1")))));
        assertEquals(List.of(0), held.rows().stream().map(Row::index).toList());
        assertEquals("Run 2's pin: i1 is held by a run of Arena bot", ofKind(held, Reason.Kind.PIN).getFirst().words());
    }

    @Test
    void aPinFoundAfterGoingBackLeavesNoReason() {
        Dealer.Narrowing third = (free, picks) -> picks.get(INSTANCE).contains("i3") ? free : List.of();
        Dealing dealing = Dealer.deal(Request.of(List.of(instances(3), accounts(3).narrowedBy(third)), 1, List.of())
                .pinned(Map.of(0, Map.of(ACCOUNT, List.of("a3")))));
        assertTrue(dealing.complete());
        assertEquals(List.of("i3"), picked(dealing, INSTANCE));
        assertTrue(dealing.reasons().isEmpty());
    }

    @Test
    void aRunTakingSeveralTriesEveryChoiceAndSpreadsOverSharedElements() {
        Source pairs = Source.bot("farm", TEAM, "heroes", numbered("h", 3)).taking(2);
        Dealer.Narrowing needsThird = (free, picks) -> picks.get(TEAM).contains("h3") ? free : List.of();
        Dealing dealing = Dealer.deal(Request.of(List.of(pairs, accounts(1).narrowedBy(needsThird)), 1, List.of()));
        assertEquals(List.of("h1+h3"), picked(dealing, TEAM));

        Source shared = Source.bot("farm", TEAM, "heroes", numbered("h", 5)).taking(3).sharedBy(2);
        assertEquals(3, Dealer.deal(Request.of(List.of(shared), 3, List.of())).capacity(),
                "5 heroes held twice each make 3 teams of 3");
    }

    @Test
    void aNarrowingIsAskedOnceForEachFreeSetAndRowSoFar() {
        List<Map<String, List<String>>> asked = new ArrayList<>();
        Dealer.Narrowing none = (free, picks) -> {
            asked.add(Map.copyOf(picks));
            return List.of();
        };
        Dealing dealing = Dealer.deal(Request.of(
                List.of(instances(2), accounts(2), Source.bot("farm", TEAM, "heroes", List.of("h1")).narrowedBy(none)),
                3, List.of()));
        assertEquals(0, dealing.capacity());
        assertEquals(4, asked.size(), "each instance with each account, once, though three runs were asked");
        assertEquals(List.of("2 of instance have nothing free in heroes: i1, i2"),
                ofKind(dealing, Reason.Kind.INCOMPLETE).stream().map(Reason::words).toList());
    }

    @Test
    void aRowKeepsWhatItHeldLastUnlessItsPoolRotates() {
        List<Source> sticky = List.of(accounts(3));
        // Last time the second run alone ran, on a1, then the first on a3.
        DealHistory last = DealHistory.NONE
                .after(List.of(new Row(1, Map.of(ACCOUNT, List.of("a1")))))
                .after(List.of(new Row(0, Map.of(ACCOUNT, List.of("a3")))));

        assertEquals(List.of("a3", "a1"), picked(Dealer.deal(Request.of(sticky, 2, List.of()).after(last)), ACCOUNT));
        // One run only: it takes its own, then what no other row held, and leaves a1 to the row that had it.
        LiveRun live = LiveRun.ofThisProcess("r1", "farm", List.of(Hold.bot("farm", ACCOUNT, "a3")));
        assertEquals(List.of("a2", "a1"),
                picked(Dealer.deal(Request.of(sticky, 2, List.of(live)).after(last)), ACCOUNT));

        List<Source> rotating = List.of(accounts(3).rotating());
        assertEquals(List.of("a2", "a1"),
                picked(Dealer.deal(Request.of(rotating, 2, List.of()).after(last)), ACCOUNT),
                "never used first, then the longest ago");
    }

    @Test
    void theHistoryIsKeptInTheBotsFolder(@TempDir Path project) {
        assertEquals(DealHistory.NONE, DealHistory.of(project));
        DealHistory kept = DealHistory.NONE.after(List.of(new Row(1, Map.of(ACCOUNT, List.of("a1", "a2")))));
        kept.keep(project);

        DealHistory read = DealHistory.of(project);
        assertEquals(kept, read);
        assertEquals(List.of("a1", "a2"), read.held(1, ACCOUNT));
        assertEquals(List.of(), read.held(0, ACCOUNT));
        assertEquals(1, read.usedAt(ACCOUNT, "a2"));
        assertEquals(0, read.usedAt(ACCOUNT, "a3"));
        assertTrue(project.resolve(".botmaker/runs/dealt.json").toFile().isFile());
    }

    @Test
    void aNarrowingThatThrowsFailsTheDealingWithItsOwnerNamed() {
        Dealer.Narrowing broken = (free, picks) -> {
            throw new IllegalStateException("no grid");
        };
        Dealer.Failed failed = assertThrows(Dealer.Failed.class, () -> Dealer.deal(
                Request.of(List.of(instances(2).narrowedBy(broken)), 1, List.of())));
        assertEquals(SDK, failed.owner());
        assertEquals("com.example:sdk failed while narrowing instance: no grid", failed.getMessage());
    }

    // ---- the pools read ----------------------------------------------------------------------------------------

    public static boolean yes() {
        return true;
    }

    static final ManagedParam<Boolean> SETUP = ManagedValue.param(TestValue.Id.DO_SETUP).in(TestValue.Holder.SDK)
            .startsAs(DealerTest::yes).shown("Setup").because("Mine.");
    static final ManagedParam<Boolean> PAUSE = ManagedValue.param(TestValue.Id.PAUSE).in(TestValue.Holder.SDK)
            .startsAs(DealerTest::yes).shown("Pause").because("Mine.");

    private static PoolReader.Reading reading(String method, String parameter, String... elements) {
        List<JavaValue> values = new ArrayList<>();
        for (String element : elements) values.add(JavaValue.parse(element).orElseThrow());
        return new PoolReader.Reading("com.bot.params.bot.FarmPools", method,
                Optional.of(new PoolReader.Filled("com.bot.params.bot.Farm", parameter, "()Ljava/lang/String;",
                        String.class, String.class)), Optional.empty(), values, 0, 2, true, "");
    }

    private static PoolSources.Target target(String method) {
        return new PoolSources.Target("com.bot.params.sdk.SdkParams", method, "()Z", "");
    }

    @Test
    void theBotsPoolsAreWrittenCanonicallyAndOneUnreadIsAWarning() {
        PoolReader.Reading unread = new PoolReader.Reading("com.bot.params.bot.FarmPools", "heroes",
                Optional.empty(), Optional.empty(), List.of(), 0, 1, false, "fills no parameter");
        PoolSources.Read read = PoolSources.ofBot("farm",
                List.of(reading("accounts", "account", "new Login(\"a\",1)", "Accounts.MAIN"), unread));

        Source accounts = read.pools().getFirst();
        assertEquals(1, read.pools().size());
        assertEquals(List.of("new Login(\"a\", 1)", "Accounts.MAIN"), accounts.elements());
        assertEquals(ACCOUNT, accounts.parameter());
        assertEquals("accounts", accounts.name());
        assertEquals(2, accounts.each());
        assertTrue(accounts.rotates());
        assertEquals(Hold.bot("farm", ACCOUNT, "Accounts.MAIN").sharedBy(2), accounts.hold("Accounts.MAIN"));
        assertEquals(List.of("heroes() could not be read (fills no parameter); make sure the runs differ"),
                read.warnings());
    }

    @Test
    void aPluginsPoolIsDealtOnlyWhereItsParameterIsBoundAndNarrowsByTypedPicks() {
        PluginPool<Boolean> setups = PluginPool.fills(SETUP).from(services -> List.of(true, false));
        // A pause goes with the setup of its own value only.
        PluginPool<Boolean> pauses = PluginPool.fills(PAUSE).from(services -> List.of(true, false))
                .narrowedBy((candidates, picks, services) -> candidates.stream()
                        .filter(candidate -> picks.of(SETUP).map(candidate::equals).orElse(false)).toList());
        List<PoolSources.Declared> plugins = List.of(new PoolSources.Declared(SDK, List.of(setups, pauses)));

        PoolSources.Read read = PoolSources.of(plugins,
                id -> Optional.of(target(id.equals(SETUP.id()) ? "doSetup" : "pause")), ValueGrammar.empty(), null,
                "farm", List.of(reading("accounts", "account", "\"a\"", "\"b\"")));
        assertEquals(target("doSetup"), read.targets().get(SETUP.id()));
        assertEquals(new PoolSources.Target("com.bot.params.bot.Farm", "account", "()Ljava/lang/String;",
                "com.bot.params.bot.FarmPools#accounts"), read.targets().get(ACCOUNT));
        assertEquals(List.of("doSetup", "pause", "accounts"), read.pools().stream().map(Source::name).toList());
        assertEquals(Hold.plugin(SDK, SETUP.id(), "true"), read.pools().getFirst().hold("true"));

        Dealing dealing = Dealer.deal(Request.of(read.pools().subList(0, 2), 2, List.of()));
        assertEquals(List.of("true", "false"), picked(dealing, SETUP.id()));
        assertEquals(List.of("true", "false"), picked(dealing, PAUSE.id()));

        PoolSources.Read unbound = PoolSources.of(plugins,
                id -> id.equals(SETUP.id()) ? Optional.of(target("doSetup")) : Optional.empty(), ValueGrammar.empty(),
                null,
                "farm", List.of());
        assertEquals(List.of("doSetup"), unbound.pools().stream().map(Source::name).toList());
    }

    @Test
    void aDealtRowCarriesWhatThePluginNotesAboutItsPickGivenTheEarlierPicks() {
        PluginPool<Boolean> setups = PluginPool.fills(SETUP).from(services -> List.of(true, false));
        // A pause that disagrees with its row's setup is dealt anyway, and said.
        PluginPool<Boolean> pauses = PluginPool.fills(PAUSE).from(services -> List.of(false, true))
                .noting((pause, picks, services) -> picks.of(SETUP).filter(setup -> !setup.equals(pause))
                        .map(setup -> "pause " + pause + " while setup " + setup).orElse(null));
        PoolSources.Read read = PoolSources.of(List.of(new PoolSources.Declared(SDK, List.of(setups, pauses))),
                id -> Optional.of(target(id.equals(SETUP.id()) ? "doSetup" : "pause")), ValueGrammar.empty(), null,
                "farm", List.of());

        Dealing dealing = Dealer.deal(Request.of(read.pools(), 2, List.of()));
        assertEquals(List.of("true", "false"), picked(dealing, SETUP.id()));
        assertEquals(List.of("false", "true"), picked(dealing, PAUSE.id()));
        assertEquals(List.of(List.of("pause false while setup true"), List.of("pause true while setup false")),
                dealing.rows().stream().map(Row::notes).toList());

        Dealing quiet = Dealer.deal(Request.of(read.pools().subList(0, 1), 1, List.of()));
        assertEquals(List.of(), quiet.rows().getFirst().notes(), "a pool with no noting notes nothing");
    }

    @Test
    void aNotingThatThrowsFailsTheDealingWithThePluginNamed() {
        PluginPool<Boolean> setups = PluginPool.fills(SETUP).from(services -> List.of(true))
                .noting((setup, picks, services) -> {
                    throw new IllegalStateException("no book");
                });
        PoolSources.Read read = PoolSources.of(List.of(new PoolSources.Declared(SDK, List.of(setups))),
                id -> Optional.of(target("doSetup")), ValueGrammar.empty(), null, "farm", List.of());

        Dealer.Failed failed = assertThrows(Dealer.Failed.class,
                () -> Dealer.deal(Request.of(read.pools(), 1, List.of())));
        assertEquals(SDK, failed.owner());
        assertEquals("com.example:sdk failed while noting doSetup: no book", failed.getMessage());
    }

    @Test
    void aBotPoolFillingTheMethodAPluginsPoolFillsIsRefused() {
        PluginPool<Boolean> setups = PluginPool.fills(SETUP).from(services -> List.of(true, false));
        PoolSources.Target account = new PoolSources.Target("com.bot.params.bot.Farm", "account",
                "()Ljava/lang/String;", "");
        Dealer.Failed failed = assertThrows(Dealer.Failed.class, () -> PoolSources.of(
                List.of(new PoolSources.Declared(SDK, List.of(setups))), id -> Optional.of(account),
                ValueGrammar.empty(), null, "farm", List.of(reading("accounts", "account", "\"a\"", "\"b\""))));
        assertEquals("farm", failed.owner());
        assertTrue(failed.getMessage().contains("Farm::account"), failed.getMessage());
    }

    @Test
    void aPluginThatThrowsFailsTheDealingWithThePluginNamed() {
        PluginPool<Boolean> broken = PluginPool.fills(SETUP).from(services -> {
            throw new IllegalStateException("no host values");
        });
        Dealer.Failed failed = assertThrows(Dealer.Failed.class, () -> PoolSources.of(
                List.of(new PoolSources.Declared(SDK, List.of(broken))), id -> Optional.of(target("doSetup")),
                ValueGrammar.empty(), null, "farm", List.of()));
        assertEquals(SDK, failed.owner());
        assertTrue(failed.getMessage().contains("doSetup"), failed.getMessage());
    }
}
