package com.botmaker.core.server;

import com.botmaker.core.CoreJson;
import com.botmaker.core.assist.CoreHome;
import com.botmaker.core.assist.PluginTools;
import com.botmaker.core.assist.RunLog;
import com.botmaker.core.edit.EditResult;
import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.plugin.HomePlugins;
import com.botmaker.core.plugin.HomeServices;
import com.botmaker.core.plugin.HostServices;
import com.botmaker.core.plugin.PluginHost;
import com.botmaker.core.plugin.UiPort;
import com.botmaker.core.run.RunEntries;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * The project this core has open and the channels attached to it. One project at a time: the plugin host binds one
 * project's plugins per process, so a client asking for another while one is open is refused until its clients leave.
 *
 * <p>Opening, leaving and a channel's end run one after another on one thread of their own, since resolving a
 * project's libraries can take minutes: a client that leaves while its project opens is let go once it has opened, and
 * a close waits for the asks the client sent before it. The other asks go to the project's own thread.
 *
 * <p>A project stays open with no client in two cases (plan phase 11): the MCP opened it ({@code open_project}, no
 * Studio), until a client opens it or asks for another; or its last client left asking to keep its run going, until
 * that run ends. Either way a client's open of another project closes it first, unless a bot runs.
 */
public final class Projects implements AutoCloseable, CoreHome.Host {

    private static final ObjectMapper LENIENT = new ObjectMapper();
    private static final McpJsonMapper JSON = new JacksonMcpJsonMapper(new ObjectMapper());

