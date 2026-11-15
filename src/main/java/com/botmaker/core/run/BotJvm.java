package com.botmaker.core.run;

import com.botmaker.core.plugin.HostFolders;
import com.botmaker.core.plugin.PluginHost;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.StudioProjectSettings;
import com.botmaker.runlog.RunLog;
import com.botmaker.core.run.agent.RunPlan;
import com.botmaker.core.run.agent.TracePlan;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The options every JVM Studio starts a bot in takes, in one place because there are two such JVMs — the
 * run ({@link CodeExecutionService}) and the debug ({@link DebuggingService}) — and a bot that
 * warns under one and not the other is a difference nobody intended.
 *
 * <p>These are not Studio's own options: Studio's are in {@code pom.xml} (the {@code javafx:run} plugin and
 * jpackage) and in its jar manifest. A bot runs in a JVM of its own, launched from Java, so its command
 * line is built here.
 */
public final class BotJvm {

    /**
     * <b>{@code --enable-native-access=ALL-UNNAMED}</b>: the SDK loads OpenCV's natives through
     * {@code System.loadLibrary} from the classpath (an unnamed module), which JDK 24 warns about by name
     * and a later release will refuse outright. The bot did nothing wrong and the warning is the first
     * thing its author sees in the output pane, so it is granted rather than explained.
     */
    public static final List<String> OPTIONS = List.of("--enable-native-access=ALL-UNNAMED");

    /**
     * {@link #OPTIONS}, then {@code -D<name>=<value>} for each of this checkout's run properties
     * ({@code StudioProjectSettings.runProperties}, set by a plugin through {@code Runs.setProperty}) — what this
     * machine starts the bot with, such as the SDK's {@code botmaker.launch.target} (2026-09-27). One argument
     * per property, so a value with spaces reaches the bot whole; a blank name is skipped.
     */
    public static List<String> options(StudioProjectSettings settings) {
        return options(settings == null ? Map.of() : settings.runProperties());
    }

    /**
     * {@code -javaagent:<jar>=<plan>}: the run agent ({@code run.agent}, one since 2026-11-03), the host in the
     * bot's JVM. It keeps the run's log under {@code <project>/target/botmaker-runs}, hands each plugin declared on
     * the bot's classpath its run channel, and traces each call the bot's own classes
     * ({@code config.compiledOutputPath()}) make into an offered class of the project's plugins, minus what the
     * Trace tab hides ({@code hiddenTraceWriters}). The plan is a file beside the classes, rewritten every run.
     * Empty only when the agent cannot be written: a run never fails over it.
     */
    public static List<String> runAgent(ProjectConfig config, StudioProjectSettings settings) {
        return runAgent(config, settings, Optional.empty());
    }

    /**
     * {@link #runAgent} for a run whose plugins keep their values of this PC (2026-11-15): the caller readies the
     * folders before it ({@code HostFolders.readyForRun}) and commits what it wrote after ({@code afterRun}). A try,
     * a debug session and a side run keep none.
     */
    public static List<String> keepingRunAgent(ProjectConfig config, StudioProjectSettings settings) {
        return runAgent(config, settings, Optional.of(HostFolders.root()));
    }

    private static List<String> runAgent(ProjectConfig config, StudioProjectSettings settings,
                                         Optional<Path> hostValues) {
        if (config == null) return List.of();
        try {
            return agent(config, settings, runPlan(config), List.of(), Optional.empty(), Optional.empty(), hostValues);
        } catch (IOException | RuntimeException e) {
            System.err.println("Runs this time without its log and call trace: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * {@link #runAgent} for one of several runs (spec {@code 2026-11-15-run-claims-design.md} §5): its plan, a file of
     * its own beside the classes, lists the methods the agent rewrites to return what {@code runId} was dealt, and the
     * live runs its claims are checked in. Unlike one run's, it throws when the agent cannot be written: a run of
     * several without it would use the body's values.
     *
     * @return the agent's argument, and the plan file, which the run reads once at its start
     */
    public static Agent pooledAgent(ProjectConfig config, StudioProjectSettings settings, String runId,
                                    List<RunPlan.Pooled> pooled, Path registry) throws IOException {
        Path plan = config.compiledOutputPath().resolveSibling("botmaker-run-" + runId + ".properties");
        List<String> argument = agent(config, settings, plan, pooled, Optional.of(runId), Optional.of(registry),
                Optional.of(HostFolders.root()));
        return new Agent(argument, plan);
    }

    /** The agent's command-line argument and the plan file it names. */
    public record Agent(List<String> arguments, Path plan) {

        public Agent {
            arguments = List.copyOf(arguments);
        }
    }

    private static List<String> agent(ProjectConfig config, StudioProjectSettings settings, Path plan,
                                      List<RunPlan.Pooled> pooled, Optional<String> runId, Optional<Path> registry,
                                      Optional<Path> hostValues) throws IOException {
        List<String> offered = PluginHost.menuFacades().stream().map(f -> f.type().getName()).toList();
        RunAgentJar.Jars jars = RunAgentJar.jars();
        TracePlan trace = new TracePlan(Set.of(config.compiledOutputPath()), Set.copyOf(offered),
                settings == null ? Set.of() : Set.copyOf(settings.hiddenTraceWriters()));
        new RunPlan(trace, Optional.of(config.projectPath().resolve(RunLog.FOLDER)), jars.inner(), pooled, runId,
                registry, hostValues).write(plan);
        return List.of("-javaagent:" + jars.agent() + "=" + plan);
    }

    /** Where {@link #runAgent} writes the plan the agent reads once, at the JVM's start. */
    static Path runPlan(ProjectConfig config) {
        return config.compiledOutputPath().resolveSibling("botmaker-run.properties");
    }

    static List<String> options(Map<String, String> runProperties) {
        List<String> out = new ArrayList<>(OPTIONS);
        if (runProperties != null) {
            runProperties.forEach((name, value) -> {
                if (name != null && !name.isBlank() && value != null) out.add("-D" + name.trim() + "=" + value);
            });
        }
        return List.copyOf(out);
    }

    private BotJvm() {
    }
}
