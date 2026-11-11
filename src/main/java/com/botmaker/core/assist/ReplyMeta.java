package com.botmaker.core.assist;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What one of the core's own tools adds to its reply's {@code _meta} for a watching client (spec
 * {@code 2026-11-11-assistant-window-design.md}): the point a tap landed on, the methods an edit changed. A tool's
 * {@code AgentReply} is the contract's and carries no such keys, so a call collects them here while it runs, on its
 * own thread, and {@link McpTools#served} puts them on the result. Outside a call, a put is dropped.
 */
public final class ReplyMeta {

    /** {@code {x, y}}, or {@code {path: [{x, y}, …]}} for a swipe: where the call touched the screen, in its pixels. */
    public static final String TAP = "botmaker/tap";
    /** {@code [{file, class, method}, …]}: the methods the call's edit changed, the file absolute. */
    public static final String CHANGED = "botmaker/changed";

    private static final ScopedValue<Map<String, Object>> CALL = ScopedValue.newInstance();

    private ReplyMeta() {}

    /** {@code key} said for the call running on this thread; nothing outside one. */
    static void put(String key, Object value) {
        if (CALL.isBound()) CALL.get().put(key, value);
    }

    /** The point {@code (x, y)}. */
    static Map<String, Object> point(int x, int y) {
        return Map.of("x", x, "y", y);
    }

    /** A path through {@code points}. */
    static Map<String, Object> path(List<Map<String, Object>> points) {
        return Map.of("path", List.copyOf(points));
    }

    /** One method an edit changed. */
    static Map<String, Object> changed(String file, String className, String method) {
        return Map.of("file", file, "class", className, "method", method);
    }

    /** Runs {@code call} collecting what it says; its answer and the keys, empty when none. */
    static <T> Collected<T> collecting(Supplier<T> call) {
        Map<String, Object> said = new LinkedHashMap<>();
        T answer = ScopedValue.where(CALL, said).call(call::get);
        return new Collected<>(answer, Map.copyOf(said));
    }

    /** A call's answer and the {@code _meta} keys it said. */
    record Collected<T>(T answer, Map<String, Object> meta) {}
}
