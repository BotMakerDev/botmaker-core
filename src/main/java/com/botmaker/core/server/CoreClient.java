package com.botmaker.core.server;

import com.botmaker.core.CoreJson;
import com.botmaker.core.edit.Edit;
import com.botmaker.core.edit.EditRequest;
import com.botmaker.core.edit.EditResult;
import com.botmaker.core.edit.HistoryStep;
import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.events.FileEdit;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A client of the core's process: each ask is one {@link Request} over its {@link ChannelClient}, answered in turn,
 * and every change of the project it is attached to reaches {@code told}, the client's own asks' changes too, before
 * their answers. Studio's editor speaks to the core through this from the plan's phase 10a1.
 */
public final class CoreClient implements AutoCloseable {

    /** How long an open may take: its libraries may have to be downloaded. */
    static final Duration OPEN_TIMEOUT = Duration.ofMinutes(10);
    /** How long any other ask may take before the core is treated as gone. */
    static final Duration ANSWER_TIMEOUT = Duration.ofMinutes(1);

    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<EditResult>> waiting = new ConcurrentHashMap<>();
    private volatile ChannelClient channel;
    private volatile boolean ended;

    private CoreClient() {
    }

    /**
     * Opens a channel to the core at {@code at} as a client of {@code version}. {@code told} hears each change on the
     * channel's one reader thread; {@code ended} runs once when the channel ends, by either side.
     *
     * @throws CoreLauncher.Refused when the core is of another version
     */
    public static CoreClient connect(CoreLauncher.Address at, String version, Consumer<CoreEvent> told,
                                     Runnable ended) throws IOException {
        CoreClient client = new CoreClient();
        client.channel = ChannelClient.open(at, version, text -> client.received(text, told), () -> {
            client.ended = true;
            client.failWaiting();
            ended.run();
        });
        return client;
    }

    /** Attaches to the project in {@code project}, which the core opens when no client has. */
    public EditResult open(Path project) throws IOException {
        return ask(new Request.Open(ids.incrementAndGet(), project.toAbsolutePath().normalize().toString()),
                OPEN_TIMEOUT);
    }

    /** Makes {@code file}, by its path below the bot's source root, the open file. */
    public EditResult show(String file) throws IOException {
        return ask(new Request.Show(ids.incrementAndGet(), file), ANSWER_TIMEOUT);
    }

    public EditResult apply(EditRequest request) throws IOException {
        return ask(new Request.Apply(ids.incrementAndGet(), request), ANSWER_TIMEOUT);
    }

    public EditResult userEdit(Edit edit) throws IOException {
        return ask(new Request.UserEdit(ids.incrementAndGet(), edit), ANSWER_TIMEOUT);
    }

    public EditResult step(HistoryStep step) throws IOException {
        return ask(new Request.Step(ids.incrementAndGet(), step), ANSWER_TIMEOUT);
    }

    /** An assistant's finished session, by its texts; see {@link Request.Commit}. */
    public EditResult commit(String file, String original, String source, String label) throws IOException {
        return ask(new Request.Commit(ids.incrementAndGet(), file, original, source, label), ANSWER_TIMEOUT);
    }

    /** A write of the open file made outside an edit; see {@link Request.Rewritten}. */
    public EditResult rewritten(String file, String original, String source) throws IOException {
        return ask(new Request.Rewritten(ids.incrementAndGet(), file, original, source), ANSWER_TIMEOUT);
    }

    /** What a plugin already wrote, recorded as one step; see {@link Request.Recorded}. */
    public EditResult recorded(String label, List<FileEdit> edits) throws IOException {
        return ask(new Request.Recorded(ids.incrementAndGet(), label, edits), ANSWER_TIMEOUT);
    }

    /** The project's libraries changed; given as long as an open, since Maven may download. */
    public EditResult reload() throws IOException {
        return ask(new Request.Reload(ids.incrementAndGet()), OPEN_TIMEOUT);
    }

    /** Detaches from the project; the channel stays open for another. */
    public EditResult leave() throws IOException {
        return ask(new Request.Close(ids.incrementAndGet()), ANSWER_TIMEOUT);
    }

    public boolean isOpen() {
        return !ended && channel.isOpen();
    }

    @Override
    public void close() {
        channel.close();
    }

    private EditResult ask(Request request, Duration timeout) throws IOException {
        CompletableFuture<EditResult> answer = new CompletableFuture<>();
        waiting.put(request.id(), answer);
        try {
            if (ended) throw new IOException("The core stopped.");
            channel.send(CoreJson.write(request));
            return answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("The core did not answer in " + timeout.toSeconds() + " s.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } finally {
            waiting.remove(request.id());
        }
    }

    private void received(String text, Consumer<CoreEvent> told) {
        switch (CoreJson.read(text, Reply.class)) {
            case Reply.Answer answer -> {
                CompletableFuture<EditResult> asked = waiting.get(answer.id());
                if (asked != null) asked.complete(answer.result());
            }
            case Reply.Told change -> told.accept(change.event());
        }
    }

    private void failWaiting() {
        waiting.values().forEach(asked -> asked.completeExceptionally(new IOException("The core stopped.")));
    }
}
