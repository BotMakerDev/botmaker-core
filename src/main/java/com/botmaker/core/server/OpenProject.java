package com.botmaker.core.server;

import com.botmaker.core.CoreJson;
import com.botmaker.core.edit.CoreEditor;
import com.botmaker.core.edit.EditResult;
import com.botmaker.core.edit.HistoryStep;
import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.events.EventBus;
import com.botmaker.core.events.FileEdit;
import com.botmaker.core.index.TypeSummaryManager;
import com.botmaker.core.parser.EditorOutput;
import com.botmaker.core.plugin.HostServices;
import com.botmaker.core.plugin.PluginHost;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectFile;
import com.botmaker.core.project.ProjectMode;
import com.botmaker.core.project.ProjectRepair;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.project.ProjectTemplate;
import com.botmaker.core.project.StudioProjectSettings;
import com.botmaker.core.project.vcs.ProjectVcs;
import com.botmaker.core.run.CodeExecutionService;
import com.botmaker.core.run.DebuggingService;
import com.botmaker.core.run.RunCheck;
import com.botmaker.core.run.RunTelemetry;
import com.botmaker.core.run.debug.DebugSnapshot;
import com.botmaker.core.run.debug.DebugView;
import com.botmaker.core.run.debug.FollowPacer;
import com.botmaker.core.run.trial.TrialCaller;
import com.botmaker.core.runtime.JavacRelease;
import com.botmaker.core.services.ContractDependency;
import com.botmaker.core.services.EditorCompanions;
import com.botmaker.core.services.MavenService;
import com.botmaker.core.services.ProgressReporter;
import com.botmaker.java.edit.palette.BlockType;
import com.botmaker.java.types.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * A project open in the core's process, with the clients attached to it: its state, its editor and its
 * {@link ProjectLock}. Every ask runs on the project's one thread, the one that owns its {@link ProjectState}, in the
 * order the asks arrived, so two clients editing one method are served one after the other: the second edit lands on
 * what the first left, or is refused saying why.
 *
 * <p>Each accepted change of the open file is written to disk as it lands, so a core that is killed loses nothing, and
 * the sources are read from disk before each ask: disk is where every client's change is. The open-time writes
 * (the remotes' names, the contract's dependency, a plugin's editor libraries) are the core's since phase 10a1.
 */
public final class OpenProject implements AutoCloseable {

    /** What a project's libraries are and what is bound from them: Maven's and the plugin host's, or a test's. */
    public interface Libraries {
        /**
         * The project's libraries, resolved after the open-time writes its pom may need; {@code state} holds its
         * sources.
         */
        List<String> classpath(ProjectConfig config, ProjectState state) throws IOException;

        /** The analyzer the editor resolves types with, over the project's {@code state}. */
        ProjectAnalyzer analyzer(List<String> classpath, ProjectState state);

        /** Binds the plugins the project's own jars declare, as Studio binds them on its open. */
        void bind(ProjectConfig config, List<String> classpath);

        /** Lets the plugins go when the project closes. */
        void unbind();

        /** Brings {@code analyzer}'s type index up to the libraries' new {@code classpath}. */
        void reindex(ProjectAnalyzer analyzer, List<String> classpath);

        /**
         * The project's pom, resolved by Maven, after the writes an open makes to it: the contract declared for a bot
         * whose source imports it, and a declared plugin's editor libraries the pom lost put back (never in a bot
         * opened read-only). The plugins are bound by the plugin host, in this process.
         */
        Libraries MAVEN = new Libraries() {
            @Override
            public List<String> classpath(ProjectConfig config, ProjectState state) throws IOException {
                List<String> classpath = resolve(config);
                try {
                    if (ContractDependency.ensureFor(config, state, classpath)) classpath = resolve(config);
                } catch (IOException | RuntimeException e) {
                    System.err.println("Warning: Could not declare the plugin contract: " + e.getMessage());
                }
                try {
                    if (!state.isReaderMode()
                            && EditorCompanions.restore(config.projectPath(), classpath, ProgressReporter.NONE)) {
                        classpath = resolve(config);
                    }
                } catch (IOException | RuntimeException e) {
                    System.err.println("Warning: Could not restore a plugin's editor libraries: " + e.getMessage());
                }
                return classpath;
            }

            private static List<String> resolve(ProjectConfig config) throws IOException {
                try {
                    return MavenService.resolveClasspath(config.projectPath());
                } catch (RuntimeException e) {
                    throw new IOException("Couldn't resolve the bot's libraries: " + e.getMessage(), e);
                }
            }

            @Override
            public ProjectAnalyzer analyzer(List<String> classpath, ProjectState state) {
                return new ProjectAnalyzer(TypeSummaryManager.buildOrLoad(classpath), state);
            }

            @Override
            public void bind(ProjectConfig config, List<String> classpath) {
                PluginHost.bind(classpath, HostServices.forProject(config),
                        StudioProjectSettings.devModeIn(config.projectPath()));
            }

            @Override
            public void unbind() {
                PluginHost.unbind();
            }

            @Override
            public void reindex(ProjectAnalyzer analyzer, List<String> classpath) {
                if (analyzer.libraryIndex() instanceof TypeSummaryManager index) index.refresh(classpath);
            }
        };
    }

    /** How long a close waits for the asks already sent. */
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(30);

    private final ProjectConfig config;
    private final ProjectState state;
    private final ProjectLock lock;
    private final Libraries libraries;
    private final ExecutorService owner;
    private final Set<Channel> clients = new CopyOnWriteArraySet<>();
    /** Where what steers a run is done: never behind an ask the owner is busy with ({@link Request.Control}). */
    private final ExecutorService control;
    /** How many stops have arrived, counted as they arrive: see {@link #ask}. */
    private final AtomicLong stops = new AtomicLong();
    private final ProjectAnalyzer analyzer;
    private final CoreEditor editor;
    private final CodeExecutionService execution;
    private final DebuggingService debugger;

    private OpenProject(ProjectConfig config, ProjectState state, ProjectLock lock, Libraries libraries,
                        ProjectAnalyzer analyzer) {
        this.config = config;
        this.state = state;
        this.lock = lock;
        this.libraries = libraries;
        this.analyzer = analyzer;
        this.owner = Executors.newSingleThreadExecutor(work -> {
            Thread thread = new Thread(work, "core-project-" + config.projectName());
            thread.setDaemon(true);
            return thread;
        });
        this.control = Executors.newSingleThreadExecutor(work -> {
            Thread thread = new Thread(work, "core-control-" + config.projectName());
            thread.setDaemon(true);
            return thread;
        });
        this.editor = new CoreEditor(config, state, new Said(), analyzer, this::changed);
        // A run's check is a client's until the core judges a tree by itself: javac judges what reaches it here.
        RunTelemetry.Listener telemetry = RunTelemetry.told(this::tell);
        this.execution = new CodeExecutionService(config, state, RunCheck.NONE, this::tell, telemetry,
                this::sourcesWritten);
        this.debugger = new DebuggingService(state, config, execution, EventBus.UiThread.INLINE, new ToldDebug(),
                this::tell, telemetry);
    }

    /**
     * Opens the project in {@code dir}: takes its lock, reads its sources and settings, resolves its libraries and binds
     * its plugins. No file is open until a client shows one.
     *
     * @throws ProjectLock.Held when another core holds the project
     */
    public static OpenProject open(Path dir, Libraries libraries) throws IOException {
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        ProjectLock lock = ProjectLock.take(config.projectPath());
        boolean bound = false;
        try {
            // The remotes' git names first: what reads origin and upstream reads them so (2026-10-25).
            try {
                new ProjectVcs(config.projectPath()).adoptRemoteNames();
            } catch (IOException e) {
                System.err.println("Warning: " + e.getMessage());
            }
            ProjectState state = new ProjectState();
            state.setSavedByCore(true);
            state.setSourcePath(config.sourceRoot());
            state.setReaderMode(ProjectMode.isReader(config.projectPath()));
            state.setLevel(JavacRelease.level(config.projectPath()));
            StudioProjectSettings settings = StudioProjectSettings.read(config.studioRoot());
            state.setSettings(settings);
            state.setTemplate(settings.template() != null ? settings.template()
                    : ProjectRepair.looksLikeGameBot(config) ? ProjectTemplate.GAME_BOT : ProjectTemplate.EMPTY);
            readSources(config.sourceRoot()).forEach(state::addFile);
            List<String> classpath;
            try {
                classpath = libraries.classpath(config, state);
            } catch (IOException e) {
                // A pom that does not resolve still opens, so the user can repair it; Studio says what failed.
                System.err.println("Warning: " + e.getMessage());
                classpath = List.of();
            }
            state.setResolvedClasspath(classpath);
            libraries.bind(config, classpath);
            bound = true;
            return new OpenProject(config, state, lock, libraries, libraries.analyzer(classpath, state));
        } catch (IOException | RuntimeException e) {
            try {
                if (bound) libraries.unbind();
            } finally {
                lock.close();
            }
            throw e;
        }
    }

    public Path directory() {
        return config.projectPath();
    }

    /** Attaches {@code channel}: it hears every change from now on. */
    /** On open, {@code params/} is fitted to {@code starting-params/}: a fresh copy gets its starting values (2026-11-04). */
    void fitParams() {
        execution.fitParams();
    }

    void attach(Channel channel) {
        clients.add(channel);
    }

    /** Detaches {@code channel}; true when no client is left. */
    boolean detach(Channel channel) {
        clients.remove(channel);
        return clients.isEmpty();
    }

    /** {@code request} done on the project's thread, after every ask that came before it; refused once it closed. */
    CompletableFuture<EditResult> ask(Request request) {
        long stopsBefore = stops.get();
        if (request instanceof Request.Stop
                || request instanceof Request.Debugging debugging && debugging.step() == Request.Debugging.Step.STOP) {
            stops.incrementAndGet();
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    if (request instanceof Request.Control steer) return steer(steer);
                    // A stop goes straight past what still waits its turn here, so a run it overtook does not start.
                    return startsABot(request) && stops.get() != stopsBefore ? stoppedBeforeItStarted(request)
                            : answer(request);
                } catch (RuntimeException e) {
                    return new EditResult.Refused("The core could not do that: " + e);
                }
            }, request instanceof Request.Control ? control : owner);
        } catch (RejectedExecutionException closed) {
            return CompletableFuture.completedFuture(new EditResult.Refused("The project was closed."));
        }
    }

    /** Waits until every ask that came before this has been done. */
    void drain() {
        try {
            CompletableFuture.runAsync(() -> { }, owner).join();
        } catch (RejectedExecutionException | CompletionException closed) {
            // Closed already: nothing is left to do.
        }
    }

    /**
     * The sources and settings as disk has them, since something other than this core may have written them (a client's
     * new, renamed or restored class, a plugin, a settings window): an edit landing on an older text would overwrite that
     * write, and a call migration would miss a new class's callers. Every client hears of a new text of the open file.
     */
    private void readDiskAgain() {
        try {
            state.setSettings(StudioProjectSettings.read(config.studioRoot()));
        } catch (RuntimeException e) {
            // Settings that cannot be read: the last ones stand.
        }
        List<ProjectFile> onDisk;
        try {
            onDisk = readSources(config.sourceRoot());
        } catch (IOException e) {
            return; // What was last read stands, and the edit's save says what is wrong.
        }
        ProjectFile active = state.getActiveFile();
        Set<Path> seen = new HashSet<>();
        List<String> others = new ArrayList<>();
        for (ProjectFile read : onDisk) {
            seen.add(read.getPath());
            Optional<ProjectFile> known = state.getFile(read.getPath());
            if (known.isEmpty()) {
                state.addFile(read);
                others.add(named(read.getPath()));
            } else if (!read.getContent().equals(known.get().getContent())) {
                known.get().setContent(read.getContent());
                known.get().setAst(null);
                if (known.get() == active) openFileRead();
                else others.add(named(read.getPath()));
            }
        }
        for (ProjectFile file : List.copyOf(state.getAllFiles())) {
            if (file != active && !seen.contains(file.getPath())) {
                state.removeFile(file.getPath());
                others.add(named(file.getPath()));
            }
        }
        if (!others.isEmpty()) tell(new CoreEvent.SourcesChanged(others));
    }

    /** {@code file} as the protocol names it: its path below the source root, with forward slashes. */
    private String named(Path file) {
        return config.sourceRoot().relativize(file).toString().replace(File.separatorChar, '/');
    }

    /** A fit or a run wrote to disk: read back in turn, and every client told. Nothing once the project closed. */
    private void sourcesWritten() {
        try {
            owner.execute(() -> {
                try {
                    readDiskAgain();
                } catch (RuntimeException e) {
                    System.err.println("Couldn't read the bot's sources again: " + e);
                }
            });
        } catch (RejectedExecutionException closed) {
            // Closing: nobody is left to tell, and the next open reads the disk.
        }
    }

    /** The open file's text came from disk: parsed for the next edit, and told, since no client's mirror has it for sure. */
    private void openFileRead() {
        ProjectFile active = state.getActiveFile();
        state.setCompilationUnit(parse(active.getPath(), active.getContent()));
        editor.openFile().ifPresent(name ->
                tell(new CoreEvent.MethodsChanged(name, List.of(), List.of(), active.getContent(), false)));
    }

    private EditResult answer(Request request) {
        readDiskAgain();
        return switch (request) {
            case Request.Show show -> show(show.file());
            case Request.Reload reload -> reload();
            case Request.Apply apply -> editor.apply(apply.request());
            case Request.UserEdit edit -> editor.userEdit(edit.edit());
            case Request.Step step -> editor.step(step.step()) ? new EditResult.Accepted(List.of())
                    : new EditResult.Refused(step.step() == HistoryStep.UNDO ? "There is nothing to undo."
                    : "There is nothing to redo.");
            case Request.Commit commit -> editor.commit(commit.file(), commit.original(), commit.source(),
                    commit.label());
            case Request.Rewritten rewritten -> rewritten(rewritten);
            case Request.Recorded recorded -> recorded(recorded);
            case Request.Compile compile -> started(() -> execution.compileCode(state.snapshot()));
            case Request.Run run -> started(() -> execution.runCode(state.snapshot()));
            case Request.RunEntry entry ->
                    started(() -> execution.runEntry(state.snapshot(), entry.title(), entry.mainClass()));
            case Request.Try attempt -> started(() -> execution.tryCode(state.snapshot(),
                    new TrialCaller.Source(attempt.className(), attempt.text()), attempt.label()));
            case Request.Debug debug -> started(() -> debugger.startDebugging(debug.follow(), breakpoints(debug)));
            case Request.Control steer -> steer(steer);
            case Request.Open open -> new EditResult.Refused("The project is open already.");
            case Request.Close close -> new EditResult.Refused("A close is the core's to handle.");
        };
    }

    private static boolean startsABot(Request request) {
        return request instanceof Request.Run || request instanceof Request.RunEntry || request instanceof Request.Try
                || request instanceof Request.Debug;
    }

    /** A client that asked for a run waits for its end; a debug session it asked for never began, so nothing ends. */
    private EditResult stoppedBeforeItStarted(Request request) {
        if (!(request instanceof Request.Debug)) {
            tell(new CoreEvent.RunState(CoreEvent.RunState.Phase.ENDED, "stopped before it started"));
        }
        return new EditResult.Accepted(List.of());
    }

    /** {@code work} started, how it goes told as events. */
    private static EditResult started(Runnable work) {
        work.run();
        return new EditResult.Accepted(List.of());
    }

    /** What steers a run, on {@link #control}: no source is read for it. */
    private EditResult steer(Request.Control request) {
        switch (request) {
            case Request.Stop stop -> execution.stopRunningProgram();
            case Request.Answer answer -> {
                if (!execution.answer(answer.asked(), answer.value())
                        && !debugger.answer(answer.asked(), answer.value())) {
                    return new EditResult.Refused("No bot is waiting for that answer.");
                }
            }
            case Request.Debugging debugging -> {
                switch (debugging.step()) {
                    case STEP_OVER -> debugger.stepOver();
                    case CONTINUE -> debugger.continueExecution();
                    case STOP -> debugger.stopDebugging();
                }
            }
        }
        return new EditResult.Accepted(List.of());
    }

    /** A debug request's marks by the paths the sources are held under. */
    private Map<Path, Set<String>> breakpoints(Request.Debug debug) {
        Map<Path, Set<String>> marks = new HashMap<>();
        debug.breakpoints().forEach((file, nodes) ->
                marks.put(config.sourceRoot().resolve(file).toAbsolutePath().normalize(), nodes));
        return marks;
    }

    /**
     * Opens {@code file}. A refusal leaves no file open, so an edit meant for the file a client went to is refused
     * rather than landing on the one it left.
     */
    private EditResult show(String file) {
        state.clearActiveFile();
        Path path = config.sourceRoot().resolve(file).toAbsolutePath().normalize();
        // The real paths, so a link inside the sources cannot open, and later save over, a file outside them.
        try {
            if (!path.toString().endsWith(".java") || !Files.isRegularFile(path)
                    || !path.toRealPath().startsWith(config.sourceRoot().toRealPath())) {
                return new EditResult.Refused(file + " is not in the bot's sources.");
            }
        } catch (IOException e) {
            return new EditResult.Refused(file + " is not in the bot's sources.");
        }
        String text;
        try {
            text = Files.readString(path);
        } catch (IOException e) {
            return new EditResult.Refused("Couldn't read " + file + ": " + e.getMessage());
        }
        Optional<ProjectFile> known = state.getFile(path);
        if (known.isPresent()) known.get().setContent(text);
        else state.addFile(new ProjectFile(path, text));
        state.setActiveFile(path);
        openFileRead();
        return new EditResult.Accepted(List.of());
    }

    /**
     * The project's libraries again, after a client changed them: resolved, the plugins bound again and the type index
     * brought up to date. A pom that does not resolve leaves the libraries there were.
     */
    private EditResult reload() {
        List<String> classpath;
        try {
            classpath = libraries.classpath(config, state);
        } catch (IOException e) {
            return new EditResult.Refused(e.getMessage());
        }
        state.setLevel(JavacRelease.level(config.projectPath()));
        state.setResolvedClasspath(classpath);
        libraries.bind(config, classpath);
        libraries.reindex(analyzer, classpath);
        ProjectFile active = state.getActiveFile();
        if (active != null) state.setCompilationUnit(parse(active.getPath(), active.getContent()));
        return new EditResult.Accepted(List.of());
    }

    /** A client's write of the open file outside an edit (an open-time migration), landed as one step. */
    private EditResult rewritten(Request.Rewritten rewritten) {
        if (editor.openFile().filter(rewritten.file()::equals).isEmpty()) {
            return new EditResult.Refused(rewritten.file() + " is not the file open here.");
        }
        // Disk may hold the rewrite already, read before this ask.
        String current = state.getCurrentCode();
        if (!rewritten.original().equals(current) && !rewritten.source().equals(current)) {
            return new EditResult.Refused("The file changed meanwhile.");
        }
        editor.rewritten(new EditorOutput.Write(rewritten.source(), rewritten.original(), false, null, List.of()));
        return new EditResult.Accepted(List.of());
    }

    /**
     * What a client's plugin already wrote, buffer and disk: its texts taken here, so the next edit lands on them, and
     * the writes recorded as one step. A file outside the bot's sources is only recorded.
     */
    private EditResult recorded(Request.Recorded recorded) {
        Path root = config.sourceRoot().toAbsolutePath().normalize();
        for (FileEdit each : recorded.edits()) {
            Path path = each.path().toAbsolutePath().normalize();
            if (!path.startsWith(root) || each.newContent() == null) continue;
            Optional<ProjectFile> known = state.getFile(path);
            if (known.isPresent()) known.get().setContent(each.newContent());
            else state.addFile(new ProjectFile(path, each.newContent()));
            ProjectFile active = state.getActiveFile();
            if (active != null && active.getPath().equals(path)) {
                state.setCurrentCode(each.newContent());
                state.setCompilationUnit(parse(path, each.newContent()));
            }
        }
        editor.recordRewrite(recorded.label(), recorded.edits());
        return new EditResult.Accepted(List.of());
    }

    /**
     * What the editor announced, told to every client; a change of the open file is also parsed, so the next edit reads
     * the tree it left, and written to disk.
     */
    private void changed(CoreEvent event) {
        if (event instanceof CoreEvent.MethodsChanged change) {
            ProjectFile active = state.getActiveFile();
            if (active != null) {
                state.setCompilationUnit(parse(active.getPath(), change.source()));
                try {
                    Files.writeString(active.getPath(), change.source());
                } catch (IOException e) {
                    tell(new CoreEvent.Status("Couldn't save " + change.file() + ": " + e.getMessage()));
                }
            }
        }
        tell(event);
    }

    private CompilationUnit parse(Path file, String source) {
        return ProjectAnalyzer.createCompilationUnit(state.level(), state.getResolvedClasspath(), source,
                config.sourceRoot(), file.toString());
    }

    void tell(CoreEvent event) {
        String told = CoreJson.write(new Reply.Told(event));
        clients.forEach(channel -> channel.send(told));
    }

    /** The editor's own words go to every client as a status line; its writes are told as {@code MethodsChanged}. */
    private final class Said implements EditorOutput {
        @Override
        public void status(String message) {
            tell(new CoreEvent.Status(message));
        }

        @Override
        public void written(Write write) {
            // Announced by the editor as MethodsChanged, which changed() saves.
        }

        @Override
        public void blockAdded(BlockType type) {
            // The palette's recent blocks are a client's to keep.
        }
    }

    /** A debug session shown by telling every client where it stopped; each finds the block in what it draws. */
    private final class ToldDebug implements DebugView {
        @Override
        public void started() {
            tell(new CoreEvent.DebugSession(CoreEvent.DebugSession.Phase.STARTED));
        }

        @Override
        public void resumed() {
            tell(new CoreEvent.DebugSession(CoreEvent.DebugSession.Phase.RESUMED));
        }

        @Override
        public void finished() {
            tell(new CoreEvent.DebugSession(CoreEvent.DebugSession.Phase.FINISHED));
        }

        @Override
        public void paused(FollowPacer.Hit hit, int line, DebugSnapshot snapshot, BooleanSupplier live) {
            tell(new CoreEvent.DebugPaused(hit == null ? "" : named(hit.file()), line, snapshot));
        }

        @Override
        public void followed(FollowPacer.Frame frame, BooleanSupplier live) {
            tell(new CoreEvent.DebugFollowed(place(frame.latest()), frame.hits().stream().map(this::place).toList(),
                    frame.looping()));
        }

        private CoreEvent.DebugFollowed.Place place(FollowPacer.Hit hit) {
            return new CoreEvent.DebugFollowed.Place(named(hit.file()), hit.line());
        }
    }

    /**
     * Ends the project's thread once the asks already sent are done, so none runs after the plugins and the lock went,
     * ends the bot, a debuggee first since it may be suspended until its connection goes, then lets the plugins and
     * the lock go.
     */
    @Override
    public void close() throws IOException {
        owner.shutdown();
        try {
            if (!owner.awaitTermination(CLOSE_WAIT.toSeconds(), TimeUnit.SECONDS)) owner.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        control.shutdown();
        try {
            debugger.stopDebugging();
            execution.close();
            libraries.unbind();
        } finally {
            lock.close();
        }
    }

    private static List<ProjectFile> readSources(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        // No link: one inside the sources would read, and a later save overwrite, a file outside them.
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> files = paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .map(p -> p.toAbsolutePath().normalize()).toList();
            List<ProjectFile> read = new ArrayList<>();
            for (Path file : files) read.add(new ProjectFile(file, Files.readString(file)));
            return read;
        }
    }
}