    private final OpenProject.Libraries libraries;
    private final ServedTools tools;
    private final Map<Channel, OpenProject> attached = new ConcurrentHashMap<>();
    private final ExecutorService opener = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "core-open");
        thread.setDaemon(true);
        return thread;
    });
    /** What the open project's runs printed and traced, for the core's own run tools. */
    private final RunLog log = new RunLog(Clock.systemUTC(), this::busy);
    private final AtomicLong asks = new AtomicLong();
    /** Set and read on the opener's thread, and by {@link #close}. */
    private volatile OpenProject open;
    /** The server serving these projects; null in Studio's in-process core and until it runs. */
    private volatile CoreServer server;
    /** The MCP opened {@link #open} and no client took it over. On the opener's thread. */
    private boolean held;
    /** {@link #open}'s last client left keeping its run going. On the opener's thread. */
    private boolean kept;
    /** The catalog's plugins bound with no project open, resolved once; null until the server runs. On the opener's thread. */
    private CompletableFuture<HomePlugins.Home> home;
    /** Whether the core binds the catalog's plugins with no project open ({@link #serveHomePlugins}). */
    private volatile boolean servesHome;
    /** Whether {@link #home}'s plugins are the ones bound now. On the opener's thread. */
    private boolean homeBound;
    /** What {@link #home} bound and left out, for {@code core_status}. */
    private volatile List<String> homeSaid = List.of();

    public Projects(OpenProject.Libraries libraries) {
        this(libraries, new ServedTools());
    }

    /** Projects whose open one's own tools, each client's, and the core's home and run tools, {@code tools} serves. */
    Projects(OpenProject.Libraries libraries, ServedTools tools) {
        this.libraries = libraries;
        this.tools = tools;
        tools.fallback(CoreHome.served(JSON, this, log));
        tools.held(CoreHome.device(JSON));
        tools.lasting(CoreHome.lasting(JSON, this));
    }

    /** Serves {@code channel}'s requests from now on; what {@link CoreServer.Hooks#opened} is given. */
    public void attach(Channel channel) {
        channel.onMessage(text -> received(channel, text));
        channel.onClose(() -> inTurn(() -> leave(channel)));
    }

    /** Whether a project is open. */
    public boolean hasOpen() {
        return open != null;
    }

    /** Whether a bot runs, its compile included: what keeps the core up with no client ({@link CoreServer.Hooks#busy}). */
    public boolean busy() {
        OpenProject project = open;
        return project != null && project.running();
    }

    private void received(Channel channel, String text) {
        Request request;
        try {
            request = CoreJson.read(text, Request.class);
        } catch (IllegalArgumentException unreadable) {
            refuseUnreadable(channel, text, unreadable);
            return;
        }
        switch (request) {
            case Request.Open ask -> inTurn(() -> answer(channel, ask.id(), open(channel, ask.project())));
            // A client's tools and its answers to the core's calls need no project, and never wait behind one.
            case Request.Tools registered -> {
                EditResult result;
                try {
                    tools.register(channel, registered);
                    result = new EditResult.Accepted(List.of());
                } catch (IllegalArgumentException e) {
                    result = new EditResult.Refused(e.getMessage());
                }
                answer(channel, registered.id(), result);
            }
            case Request.WatchCalls watch -> {
                // Every MCP call, the kept ones first, whatever the channel has open (the Assistant window's Calls).
                channel.onClose(tools.watch(event -> channel.send(CoreJson.write(new Reply.Told(event)))));
                answer(channel, watch.id(), new EditResult.Accepted(List.of()));
            }
            case Request.Called called -> {
                tools.called(channel, called);
                answer(channel, called.id(), new EditResult.Accepted(List.of()));
            }
            case Request.Close ask -> inTurn(() -> {
                OpenProject project = attached.get(channel);
                if (project != null) project.drain();
                if (project != null && project == open && ask.keepRun() && project.running()) kept = true;
                leave(channel);
                answer(channel, ask.id(), new EditResult.Accepted(List.of()));
            });
            default -> {
                OpenProject project = attached.get(channel);
                if (project == null) {
                    answer(channel, request.id(), new EditResult.Refused("No project is open."));
                } else {
                    project.ask(request).thenAccept(result -> answer(channel, request.id(), result));
                }
            }
        }
    }

    /** A message no request reads as: refused by its id when it has one, so its client does not wait for nothing. */
    private static void refuseUnreadable(Channel channel, String text, IllegalArgumentException why) {
        try {
            JsonNode id = LENIENT.readTree(text).path("id");
            if (id.canConvertToLong()) {
                answer(channel, id.asLong(), new EditResult.Refused("The core could not read that: " + why.getMessage()));
                return;
            }
        } catch (IOException notJson) {
            // Nothing to answer by.
        }
        System.err.println("A client sent what is no request: " + why.getMessage());
    }

    /** Runs {@code work} after every open, leave and close before it; nothing once the core is ending. */
    private void inTurn(Runnable work) {
        try {
            opener.execute(work);
        } catch (RejectedExecutionException ending) {
            // The core is closing: every project goes with it.
        }
    }

    /** {@code work} done in turn, its answer awaited; refused once the core is ending. */
    private <T> T inTurnAwaited(Supplier<T> work) {
        try {
            return CompletableFuture.supplyAsync(work, opener).join();
        } catch (RejectedExecutionException ending) {
            throw new IllegalArgumentException("BotMaker's core is ending.");
        }
    }

    private EditResult open(Channel channel, String directory) {
        Path dir;
        try {
            dir = Path.of(directory).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return new EditResult.Refused(directory + " is not a folder.");
        }
        OpenProject before = open;
        Optional<String> refused = opened(dir);
        if (refused.isPresent()) return new EditResult.Refused(refused.get());
        // A client takes over what the MCP opened, or a run kept past an earlier client: it closes once its clients
        // leave, unless this one keeps a run too.
        held = false;
        kept = false;
        OpenProject was = attached.put(channel, open);
        if (was != null && was != open) closeIfLeft(was, channel);
        open.attach(channel);
        // With a client on it: what the first passes say, that client is who must hear it.
        if (open != before) {
            try {
                prepare(open);
            } catch (RuntimeException | LinkageError failed) {
                // Answered, or the client's open waits forever; half prepared is not open.
                attached.remove(channel);
                closeOpen();
                return new EditResult.Refused(dir.getFileName() + " could not be opened: " + said(failed));
            }
        }
        // A channel that ended while the project opened heard nothing and never will: let it go now.
        if (!channel.isOpen()) leave(channel);
        return new EditResult.Accepted(List.of());
    }

    /**
     * {@code dir} open, or why not: another project open is closed first when nobody is on it and no bot runs. On the
     * opener's thread.
     */
    private Optional<String> opened(Path dir) {
        if (open != null && !open.directory().equals(dir)) {
            if (!open.unattended() || open.running()) {
                return Optional.of(open.directory().getFileName() + " is open in BotMaker's core"
                        + (open.running() ? " and its bot runs" : "") + "; close it before opening "
                        + dir.getFileName() + ".");
            }
            closeOpen();
        }
        if (open != null) return Optional.empty();
        // The home plugins go first: a project binds its own, and none of theirs may write into it.
        if (homeBound) {
            homeBound = false;
            tools.plugins(List.of());
            PluginHost.unbind();
        }
        OpenProject project;
        try {
            project = OpenProject.open(dir, libraries);
        } catch (IOException | RuntimeException | LinkageError e) {
            // A LinkageError too, answered, or the opener waits forever (2026-11-10). The failed open unbound the
            // home plugins: bound again.
            serveHome();
            return Optional.of(said(e));
        }
        open = project;
        held = false;
        kept = false;
        project.watch(event -> heard(project, event));
        return Optional.empty();
    }

    /**
     * A project just opened made whole: a holder a plugin brings and a value the fit moved or reset are said once, the
     * holders first, since a new parameter group is fitted like the others; then its tools are served. A plugin that
     * cannot write its holders leaves the project open without them, as Studio's open did.
     */
    private void prepare(OpenProject project) {
        try {
            project.createMissing();
        } catch (RuntimeException e) {
            System.err.println("[core] A plugin's missing values were not written: " + e);
        }
        project.fitParams();
        tools.own(project.bot());
        servePlugins(project);
    }

    /**
     * Why an open failed, in a sentence. A class that would not load is the core's own jars rebuilt under it (a dev
     * {@code mvn install}) when {@link CoreJars} sees one changed, else a library or plugin that doesn't fit the rest.
     */
    static String said(Throwable failure) {
        String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        if (!(failure instanceof LinkageError)) return message;
        List<String> rebuilt = CoreJars.changed();
        if (!rebuilt.isEmpty()) {
            return "BotMaker's core can't load " + message + ": " + String.join(", ", rebuilt) + " was rebuilt since it "
                    + "started. quit_core (or closing Studio and every assistant using BotMaker) restarts it on them.";
        }
        return "a library or plugin of the bot doesn't fit the others: " + failure;
    }

    /** {@code project}'s bound plugins' tools, served by the core while no Studio serves them (2026-11-10). */
    private void servePlugins(OpenProject project) {
        tools.plugins(PluginTools.headless(JSON, () -> HostServices.forProject(project.config(), UiPort.NONE)));
    }

    private void leave(Channel channel) {
        OpenProject project = attached.remove(channel);
        if (project != null) closeIfLeft(project, channel);
    }

    private void closeIfLeft(OpenProject project, Channel channel) {
        if (!project.detach(channel)) return;
        if (project == open) {
            if (held || kept && project.running()) return;
            closeOpen();
            return;
        }
        closeQuietly(project);
    }

    /** Closes the open project. On the opener's thread, or the core's end. */
    private void closeOpen() {
        OpenProject project = open;
        open = null;
        held = false;
        kept = false;
        tools.own(null);
        tools.plugins(List.of());
        if (project != null) closeQuietly(project);
        serveHome();
    }

    /**
     * With no project open, the catalog's admin and trusted plugins bound and their tools served (2026-11-10): resolved
     * once on a thread of its own, since it may download them, and again while that found none (offline at start);
     * bound on the opener's, unless a project opened meanwhile. Only once {@link #serveHomePlugins} asked. On the
     * opener's thread.
     */
    private void serveHome() {
        if (!servesHome || open != null || homeBound || opener.isShutdown()) return;
        if (home == null || home.isDone() && home.join().classpath().isEmpty()) {
            home = CompletableFuture.supplyAsync(HomePlugins::resolve, task -> {
                Thread thread = new Thread(task, "core-home-plugins");
                thread.setDaemon(true);
                thread.start();
            });
        }
        home.thenAccept(found -> inTurn(() -> {
            if (open != null || homeBound) return;
            try {
                HomePlugins.bind(found);
                homeBound = true;
                homeSaid = found.said().isEmpty() ? List.of("none.") : found.said();
            } catch (RuntimeException | LinkageError e) {
                homeSaid = List.of("The catalog's plugins could not be bound: " + said(e));
                return;
            }
            tools.plugins(PluginTools.headless(JSON, HomeServices::new));
        }));
    }

    private static void closeQuietly(OpenProject project) {
        try {
            project.close();
        } catch (IOException e) {
            System.err.println("Couldn't let " + project.directory().getFileName() + " go: " + e.getMessage());
        }
    }

    /** What {@code project} tells, for the core's run log; a kept run's end closes a project nobody is on. */
    private void heard(OpenProject project, CoreEvent event) {
        switch (event) {
            case CoreEvent.RunState state -> {
                switch (state.phase()) {
                    case COMPILING -> log.cleared();
                    case RUNNING -> log.started();
                    case ENDED -> {
                        log.stopped();
                        inTurn(() -> {
                            if (project == open && kept && !held && project.unattended() && !project.running()) {
                                closeOpen();
                            }
                        });
                    }
                    case PAUSED -> {
                    }
                }
            }
            // A client changed the libraries: the plugins were bound again, their tools with them.
            // On the opener's thread, as closeOpen: a close in between serves no closed project's tools.
            case CoreEvent.LibrariesResolved ignored -> inTurn(() -> {
                if (project == open) servePlugins(project);
            });
            case CoreEvent.RunOutput output -> log.output(output.text());
            case CoreEvent.RunTraced traced -> log.trace(traced.traceLine());
            case CoreEvent.DebugSession session -> {
                switch (session.phase()) {
                    case STARTED -> log.debugging(true);
                    case FINISHED -> log.debugging(false);
                    case RESUMED -> {
                    }
                }
            }
            default -> {
            }
        }
    }

    // ---- the core's own home and run tools (CoreHome.Host) ----

    @Override
    public String open(Path dir) {
        Path wanted = dir.toAbsolutePath().normalize();
        Optional<String> refused = inTurnAwaited(() -> {
            OpenProject before = open;
            Optional<String> why = opened(wanted);
            if (why.isEmpty() && open != before) {
                held = true;
                try {
                    prepare(open);
                } catch (RuntimeException | LinkageError failed) {
                    // Half prepared is not open: no file tools would act on it.
                    closeOpen();
                    return Optional.of(wanted.getFileName() + " could not be opened: " + said(failed));
                }
            }
            return why;
        });
        if (refused.isPresent()) throw new IllegalArgumentException(refused.get());
        return wanted.getFileName() + " is open in BotMaker's core: the file and run tools act on it.";
    }

    @Override
    public Optional<Path> projectDir() {
        OpenProject project = open;
        return project == null ? Optional.empty() : Optional.of(project.directory());
    }

    @Override
    public String runBot() {
        return started("the bot", Request.Run::new);
    }

    @Override
    public String runEntry(RunEntries.Declared entry) {
        return started(entry.title(), id -> new Request.RunEntry(id, entry.title(), entry.mainClass()));
    }

    @Override
    public String stop() {
        if (!log.busy()) return "Nothing runs.";
        boolean debugging = log.debugging();
        asked(debugging ? id -> new Request.Debugging(id, Request.Debugging.Step.STOP) : Request.Stop::new);
        return "Stopping. run_state says when it has.";
    }

    private String started(String what, LongFunction<Request> request) {
        log.requested(what);
        try {
            asked(request);
        } catch (RuntimeException refused) {
            // A refused run names no later one.
            log.requested(null);
            throw refused;
        }
        return "Starting " + what + ". run_state says when it runs; read_trace what it does.";
    }

    /** {@code request} done on the open project; refused, saying why, when it is refused or none is open. */
    private void asked(LongFunction<Request> request) {
        OpenProject project = open;
        if (project == null) throw new IllegalArgumentException("No project is open: open_project opens one.");
        if (project.ask(request.apply(asks.incrementAndGet())).join() instanceof EditResult.Refused refused) {
            throw new IllegalArgumentException(refused.reason());
        }
    }

    /** The server that serves these projects, which {@code core_status} and {@code quit_core} ask; set once it runs. */
    void servedBy(CoreServer server) {
        this.server = server;
    }

    /**
     * From now on, with no project open, the catalog's plugins are bound and their tools served (2026-11-10): the
     * core's own process only, which {@link CoreMain} says; Studio's in-process core has Studio's plugins.
     */
    void serveHomePlugins() {
        inTurn(() -> {
            servesHome = true;
            serveHome();
        });
    }

    @Override
    public String coreStatus() {
        CoreServer serving = server;
        List<String> lines = new ArrayList<>();
        if (serving != null) {
            CoreServer.Info info = serving.info();
            lines.add("BotMaker's core " + info.version() + ", pid " + info.pid() + (info.ending() ? ", ending" : "")
                    + ".");
            lines.add(info.clients() == 0 ? "No Studio is attached." : "Studio is attached (" + info.clients()
                    + " window" + (info.clients() == 1 ? "" : "s") + ").");
        }
        OpenProject project = open;
        lines.add(project == null ? "No project is open." : project.directory().getFileName() + " is open"
                + (busy() ? ", and its bot runs." : "."));
        if (project == null && servesHome) {
            List<String> said = homeSaid;
            lines.add(said.isEmpty() ? "It is still getting the catalog's plugins."
                    : "With no project open, the catalog's plugins:\n  " + String.join("\n  ", said));
        }
        List<String> rebuilt = CoreJars.changed();
        lines.add(rebuilt.isEmpty() ? "Its jars are the ones it started on."
                : "Rebuilt since it started, so it may fail to load their classes: " + String.join(", ", rebuilt)
                + ". quit_core, and the next call starts a fresh core on them.");
        return String.join("\n", lines);
    }

    @Override
    public String quitCore(boolean force) {
        CoreServer serving = server;
        if (serving == null) throw new IllegalArgumentException("This core runs inside Studio: quit Studio instead.");
        return serving.quit(force);
    }

    private static void answer(Channel channel, long id, EditResult result) {
        channel.send(CoreJson.write(new Reply.Answer(id, result)));
    }

    /** Closes the open project, whoever is attached; the core is ending. */
    @Override
    public void close() {
        opener.shutdownNow();
        attached.clear();
        closeOpen();
        // The home plugins' jars stay open while bound, which on Windows keeps them from being replaced.
        if (homeBound) {
            homeBound = false;
            PluginHost.unbind();
        }
        log.close();
    }
}
