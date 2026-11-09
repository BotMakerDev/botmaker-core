package com.botmaker.core.server;

import com.botmaker.core.CoreJson;
import com.botmaker.core.edit.Edit;
import com.botmaker.core.edit.EditRequest;
import com.botmaker.core.edit.EditResult;
import com.botmaker.core.edit.HistoryStep;
import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.plugin.PluginWrite;
import com.botmaker.core.run.trial.TrialAsk;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        return connect(at, version, told, call -> { }, ended);
    }

    /**
     * As {@link #connect(CoreLauncher.Address, String, Consumer, Runnable)}, with {@code called} hearing each call the
     * core makes of this client's tools ({@link Reply.Call}), on the reader thread: it answers with {@link #called}
     * from another, since the answer is read on this one.
     */
    public static CoreClient connect(CoreLauncher.Address at, String version, Consumer<CoreEvent> told,
                                     Consumer<Reply.Call> called, Runnable ended) throws IOException {
        CoreClient client = new CoreClient();
        client.channel = ChannelClient.open(at, version, text -> client.received(text, told, called), () -> {
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

    /** Review's Undo of a refactor's change to {@code function}; see {@link Request.Restore}. */
    public EditResult restore(String file, String function, List<String> entries) throws IOException {
        return ask(new Request.Restore(ids.incrementAndGet(), file, function, entries), ANSWER_TIMEOUT);
    }

    /** The MCP tools this client serves through the core from now on, each as its JSON; see {@link Request.Tools}. */
    public EditResult tools(List<String> tools, boolean admits) throws IOException {
        return ask(new Request.Tools(ids.incrementAndGet(), tools, admits), ANSWER_TIMEOUT);
    }

    /** This client's answer, an MCP result as its JSON, to the core's call {@code call}; see {@link Reply.Call}. */
    public EditResult called(long call, String result) throws IOException {
        return ask(new Request.Called(ids.incrementAndGet(), call, result), ANSWER_TIMEOUT);
    }

    /** A write of the bot's values a client's plugin asked for; see {@link Request.PluginWriting}. */
    public EditResult pluginWrite(PluginWrite write) throws IOException {
        return ask(new Request.PluginWriting(ids.incrementAndGet(), write), ANSWER_TIMEOUT);
    }

    /** Opens ({@code open}) or ends a plugin's batch; see {@link Request.Batch}. */
    public EditResult batch(String label, boolean open) throws IOException {
        return ask(new Request.Batch(ids.incrementAndGet(), label, open), ANSWER_TIMEOUT);
    }

    /**
     * The project's libraries changed; given as long as an open, since Maven may download. What they resolved to is
     * told as {@code LibrariesResolved} before the answer.
     */
    public EditResult reload() throws IOException {
        return ask(new Request.Reload(ids.incrementAndGet()), OPEN_TIMEOUT);
    }

    public EditResult compile() throws IOException {
        return ask(new Request.Compile(ids.incrementAndGet()), ANSWER_TIMEOUT);
    }

    public EditResult run() throws IOException {
        return ask(new Request.Run(ids.incrementAndGet()), ANSWER_TIMEOUT);
    }

    public EditResult runEntry(String title, String mainClass) throws IOException {
        return ask(new Request.RunEntry(ids.incrementAndGet(), title, mainClass), ANSWER_TIMEOUT);
    }

    /** A ▶ Try, planned by the core; see {@link Request.Try}. */
    public EditResult tryOut(TrialAsk trial) throws IOException {
        return ask(new Request.Try(ids.incrementAndGet(), trial), ANSWER_TIMEOUT);
    }

    /** Starts a debug session; see {@link Request.Debug}. */
    public EditResult debug(boolean follow, Map<String, Set<String>> breakpoints) throws IOException {
        return ask(new Request.Debug(ids.incrementAndGet(), follow, breakpoints), ANSWER_TIMEOUT);
    }

    public EditResult stop() throws IOException {
        return ask(new Request.Stop(ids.incrementAndGet()), ANSWER_TIMEOUT);
    }

    /** The user's answer to the running bot's question {@code asked}. */
    public EditResult answer(long asked, String value) throws IOException {
        return ask(new Request.Answer(ids.incrementAndGet(), asked, value), ANSWER_TIMEOUT);
    }

    public EditResult debugging(Request.Debugging.Step step) throws IOException {
        return ask(new Request.Debugging(ids.incrementAndGet(), step), ANSWER_TIMEOUT);
    }

    /** Detaches from the project; the channel stays open for another. */
    public EditResult leave() throws IOException {
        return leave(false);
    }

    /** {@link #leave()}, the bot running now kept going when {@code keepRun} ({@link Request.Close}). */
    public EditResult leave(boolean keepRun) throws IOException {
        return ask(new Request.Close(ids.incrementAndGet(), keepRun), ANSWER_TIMEOUT);
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

    private void received(String text, Consumer<CoreEvent> told, Consumer<Reply.Call> called) {
        switch (CoreJson.read(text, Reply.class)) {
            case Reply.Answer answer -> {
                CompletableFuture<EditResult> asked = waiting.get(answer.id());
                if (asked != null) asked.complete(answer.result());
            }
            case Reply.Told change -> told.accept(change.event());
            case Reply.Call call -> called.accept(call);
        }
    }

    private void failWaiting() {
        waiting.values().forEach(asked -> asked.completeExceptionally(new IOException("The core stopped.")));
    }
}
