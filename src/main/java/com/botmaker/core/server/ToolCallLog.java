package com.botmaker.core.server;

import com.botmaker.core.assist.PluginTools;
import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.event.CoreEvent.ToolCallEnded;
import com.botmaker.core.event.CoreEvent.ToolCallEnded.Outcome;
import com.botmaker.core.event.CoreEvent.ToolCallEnded.Part;
import com.botmaker.core.event.CoreEvent.ToolCallStarted;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Every MCP call the core serves, told as it begins and ends to whoever watches ({@link ServedTools#watch}): Studio's
 * Assistant window lists them (spec {@code 2026-11-11-assistant-window-design.md}). The last {@link #KEPT} calls are
 * kept in memory only, this core's session, the images of the last {@link #IMAGES_KEPT} only, and a watcher that comes
 * later is told them first.
 *
 * <p>A call only hands its start and its answer over: the log and its watchers live on one thread of their own, which
 * makes the thumbnails and writes to each watcher, so a slow client never holds up a tool's answer, and a watcher hears
 * each event once, in order.
 */
final class ToolCallLog {

    static final int KEPT = 500;
    /** The calls, newest first, whose images are kept; an older call's image is said in text. */
    static final int IMAGES_KEPT = 50;
    /** An image's long side in a reply part; the full image stays the tool's answer only. */
    static final int THUMBNAIL_SIDE = 960;
    static final String IMAGE_GONE = "[an image no longer kept]";

    private final McpJsonMapper json;
    private final Clock clock;
    private final AtomicLong ids = new AtomicLong();
    private final ExecutorService told = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon()
            .name("tool-call-log").factory());
    /** The kept calls by id, oldest first; a running one has no end yet. Only {@link #told}'s thread touches it. */
    private final Map<Long, Kept> kept = new LinkedHashMap<>();
    private final List<Consumer<CoreEvent>> watchers = new ArrayList<>();

    private record Kept(ToolCallStarted started, ToolCallEnded ended) {
    }

    ToolCallLog(McpJsonMapper json, Clock clock) {
        this.json = json;
        this.clock = clock;
    }

    /** {@code watcher} hears every kept call, then each call as it begins and ends, until the returned stop. */
    Runnable watch(Consumer<CoreEvent> watcher) {
        told.execute(() -> {
            for (CoreEvent event : events()) deliver(watcher, event);
            watchers.add(watcher);
        });
        return () -> told.execute(() -> watchers.remove(watcher));
    }

    /** {@code tool}, its calls logged; the answer is the tool's own, whatever the log does. */
    SyncToolSpecification logged(SyncToolSpecification tool) {
        String name = tool.tool().name();
        String plugin = PluginTools.pluginOf(tool.tool());
        return SyncToolSpecification.builder().tool(tool.tool()).callHandler((exchange, request) -> {
            long call = ids.incrementAndGet();
            long began = System.nanoTime();
            ToolCallStarted started = new ToolCallStarted(call, name, plugin, arguments(request.arguments()),
                    clock.millis());
            told.execute(() -> record(started));
            CallToolResult result;
            try {
                result = tool.callHandler().apply(exchange, request);
            } catch (RuntimeException | Error e) {
                long millis = millisSince(began);
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                told.execute(() -> record(new ToolCallEnded(call, Outcome.ERROR, List.of(new Part(Part.Kind.TEXT, why)),
                        millis)));
                throw e;
            }
            long millis = millisSince(began);
            told.execute(() -> record(new ToolCallEnded(call, outcome(result), parts(result), millis)));
            return result;
        }).build();
    }

    /** The kept calls, oldest first, each its start then its end when it has one, once every call so far is kept. */
    List<CoreEvent> kept() {
        try {
            return told.submit(this::events).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    private List<CoreEvent> events() {
        List<CoreEvent> out = new ArrayList<>();
        for (Kept call : kept.values()) {
            out.add(call.started());
            if (call.ended() != null) out.add(call.ended());
        }
        return out;
    }

    private void record(CoreEvent event) {
        switch (event) {
            case ToolCallStarted started -> {
                kept.put(started.call(), new Kept(started, null));
                while (kept.size() > KEPT) kept.remove(kept.keySet().iterator().next());
            }
            case ToolCallEnded ended -> {
                kept.computeIfPresent(ended.call(), (id, call) -> new Kept(call.started(), ended));
                forgetOldImages();
            }
            default -> throw new IllegalArgumentException("not a tool call's event: " + event);
        }
        for (Consumer<CoreEvent> watcher : watchers) deliver(watcher, event);
    }

    /** Says in text the images of every call but the newest {@link #IMAGES_KEPT}: the kept calls' memory stays small. */
    private void forgetOldImages() {
        int older = kept.size() - IMAGES_KEPT;
        for (Map.Entry<Long, Kept> call : kept.entrySet()) {
            if (older-- <= 0) return;
            ToolCallEnded ended = call.getValue().ended();
            if (ended == null || ended.reply().stream().noneMatch(part -> part.kind() == Part.Kind.IMAGE)) continue;
            List<Part> reply = ended.reply().stream().map(part -> part.kind() == Part.Kind.IMAGE
                    ? new Part(Part.Kind.TEXT, IMAGE_GONE) : part).toList();
            call.setValue(new Kept(call.getValue().started(),
                    new ToolCallEnded(ended.call(), ended.outcome(), reply, ended.millis())));
        }
    }

    private static void deliver(Consumer<CoreEvent> watcher, CoreEvent event) {
        try {
            watcher.accept(event);
        } catch (RuntimeException e) {
            // A watcher that fails misses this event; the others and the call go on.
            System.err.println("Warning: a tool call's event could not be told: " + e.getMessage());
        }
    }

    private String arguments(Map<String, Object> arguments) {
        try {
            return json.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (IOException e) {
            return "{}";
        }
    }

    static Outcome outcome(CallToolResult result) {
        if (!Boolean.TRUE.equals(result.isError())) return Outcome.OK;
        boolean refused = result.content() != null && result.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent).map(c -> ((McpSchema.TextContent) c).text())
                .findFirst().map(text -> text != null && text.startsWith("REFUSED")).orElse(false);
        return refused ? Outcome.REFUSED : Outcome.ERROR;
    }

    static List<Part> parts(CallToolResult result) {
        List<Part> out = new ArrayList<>();
        for (McpSchema.Content content : result.content() == null ? List.<McpSchema.Content>of() : result.content()) {
            switch (content) {
                case McpSchema.TextContent text -> out.add(new Part(Part.Kind.TEXT, text.text()));
                case McpSchema.ImageContent image -> out.add(thumbnail(image.data())
                        .map(jpeg -> new Part(Part.Kind.IMAGE, jpeg))
                        .orElseGet(() -> new Part(Part.Kind.TEXT, "[an image that could not be read]")));
                default -> out.add(new Part(Part.Kind.TEXT, "[" + content.type() + "]"));
            }
        }
        return out;
    }

    /**
     * {@code base64}'s image as a JPEG whose long side is at most {@link #THUMBNAIL_SIDE}, in base64; what is
     * transparent shows white, as a viewer draws it.
     */
    static Optional<String> thumbnail(String base64) {
        try {
            BufferedImage read = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
            if (read == null) return Optional.empty();
            double scale = Math.min(1.0, (double) THUMBNAIL_SIDE / Math.max(read.getWidth(), read.getHeight()));
            int width = Math.max(1, (int) Math.round(read.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(read.getHeight() * scale));
            BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, width, height);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(read, 0, 0, width, height, null);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(out, "jpg", bytes)) return Optional.empty();
            return Optional.of(Base64.getEncoder().encodeToString(bytes.toByteArray()));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static long millisSince(long began) {
        return (System.nanoTime() - began) / 1_000_000;
    }
}
