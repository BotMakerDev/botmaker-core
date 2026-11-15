package com.botmaker.core.run;

import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.runtime.JavacRelease;
import com.botmaker.core.services.MavenService;
import com.botmaker.core.services.ProgressReporter;
import com.botmaker.core.sharing.UnpackedBots;
import com.botmaker.core.util.ClassPathManager;
import com.botmaker.java.grammar.ParamsFitter;
import com.botmaker.plugin.api.run.SideRun;
import com.botmaker.plugin.api.run.SideRuns;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The host's {@link SideRuns}: a bot that is not the open project's, compiled into its own {@code target/classes} when
 * its sources changed since the last start, then started as a run is ({@code CodeExecutionService}): the run agent,
 * so its log and report land in its own folder, and each run property as one {@code -D}. Its output goes to
 * {@link #OUTPUT} beside its classes, not to a client: no Run tab shows a bot that is not open.
 *
 * <p>The sources compiled are the runner's: each private parameter file, else its starting copy when the runner has
 * none yet. Every process started is killed when this JVM ends.
 */
public final class SideRunner implements SideRuns {

    /** Where a side run's standard output and error go, below the bot's {@code target/}. */
    public static final String OUTPUT = "botmaker-side-run.log";
    /** What the last compile was of, below the bot's {@code target/}: a start with the same sources compiles nothing. */
    static final String STAMP = "botmaker-side-run.stamp";
    private static final Duration STOP_GRACE = Duration.ofSeconds(5);
    /** Where a fit copies a parameter file before it changes it, as the open bot's run does. */
    private static final String VALUES_BACKUPS = ".botmaker/values-removed";

    private static final Set<Process> LIVE = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> LIVE.forEach(Process::destroyForcibly),
                "side-run-reaper"));
    }

    @Override
    public SideRun start(Path bot, Map<String, String> properties, Map<String, String> environment)
            throws IOException {
        if (bot == null) throw new IOException("No bot was named to run.");
        Path dir = bot.toAbsolutePath().normalize();
        if (!Files.isRegularFile(dir.resolve("pom.xml")) && UnpackedBots.at(dir).isEmpty()) {
            throw new IOException("There is no bot in " + ProjectConfig.displayDirectory(dir) + ".");
        }
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        MavenService.Resolution resolved = MavenService.resolve(dir, ProgressReporter.NONE);
        if (!resolved.problems().isEmpty()) {
            throw new IOException("Its libraries did not resolve: " + resolved.problems().getFirst());
        }
        List<String> classpath = classpath(config, resolved.jars());
        try {
            compile(config, classpath);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Stopped while it compiled.");
        }
        List<String> command = new ArrayList<>(List.of(config.javaExecutable()));
        command.addAll(BotJvm.options(properties));
        command.addAll(BotJvm.runAgent(config, null));
        command.addAll(List.of("-cp", String.join(File.pathSeparator, classpath), config.entryClassName()));
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                // appended: the next account's run must not wipe the output of the one that failed
                .redirectOutput(ProcessBuilder.Redirect.appendTo(config.compiledOutputPath().resolveSibling(OUTPUT)
                        .toFile()));
        if (environment != null) environment.forEach((name, value) -> {
            if (name != null && !name.isBlank() && value != null) builder.environment().put(name, value);
        });
        Process process = builder.start();
        LIVE.add(process);
        process.onExit().thenRun(() -> LIVE.remove(process));
        return new Running(process);
    }

    /** The compiled classes, the resources, then the bot's jars. */
    private static List<String> classpath(ProjectConfig config, List<String> jars) {
        List<String> out = new ArrayList<>(List.of(config.compiledOutputPath().toString(),
                config.resourcesRoot().toString()));
        out.addAll(jars);
        return out;
    }

    /** Compiles the bot into its classes, unless its sources are those of the last compile. */
    private static void compile(ProjectConfig config, List<String> classpath) throws IOException, InterruptedException {
        fitParams(config);
        List<String> sources = sources(config);
        if (sources.isEmpty()) throw new IOException("The bot has no sources.");
        Path stamp = config.compiledOutputPath().resolveSibling(STAMP);
        String stamped = config.javacExecutable() + ' ' + JavacRelease.args(config.projectPath()) + '\n'
                + stamp(sources, classpath);
        if (Files.isDirectory(config.compiledOutputPath()) && Files.isRegularFile(stamp)
                && Files.readString(stamp).equals(stamped)) {
            return;
        }
        // a class whose source is gone must not stay on the run's classpath
        clear(config.compiledOutputPath());
        Files.createDirectories(config.compiledOutputPath());
        List<String> command = new ArrayList<>(List.of(config.javacExecutable(),
                "-cp", String.join(File.pathSeparator, classpath),
                "-d", config.compiledOutputPath().toString(), "-encoding", "UTF-8", "-nowarn"));
        command.addAll(JavacRelease.args(config.projectPath()));
        command.addAll(sources);
        Process javac = new ProcessBuilder(command).directory(config.projectPath().toFile())
                .redirectErrorStream(true).start();
        String output = new String(javac.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (javac.waitFor() != 0) {
            List<String> errors = TreeCompile.errorLines(output, config.projectPath());
            throw new IOException("The bot does not compile: "
                    + (errors.isEmpty() ? output.strip() : errors.getFirst()));
        }
        Files.writeString(stamp, stamped);
    }

    /**
     * Fits the runner's {@code params/} to the bot's {@code starting-params/} ({@link ParamsFitter#onDisk}), as each of
     * the open bot's compiles does: a parameter a newer tag added is filled, one it dropped is removed, the runner's
     * values kept. A fit that fails leaves the files as they were, and javac says the rest.
     */
    private static void fitParams(ProjectConfig config) {
        Path params = config.parametersPackageDir().toAbsolutePath().normalize();
        Path starting = config.projectPath().resolve(ParamsFitter.STARTING);
        if (!Files.isDirectory(params) || !Files.isDirectory(starting)) return;
        try {
            ParamsFitter.onDisk(JavacRelease.level(config.projectPath()), params, starting,
                    config.projectPath().resolve(VALUES_BACKUPS));
        } catch (IOException | UncheckedIOException e) {
            // the files are as they were
        }
    }

    /** Deletes the classes of the last compile, which only this runner writes. */
    private static void clear(Path classes) throws IOException {
        if (!Files.isDirectory(classes)) return;
        try (Stream<Path> walk = Files.walk(classes)) {
            for (Path each : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(each);
        }
    }

    /**
     * The bot's sources: each private parameter file the runner has, else its starting copy ({@code starting-params/}),
     * so a run copy never opened still compiles with its author's values.
     */
    static List<String> sources(ProjectConfig config) {
        Path params = config.parametersPackageDir().toAbsolutePath().normalize();
        Path starting = config.projectPath().resolve(ParamsFitter.STARTING).toAbsolutePath().normalize();
        List<String> out = new ArrayList<>(ClassPathManager.findJavaFiles(config.sourceRoot()));
        if (Files.isDirectory(starting)) {
            for (String file : ClassPathManager.findJavaFiles(starting)) {
                Path relative = starting.relativize(Path.of(file).toAbsolutePath().normalize());
                if (!Files.isRegularFile(params.resolve(relative))) out.add(file);
            }
        }
        return out;
    }

    /** Each source's path, size and time, and the classpath: what a compile's classes follow from. */
    private static String stamp(List<String> sources, List<String> classpath) throws IOException {
        StringBuilder out = new StringBuilder(String.join(File.pathSeparator, classpath)).append('\n');
        for (String source : sources.stream().sorted().toList()) {
            Path file = Path.of(source);
            out.append(source).append(' ').append(Files.size(file)).append(' ')
                    .append(Files.getLastModifiedTime(file).toMillis()).append('\n');
        }
        return out.toString();
    }

    /** One started run. */
    private record Running(Process process) implements SideRun {

        @Override
        public boolean isRunning() {
            return process.isAlive();
        }

        @Override
        public OptionalInt await(Duration limit) throws InterruptedException {
            long millis = limit == null ? 0 : Math.max(0, limit.toMillis());
            return process.waitFor(millis, TimeUnit.MILLISECONDS) ? OptionalInt.of(process.exitValue())
                    : OptionalInt.empty();
        }

        @Override
        public void stop() {
            if (!process.isAlive()) return;
            process.destroy();
            try {
                if (!process.waitFor(STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }
}
