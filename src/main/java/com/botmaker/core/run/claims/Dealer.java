// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.run.claims;

import com.botmaker.core.run.claims.RunRegistry.Hold;
import com.botmaker.core.run.claims.RunRegistry.LiveRun;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Deals several runs their rows (spec {@code 2026-11-15-run-claims-design.md} §4, §5): one pick from every pool, no
 * element in more live runs than it allows. Pure: it reads the pools, the live runs and what each row held last, and
 * holds nothing. Its caller deals and holds under {@link RunRegistry#locked}, so two starters never deal one element
 * twice.
 *
 * <p>A row is dealt pool by pool, in the pools' order: each takes its first free element its narrowing leaves, and a
 * pool with none sends the row back to the pool before it for its next element. Rows are dealt one after the other,
 * so what the dealing counts is the rows it found, which is the capacity the window shows, with why it stopped.
 */
public final class Dealer {

    private Dealer() {}

    /** What a pool's owner leaves of its free elements, given the row's picks before it. */
    @FunctionalInterface
    public interface Narrowing {

        /** Every free element goes with any row. */
        Narrowing NONE = (free, picks) -> free;

        /**
         * Those of {@code free} that can join a row holding {@code picks}, in the order to try them. Anything else
         * answered is dropped, and {@code null} reads as none. Asked once for each free set and row so far.
         *
         * @param picks the row so far, by each pool's {@link Source#parameter}
         */
        List<String> left(List<String> free, Map<String, List<String>> picks);
    }

    /**
     * One pool as the dealing reads it, a bot's or a plugin's.
     *
     * @param owner     who its elements are held under: the plugin's {@code groupId:artifactId}, or the bot
     * @param parameter the parameter it fills, which names the pool: a plugin's parameter id, or the bot's
     *                  {@code owner class#method}
     * @param name      what its elements are called in a reason, {@code accounts}
     * @param elements  every element, as the grammar writes it, in the order to deal
     * @param take      how many elements a run takes; {@code 0} for one
     * @param each      how many live runs may hold one element at once
     * @param rotates   whether the element used least recently is dealt first, rather than the row's last one
     */
    public record Source(Hold.Kind kind, String owner, String parameter, String name, List<String> elements, int take,
                         int each, boolean rotates, Narrowing narrowing) {

        public Source {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(parameter, "parameter");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(narrowing, "narrowing");
            elements = List.copyOf(new LinkedHashSet<>(elements));
            if (take < 0) throw new IllegalArgumentException("take " + take + " < 0");
            if (each < 1) throw new IllegalArgumentException("each " + each + " < 1");
        }

        /** A bot's own pool: one element a run, held by one run, sticky. */
        public static Source bot(String bot, String parameter, String name, List<String> elements) {
            return new Source(Hold.Kind.BOT, bot, parameter, name, elements, 0, 1, false, Narrowing.NONE);
        }

        /** A plugin's pool, held for every bot on this PC. */
        public static Source plugin(String coordinate, String parameter, String name, List<String> elements) {
            return new Source(Hold.Kind.PLUGIN, coordinate, parameter, name, elements, 0, 1, false, Narrowing.NONE);
        }

        /** This pool, each run taking {@code k} elements. */
        public Source taking(int k) {
            return new Source(kind, owner, parameter, name, elements, k, each, rotates, narrowing);
        }

        /** This pool, {@code n} live runs sharing an element. */
        public Source sharedBy(int n) {
            return new Source(kind, owner, parameter, name, elements, take, n, rotates, narrowing);
        }

        /** This pool, its least recently used element dealt first. */
        public Source rotating() {
            return new Source(kind, owner, parameter, name, elements, take, each, true, narrowing);
        }

        /** This pool, its free elements narrowed by the row's earlier picks. */
        public Source narrowedBy(Narrowing by) {
            return new Source(kind, owner, parameter, name, elements, take, each, rotates, by);
        }

        /** How many elements one run takes. */
        public int perRun() {
            return Math.max(1, take);
        }

        /** {@code element} as the registry holds it for a run. */
        public Hold hold(String element) {
            return new Hold(kind, owner, parameter, element, each);
        }
    }

    /**
     * One run's picks.
     *
     * @param index which of the runs asked for it is, from 0: what its pins and its last picks are kept under
     * @param picks each pool's pick by its {@link Source#parameter}, in the pools' order: one element, or as many
     *              as the pool takes
     */
    public record Row(int index, Map<String, List<String>> picks) {

        public Row {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            picks.forEach((parameter, pick) -> copy.put(parameter, List.copyOf(pick)));
            picks = Collections.unmodifiableMap(copy);
        }

        /** What the registry holds for this row, over the {@code pools} it was dealt from. */
        public List<Hold> holds(List<Source> pools) {
            List<Hold> out = new ArrayList<>();
            for (Source pool : pools) {
                for (String element : picks.getOrDefault(pool.parameter(), List.of())) out.add(pool.hold(element));
            }
            return List.copyOf(out);
        }
    }

    /**
     * Why fewer rows were dealt than asked for.
     *
     * @param pool     the pool's {@link Source#parameter}
     * @param elements the elements it is about; none for {@link Kind#TOO_FEW}
     * @param count    how many: elements missing, elements with nothing to go with, or one
     */
    public record Reason(Kind kind, String pool, List<String> elements, int count, String words) {

        /** What kind of reason it is. */
        public enum Kind {
            /** The pool has fewer elements than the runs asked for need. */
            TOO_FEW,
            /** Free elements of the pool make no complete row: a later pool leaves nothing to go with them. */
            INCOMPLETE,
            /** An element is held by another live run, in a pool that falls short. */
            HELD,
            /** A row's pin could not be dealt as given. */
            PIN
        }

        public Reason {
            elements = List.copyOf(elements);
        }
    }

    /**
     * What to deal.
     *
     * @param pools the pools in the order a row is dealt: the plugins' first, then the bot's
     * @param runs  how many rows are asked for
     * @param live  the live runs of this PC, as the registry reads them
     * @param pins  the picks the user fixed, by row index then by pool: dealt as given, or the row is not dealt
     * @param last  what each row held last time, and when each element was last used
     */
    public record Request(List<Source> pools, int runs, List<LiveRun> live,
                          Map<Integer, Map<String, List<String>>> pins, DealHistory last) {

        public Request {
            pools = List.copyOf(pools);
            live = List.copyOf(live);
            pins = Map.copyOf(pins);
            Objects.requireNonNull(last, "last");
            if (runs < 0) throw new IllegalArgumentException("runs " + runs + " < 0");
            Set<String> named = new LinkedHashSet<>();
            for (Source pool : pools) {
                if (!named.add(pool.parameter())) {
                    throw new IllegalArgumentException("Two pools fill " + pool.parameter());
                }
            }
        }

        /** {@code runs} rows from {@code pools}, with no pin and no memory of an earlier dealing. */
        public static Request of(List<Source> pools, int runs, List<LiveRun> live) {
            return new Request(pools, runs, live, Map.of(), DealHistory.NONE);
        }

        /** This request, with {@code pins}. */
        public Request pinned(Map<Integer, Map<String, List<String>>> pins) {
            return new Request(pools, runs, live, pins, last);
        }

        /** This request, remembering {@code last}. */
        public Request after(DealHistory last) {
            return new Request(pools, runs, live, pins, last);
        }
    }

    /**
     * What was dealt.
     *
     * @param asked   how many rows were asked for
     * @param rows    the complete, free rows found, by index
     * @param reasons why there are fewer than asked; none when every row was dealt
     */
    public record Dealing(int asked, List<Row> rows, List<Reason> reasons) {

        public Dealing {
            rows = List.copyOf(rows);
            reasons = List.copyOf(reasons);
        }

        /** How many runs can start: the number of complete, free rows. */
        public int capacity() {
            return rows.size();
        }

        /** Whether every run asked for got its row. */
        public boolean complete() {
            return rows.size() == asked;
        }

        /** The capacity in one line, {@code 7 of 11 can run: …}. */
        public String words() {
            if (complete()) return asked + " of " + asked + " can run";
            StringBuilder out = new StringBuilder(capacity() + " of " + asked + " can run");
            String sep = ": ";
            for (Reason reason : reasons) {
                out.append(sep).append(reason.words());
                sep = "; ";
            }
            return out.toString();
        }
    }

    /** A pool's owner threw while the host dealt: nothing is dealt, and no run starts. */
    public static final class Failed extends RuntimeException {

        private final String owner;

        public Failed(String owner, String what, Throwable cause) {
            super(owner + " " + what + (cause == null || cause.getMessage() == null ? "" : ": " + cause.getMessage()),
                    cause);
            this.owner = owner;
        }

        /** Whose pool failed: the plugin's {@code groupId:artifactId}. */
        public String owner() {
            return owner;
        }
    }

    /**
     * Deals {@code request}.
     *
     * @throws Failed when a pool's narrowing throws
     */
    public static Dealing deal(Request request) {
        return new Table(request).deal();
    }

    /** One dealing's state: what each row took so far. */
    private static final class Table {

        private final Request request;
        private final List<Source> pools;
        /** By pool then element: how many rows of this dealing hold it. */
        private final Map<String, Map<String, Integer>> taken = new HashMap<>();
        /** By pool then element: how many rows not dealt yet pin it. */
        private final Map<String, Map<String, Integer>> reserved = new HashMap<>();
        private final List<Reason> reasons = new ArrayList<>();
        /** By pool where a row found nothing: the first pool's picks that led there, what makes no complete row. */
        private final Map<String, Set<String>> stuck = new LinkedHashMap<>();
        /** A narrowing's answer, by pool, free elements and row so far: a plugin is asked each once. */
        private final Map<List<Object>, List<String>> narrowed = new HashMap<>();
        /** The rows whose pins can be dealt at all: only those keep their elements from the rows before them. */
        private final Set<Integer> fitting = new LinkedHashSet<>();
        /** Why the row being dealt could not take its pin, the first time it was found. */
        private Reason pinMissed;

        Table(Request request) {
            this.request = request;
            this.pools = request.pools();
            for (int row = 0; row < request.runs(); row++) {
                Optional<Reason> unfit = unfit(row, pinsOf(row));
                if (unfit.isPresent()) {
                    reasons.add(unfit.get());
                } else {
                    fitting.add(row);
                    reserve(row, 1);
                }
            }
        }

        Dealing deal() {
            List<Row> rows = new ArrayList<>();
            // Whether an unpinned row found nothing since the free elements last grew: the next finds nothing too,
            // since each row tries every option its pools leave.
            boolean dry = false;
            for (int index = 0; index < request.runs(); index++) {
                Map<String, List<String>> pins = pinsOf(index);
                if (!fitting.contains(index)) continue;
                reserve(index, -1);
                if (!pins.isEmpty()) {
                    pinMissed = null;
                    Map<String, List<String>> picks = new LinkedHashMap<>();
                    if (fill(index, 0, pins, picks)) {
                        take(rows, index, picks);
                    } else {
                        reasons.add(pinMissed != null ? pinMissed : new Reason(Reason.Kind.PIN, "", List.of(), 1,
                                "Run " + (index + 1) + "'s pins do not go with the rest of its row"));
                        // Its pins are free again: the rows after it may take them.
                        dry = false;
                    }
                    continue;
                }
                if (dry) continue;
                Map<String, List<String>> picks = new LinkedHashMap<>();
                if (fill(index, 0, pins, picks)) take(rows, index, picks);
                else dry = true;
            }
            if (rows.size() < request.runs()) explain(rows.size());
            return new Dealing(request.runs(), rows, reasons);
        }

        private void take(List<Row> rows, int index, Map<String, List<String>> picks) {
            picks.forEach((pool, pick) -> pick.forEach(element -> count(taken, pool, element, 1)));
            rows.add(new Row(index, picks));
        }

        private Map<String, List<String>> pinsOf(int row) {
            Map<String, List<String>> pins = request.pins().get(row);
            return pins == null ? Map.of() : pins;
        }

        private void reserve(int row, int by) {
            pinsOf(row).forEach((pool, pick) -> pick.forEach(element -> count(reserved, pool, element, by)));
        }

        private static void count(Map<String, Map<String, Integer>> counts, String pool, String element, int by) {
            counts.computeIfAbsent(pool, any -> new HashMap<>()).merge(element, by, Integer::sum);
        }

        private static int counted(Map<String, Map<String, Integer>> counts, String pool, String element) {
            return counts.getOrDefault(pool, Map.of()).getOrDefault(element, 0);
        }

        /**
         * Why a row's pins can never be dealt, whatever the rows before it take: a pool none fills, a pick of the
         * wrong size or not in its pool, an element live runs hold as often as it may be held.
         */
        private Optional<Reason> unfit(int row, Map<String, List<String>> pins) {
            for (Map.Entry<String, List<String>> pin : pins.entrySet()) {
                Optional<Source> pool = pools.stream().filter(one -> one.parameter().equals(pin.getKey())).findFirst();
                List<String> pick = pin.getValue() == null ? List.of() : pin.getValue();
                String why = pool.isEmpty() ? "no pool fills " + pin.getKey()
                        : pick.size() != pool.get().perRun() || new LinkedHashSet<>(pick).size() != pick.size()
                                ? pool.get().name() + " takes " + pool.get().perRun() + " different elements"
                                : pick.stream().filter(element -> !pool.get().elements().contains(element)).findFirst()
                                        .map(element -> element + " is not in " + pool.get().name())
                                        .orElseGet(() -> pick.stream()
                                                .filter(element -> held(pool.get(), element) >= share(pool.get(), element))
                                                .findFirst()
                                                .map(element -> element + " is held by a run of " + holder(pool.get(),
                                                        element).map(LiveRun::bot).orElse("another bot"))
                                                .orElse(null));
                if (why != null) {
                    return Optional.of(new Reason(Reason.Kind.PIN, pin.getKey(), pick, 1,
                            "Run " + (row + 1) + "'s pin: " + why));
                }
            }
            return Optional.empty();
        }

        /** Fills {@code picks} from pool {@code at} on, going back on a dead end. */
        private boolean fill(int row, int at, Map<String, List<String>> pins, Map<String, List<String>> picks) {
            if (at == pools.size()) return true;
            Source pool = pools.get(at);
            List<String> pinned = pins.get(pool.parameter());
            List<String> free = pool.elements().stream().filter(element -> slots(pool, element, pinned != null) > 0)
                    .toList();
            if (pinned != null) {
                boolean fits = free.containsAll(pinned) && narrowed(pool, free, picks).containsAll(pinned);
                if (!fits) {
                    if (pinMissed == null) pinMissed = pinReason(row, pool, pinned, free);
                    return false;
                }
                picks.put(pool.parameter(), pinned);
                if (fill(row, at + 1, pins, picks)) return true;
                picks.remove(pool.parameter());
                return false;
            }
            List<String> left = narrowed(pool, ordered(pool, row, free), picks);
            if (choose(left, pool.perRun(), 0, new ArrayList<>(),
                    option -> {
                        picks.put(pool.parameter(), option);
                        if (fill(row, at + 1, pins, picks)) return true;
                        picks.remove(pool.parameter());
                        return false;
                    })) {
                return true;
            }
            // Free elements the owner leaves too few of: what the row's first pick was goes with none of them.
            if (left.size() < pool.perRun() && free.size() >= pool.perRun()) {
                Set<String> without = stuck.computeIfAbsent(pool.parameter(), any -> new LinkedHashSet<>());
                if (at == 0) without.addAll(free);
                else without.addAll(picks.get(pools.getFirst().parameter()));
            }
            return false;
        }

        /** Tries each choice of {@code k} of {@code left} in order, until {@code then} takes one. */
        private static boolean choose(List<String> left, int k, int from, List<String> chosen,
                                      Predicate<List<String>> then) {
            if (chosen.size() == k) return then.test(List.copyOf(chosen));
            for (int i = from; i <= left.size() - (k - chosen.size()); i++) {
                chosen.add(left.get(i));
                if (choose(left, k, i + 1, chosen, then)) return true;
                chosen.removeLast();
            }
            return false;
        }

        private Reason pinReason(int row, Source pool, List<String> pinned, List<String> free) {
            String run = "Run " + (row + 1) + "'s pin: ";
            for (String element : pinned) {
                if (free.contains(element)) continue;
                return new Reason(Reason.Kind.PIN, pool.parameter(), pinned, 1, run + element
                        + " is dealt to another run");
            }
            return new Reason(Reason.Kind.PIN, pool.parameter(), pinned, 1,
                    run + String.join(", ", pinned) + " does not go with the rest of its row");
        }

        /** How many more rows may take {@code element}; a pin ignores what later rows pin. */
        private int slots(Source pool, String element, boolean pinned) {
            return share(pool, element) - held(pool, element) - counted(taken, pool.parameter(), element)
                    - (pinned ? 0 : counted(reserved, pool.parameter(), element));
        }

        /**
         * How many live runs hold {@code element}. A run that claimed it while it ran ({@link Hold#key()}) holds it
         * whole: the registry refuses its hold to any other run.
         */
        private int held(Source pool, String element) {
            Hold asked = pool.hold(element);
            int holds = (int) request.live().stream().flatMap(run -> run.holds().stream()).filter(asked::sameElement)
                    .count();
            return claimer(asked).isPresent() ? Math.max(holds, share(pool, element)) : holds;
        }

        private Optional<LiveRun> claimer(Hold asked) {
            return request.live().stream().filter(run -> run.claims().stream().anyMatch(asked::claimedBy)).findFirst();
        }

        /** How many runs may hold {@code element} at once: the least any hold of it allows, as the registry counts. */
        private int share(Source pool, String element) {
            Hold asked = pool.hold(element);
            return request.live().stream().flatMap(run -> run.holds().stream()).filter(asked::sameElement)
                    .mapToInt(Hold::share).reduce(pool.each(), Math::min);
        }

        private Optional<LiveRun> holder(Source pool, String element) {
            Hold asked = pool.hold(element);
            return request.live().stream().filter(run -> run.holds().stream().anyMatch(asked::sameElement))
                    .findFirst().or(() -> claimer(asked));
        }

        /**
         * {@code free} in the order to try for {@code row}: its last pick first, or the least recently used. A shared
         * pool then deals the elements with the most room left first, so rows that take several spread over them.
         */
        private List<String> ordered(Source pool, int row, List<String> free) {
            DealHistory last = request.last();
            List<String> out = new ArrayList<>(free);
            if (pool.rotates()) {
                out.sort(Comparator.comparingLong(element -> last.usedAt(pool.parameter(), element)));
            } else {
                List<String> mine = last.held(row, pool.parameter());
                Set<String> others = new LinkedHashSet<>();
                for (int other = 0; other < request.runs(); other++) {
                    if (other != row) others.addAll(last.held(other, pool.parameter()));
                }
                // The row's own last pick, then what no other row asked for held, then the rest: each stable.
                out.sort(Comparator.comparingInt(
                        element -> mine.contains(element) ? 0 : others.contains(element) ? 2 : 1));
            }
            if (pool.each() > 1) {
                out.sort(Comparator.comparingInt((String element) -> slots(pool, element, false)).reversed());
            }
            return out;
        }

        private List<String> narrowed(Source pool, List<String> free, Map<String, List<String>> picks) {
            if (pool.narrowing() == Narrowing.NONE) return free;
            List<Object> key = List.of(pool.parameter(), List.copyOf(free), Map.copyOf(picks));
            List<String> known = narrowed.get(key);
            if (known != null) return known;
            List<String> answered;
            try {
                answered = pool.narrowing().left(List.copyOf(free), Collections.unmodifiableMap(picks));
            } catch (Failed failed) {
                throw failed;
            } catch (RuntimeException | LinkageError e) {
                throw new Failed(pool.owner(), "failed while narrowing " + pool.name(), e);
            }
            Set<String> among = Set.copyOf(free);
            List<String> left = answered == null ? List.of()
                    : new LinkedHashSet<>(answered).stream().filter(Objects::nonNull).filter(among::contains).toList();
            narrowed.put(key, left);
            return left;
        }

        /** Why rows are missing, counted over the pools as the dealing left them. */
        private void explain(int dealt) {
            int before = reasons.size();
            for (Source pool : pools) {
                int all = pool.elements().stream().mapToInt(element -> share(pool, element)).sum();
                int room = pool.elements().stream()
                        .mapToInt(element -> Math.max(0, share(pool, element) - held(pool, element))).sum();
                int needed = request.runs() * pool.perRun();
                // Another run's hold is a reason only where the pool falls short.
                if (needed > room) {
                    for (String element : pool.elements()) {
                        if (held(pool, element) >= share(pool, element)) {
                            String by = holder(pool, element).map(LiveRun::bot).orElse("another bot");
                            reasons.add(new Reason(Reason.Kind.HELD, pool.parameter(), List.of(element), 1,
                                    element + " is held by a run of " + by));
                        }
                    }
                }
                if (needed > all) {
                    int missing = Math.ceilDiv(needed - all, pool.each());
                    reasons.add(new Reason(Reason.Kind.TOO_FEW, pool.parameter(), List.of(), missing,
                            missing + " more of " + pool.name() + (missing == 1 ? " is" : " are") + " needed"));
                }
            }
            Source first = pools.isEmpty() ? null : pools.getFirst();
            stuck.forEach((parameter, without) -> {
                Source pool = pools.stream().filter(one -> one.parameter().equals(parameter)).findFirst()
                        .orElseThrow();
                // Only what is still free: an element a dealt row took found its row after all.
                List<String> still = without.stream()
                        .filter(element -> counted(taken, first.parameter(), element) == 0).toList();
                if (still.isEmpty()) return;
                String words = pool == first ? "Nothing free in " + pool.name() + " can be dealt"
                        : still.size() + " of " + first.name() + " " + (still.size() == 1 ? "has" : "have")
                          + " nothing free in " + pool.name();
                reasons.add(new Reason(Reason.Kind.INCOMPLETE, first.parameter(), still, still.size(),
                        words + ": " + String.join(", ", still)));
            });
            if (reasons.size() == before && reasons.stream().noneMatch(reason -> reason.kind() == Reason.Kind.PIN)) {
                reasons.add(new Reason(Reason.Kind.INCOMPLETE, "", List.of(), request.runs() - dealt,
                        "the free elements make no more complete rows"));
            }
        }
    }
}
