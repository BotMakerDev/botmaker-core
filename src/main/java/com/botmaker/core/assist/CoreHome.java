package com.botmaker.core.assist;

import com.botmaker.core.project.ProjectInfo;
import com.botmaker.core.project.ProjectManager;
import com.botmaker.plugin.api.assist.AssistantTool;
import com.botmaker.plugin.api.assist.Describe;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.botmaker.core.assist.McpTools.said;

/**
 * The tools the core serves itself while no client with a screen serves its own (plan phase 11): the user's projects,
 * opening one, and its runs; and {@code device}, the emulators on this computer with no project (2026-11-10). A
 * client's tool of the same name wins, so with Studio attached these are Studio's.
 */
public final class CoreHome {

    /** What these tools ask of the core: its open project's runs, and an open for the MCP alone. */
    public interface Host extends BotRuns {
        /**
         * Opens {@code dir} for the MCP, held until a client opens another or the core ends; answers once it is open.
         *
         * @throws IllegalArgumentException with why it was not opened
         */
        String open(Path dir);
    }

    record Open(@Describe("the project's folder, absolute, as list_projects gives it") String path) {
    }

    private CoreHome() {
    }

    /** The core's own home and run tools over {@code host}, what it ran told by {@code log}. */
    public static List<SyncToolSpecification> served(McpJsonMapper json, Host host, RunLog log) {
        List<AssistantTool<?>> tools = new ArrayList<>();
        tools.add(said("list_projects", "List the projects under ~/BotMakerProjects, newest first: each one's name "
                + "and folder.", CoreHome::listProjects));
        tools.add(said("open_project", "Open a project in BotMaker's core, with no Studio: the file and run tools "
                + "then act on it. Studio, when it opens, shows the same project.", Open.class,
                call -> host.open(HomeTools.projectAt(call.path()))));
        tools.addAll(RunTools.headless(host, log));
        tools.add(DeviceTools.tool());
        return tools.stream().map(tool -> McpTools.served(json, tool)).toList();
    }

    private static String listProjects() {
        List<ProjectInfo> projects = new ProjectManager().listProjects();
        if (projects.isEmpty()) return "No project yet: Studio's bot list makes one.";
        return String.join("\n", projects.stream().map(p -> p.name() + "  " + p.projectPath()).toList());
    }
}
