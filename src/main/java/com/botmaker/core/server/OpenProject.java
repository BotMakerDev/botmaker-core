package com.botmaker.core.server;

import com.botmaker.core.CoreJson;
import com.botmaker.core.edit.CoreEditor;
import com.botmaker.core.edit.EditResult;
import com.botmaker.core.edit.HistoryStep;
import com.botmaker.core.event.CoreEvent;
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
import com.botmaker.core.runtime.JavacRelease;
import com.botmaker.core.services.MavenService;
import com.botmaker.java.edit.palette.BlockType;
import com.botmaker.java.types.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A project open in the core's process, with the clients attached to it: its state, its editor and its
 * {@link ProjectLock}. Every ask runs on the project's one thread, the one that owns its {@link ProjectState}, in the
 * order the asks arrived, so two clients editing one method are served one after the other: the second edit lands on
 * what the first left, or is refused saying why.
 *
 * <p>Each accepted change of the open file is written to disk as it lands, so a core that is killed loses nothing.
 * The open-time writes Studio makes (the contract's dependency, a plugin's editor libraries, migrations) stay Studio's
 * until the plan's phase 10a1.
 */
public final class OpenProject implements AutoCloseable {

    /** What a project's libraries are and what is bound from them: Maven's and the plugin host's, or a test's. */
    public interface Libraries {
        List<String> classpath(ProjectConfig config) throws IOException;

        /** The analyzer the editor resolves types with, over the project's {@code state}. */
        ProjectAnalyzer analyzer(List<String> classpath, ProjectState state);

        /** Binds the plugins the project's own jars declare, as Studio binds them on its open. */
        void bind(ProjectConfig config, List<String> classpath);

        /** Lets the plugins go when the project closes. */
        void unbind();

        /** The project's pom, resolved by Maven; the plugins bound by the plugin host, in this process. */
        Libraries MAVEN = new Libraries() {
            @Override
            public List<String> classpath(ProjectConfig config) throws IOException {
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
    private final CoreEditor editor;

    private OpenProject(ProjectConfig config, ProjectState state, ProjectLock lock, Libraries libraries,
                        ProjectAnalyzer analyzer) {
        this.config = config;
        this.state = state;
        this.lock = lock;
        this.libraries = libraries;
        this.owner = Executors.newSingleThreadExecutor(work -> {
            Thread thread = new Thread(work, "core-project-" + config.projectName());
            thread.setDaemon(true);
            return thread;
        });
        this.editor = new CoreEditor(config, state, new Said(), analyzer, this::changed);
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
            ProjectState state = new ProjectState();
            state.setSourcePath(config.sourceRoot());
            state.setReaderMode(ProjectMode.isReader(config.projectPath()));
            state.setLevel(JavacRelease.level(config.projectPath()));
            StudioProjectSettings settings = StudioProjectSettings.read(config.studioRoot());
            state.setSettings(settings);
            state.setTemplate(settings.template() != null ? settings.template()
                    : ProjectRepair.looksLikeGameBot(config) ? ProjectTemplate.GAME_BOT : ProjectTemplate.EMPTY);
            readSources(config.sourceRoot()).forEach(state::addFile);
            List<String> classpath = libraries.classpath(config);
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
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return answer(request);
                } catch (RuntimeException e) {
                    return new EditResult.Refused("The core could not do that: " + e);
                }
            }, owner);
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

    private EditResult answer(Request request) {
        return switch (request) {
            case Request.Show show -> show(show.file());
            case Request.Apply apply -> editor.apply(apply.request());
            case Request.UserEdit edit -> editor.userEdit(edit.edit());
            case Request.Step step -> editor.step(step.step()) ? new EditResult.Accepted(List.of())
                    : new EditResult.Refused(step.step() == HistoryStep.UNDO ? "There is nothing to undo."
                    : "There is nothing to redo.");
            case Request.Commit commit -> editor.commit(commit.file(), commit.original(), commit.source(),
                    commit.label());
            case Request.Open open -> new EditResult.Refused("The project is open already.");
            case Request.Close close -> new EditResult.Refused("A close is the core's to handle.");
        };
    }

    private EditResult show(String file) {
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
        Optional<ProjectFile> known = state.getFile(path);
        if (known.isEmpty()) {
            try {
                state.addFile(new ProjectFile(path, Files.readString(path)));
            } catch (IOException e) {
                return new EditResult.Refused("Couldn't read " + file + ": " + e.getMessage());
            }
        }
        state.setActiveFile(path);
        state.setCompilationUnit(parse(path, state.getCurrentCode()));
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

    /**
     * Ends the project's thread once the asks already sent are done, so none runs after the plugins and the lock went,
     * then lets both go.
     */
    @Override
    public void close() throws IOException {
        owner.shutdown();
        try {
            if (!owner.awaitTermination(CLOSE_WAIT.toSeconds(), TimeUnit.SECONDS)) owner.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            libraries.unbind();
        } finally {
            lock.close();
        }
    }

    private static List<ProjectFile> readSources(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> files = paths.filter(p -> p.toString().endsWith(".java")).filter(Files::isRegularFile)
                    .map(p -> p.toAbsolutePath().normalize()).toList();
            List<ProjectFile> read = new ArrayList<>();
            for (Path file : files) read.add(new ProjectFile(file, Files.readString(file)));
            return read;
        }
    }
}
