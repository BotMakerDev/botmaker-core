package com.botmaker.core.run.claims;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a start of several runs asks (spec {@code 2026-11-15-run-claims-design.md} §5): how many runs, what to do when
 * fewer can run, and the picks the user fixed by hand.
 *
 * @param runs how many runs, 1 to {@link SeveralRuns#MOST}
 * @param how  start what can run, start all or none, or only count what can
 * @param pins by row index, from 0, then by pool: the picks dealt as given, or the row is not dealt
 */
public record SeveralAsk(int runs, How how, Map<Integer, Map<String, List<String>>> pins) {

    /** What the start does with the rows dealt. */
    public enum How {
        /** Starts every row dealt, however many fewer than asked. */
        START,
        /** Starts the rows only when every run asked for is dealt; else none. */
        ALL_OR_NONE,
        /** Deals and says what could run, holding and starting nothing. */
        COUNT
    }

    /** {@code [run:]pool=value}: the run from 1, the first when not given; the pool as the dealt picks name it. */
    private static final Pattern PIN = Pattern.compile("(?:(\\d+):)?([^=]+)=(.+)", Pattern.DOTALL);

    public SeveralAsk {
        if (runs < 1 || runs > SeveralRuns.MOST) {
            throw new IllegalArgumentException("several runs are 1 to " + SeveralRuns.MOST + ", not " + runs);
        }
        if (how == null) throw new IllegalArgumentException("a start of several runs says how it starts them");
        Map<Integer, Map<String, List<String>>> copy = new LinkedHashMap<>();
        if (pins != null) {
            for (Map.Entry<Integer, Map<String, List<String>>> row : pins.entrySet()) {
                if (row.getKey() == null || row.getKey() < 0 || row.getKey() >= runs) {
                    throw new IllegalArgumentException("a pin is for run 1 to " + runs + ", not " + row.getKey());
                }
                if (row.getValue() == null) throw new IllegalArgumentException("run " + (row.getKey() + 1) + "'s pins are missing");
                Map<String, List<String>> picks = new LinkedHashMap<>();
                row.getValue().forEach((pool, pick) -> {
                    if (pool == null || pick == null || pick.stream().anyMatch(Objects::isNull)) {
                        throw new IllegalArgumentException("a pin names its pool and its values, not " + pool + "=" + pick);
                    }
                    picks.put(pool, List.copyOf(pick));
                });
                copy.put(row.getKey(), Map.copyOf(picks));
            }
        }
        pins = Map.copyOf(copy);
    }

    /** {@code runs} runs, each row dealt started, nothing pinned. */
    public static SeveralAsk of(int runs) {
        return new SeveralAsk(runs, How.START, Map.of());
    }

    /**
     * {@code runs} runs started {@code how}, pinned by {@code written}: each {@code [run:]pool=value}, the run from 1
     * (the first when not given), the pool and the value as a count's picks write them. A pool pinned twice in one run
     * takes both values, for a pool that deals several to a run.
     *
     * @throws IllegalArgumentException for a pin written otherwise, or for a run past {@code runs}
     */
    public static SeveralAsk pinned(int runs, How how, List<String> written) {
        Map<Integer, Map<String, List<String>>> pins = new LinkedHashMap<>();
        for (String pin : written == null ? List.<String>of() : written) {
            Matcher parts = PIN.matcher(pin == null ? "" : pin.strip());
            if (!parts.matches() || parts.group(2).isBlank()) {
                throw new IllegalArgumentException("A pin is pool=value or run:pool=value, not " + pin);
            }
            // Past three digits it is past SeveralRuns.MOST, and past an int.
            int run = parts.group(1) == null ? 1 : parts.group(1).length() > 3 ? -1 : Integer.parseInt(parts.group(1));
            if (run < 1 || run > runs) {
                throw new IllegalArgumentException("A pin is for run 1 to " + runs + ", not " + run + ": " + pin);
            }
            pins.computeIfAbsent(run - 1, row -> new LinkedHashMap<>())
                    .computeIfAbsent(parts.group(2).strip(), pool -> new ArrayList<>()).add(parts.group(3).strip());
        }
        return new SeveralAsk(runs, how, pins);
    }
}
