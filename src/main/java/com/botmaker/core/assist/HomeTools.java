package com.botmaker.core.assist;

import com.botmaker.plugin.api.assist.AssistantTool;
import com.botmaker.plugin.api.assist.Describe;
import com.botmaker.core.project.ProjectManager;
import com.botmaker.core.sharing.UnpackedBots;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import static com.botmaker.core.assist.McpTools.given;
import static com.botmaker.core.assist.McpTools.said;

/**
 * What a dev Studio with no project open serves through the core (2026-10-25; through the core since plan phase 10d):
 * on the bot list, in the Run window, while a project opens. Its screen's tools ({@link ToolGroup#tools},
 * {@link ToolGroup#homeTools}: Studio's {@code ui} and {@code quit_studio}) and the bot list read, a project opened and a
 * new one made without a click (2026-10-28), so a session starts from the home as well as from a project. Each asks its
 * {@link Shell} (2026-11-05), never a window of Studio's; and only when Claude drives the screen ({@link ToolGroup#driven}).
 */
public final class HomeTools {

    private static final DateTimeFormatter USED = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    /**
     * What the home's tools ask of the client that shows the bot list; Studio's is {@code HomeShell}. Every method may be
     * called from any thread.
     */
    public interface Shell {
        /** Opens {@code dir} in the Runner when {@code run}, else in the editor, on the client's own thread. */
        void open(Path dir, boolean run);

        /** My bots as the bot list shows them, newest first. */
        List<Listed> bots();

        /** The published bots a project can start from, a line each, for the model to read. */
        String publishedBots();

        /**
         * Makes the project {@code name}, blank or from the published {@code bot}, with {@code plugins}, asking on
         * screen what needs the user's yes, then hands its folder to {@code opened}, whose answer this returns.
         *
         * @throws IllegalArgumentException with the sentence that says why it was not made
         */
        String create(String name, String bot, List<String> plugins, Function<Path, String> opened);

        /** What a question on screen waits for, or empty when none is up. */
        String busy();

        /** One of My bots: its folder and name, whose, its version, when it was used, and what state it is in. */
        record Listed(Path dir, String name, Optional<String> owner, Optional<String> version, LocalDateTime lastUsed,
                      boolean gone, boolean unpacked, Optional<String> available) {
        }
    }

    record OpenProject(@Describe("the project's folder, absolute, as list_projects gives it") String path,
                       @Describe(value = "true for the Runner, which runs the bot; left out, the editor",
                               optional = true) Boolean run) {
    }

    private HomeTools() {
    }

    /** What the home serves for {@code screen} and {@code shell}: nothing unless Claude drives the screen. */
    public static List<SyncToolSpecification> served(McpJsonMapper json, ToolGroup screen, Shell shell) {
        if (!screen.driven()) return List.of();
        List<AssistantTool<?>> own = new ArrayList<>(screen.tools());
        own.addAll(screen.homeTools());
        own.addAll(of(shell));
        return own.stream().map(t -> McpTools.withNews(screen, McpTools.served(json, t))).toList();
    }

    static List<AssistantTool<?>> of(Shell shell) {
        return List.of(
                said("list_projects", "List My bots as the bot list shows them, newest first: each one's name, "
                        + "whose, its version and its folder.", () -> listProjects(shell)),
                said("open_project", "Open a project in the editor, or the Runner. The editor's tools join these "
                                + "on this address once it is open; the Runner serves none, so these stay.",
                        OpenProject.class, call -> openProject(shell, call.path(), Boolean.TRUE.equals(call.run()))),
                said("list_bots", "List the published bots create_project can start from.", shell::publishedBots),
                said("create_project", "Create a project, blank or cloned from a published bot's release, and "
                                + "open it in the editor.",
                        ProjectTools.NewProject.class, call -> shell.create(call.name(), given(call.bot()),
                                ProjectTools.pluginIds(call.plugins()), dir -> {
                                    shell.open(dir, false);
                                    return opening(false);
                                })));
    }

    static String listProjects(Shell shell) {
        List<Shell.Listed> bots = shell.bots();
        if (bots.isEmpty()) return "My bots is empty: create_project makes one.";
        return String.join("\n", bots.stream().map(HomeTools::line).toList());
    }

    private static String line(Shell.Listed bot) {
        String whose = bot.owner().map(o -> "by " + o).orElse("mine");
        String version = bot.version().map(v -> ", " + v).orElse("");
        String used = bot.lastUsed().equals(LocalDateTime.MIN) ? "" : ", used " + USED.format(bot.lastUsed());
        String state = (bot.gone() ? ", its folder is gone" : "")
                + (bot.unpacked() ? ", a .bmk to run" : "")
                + bot.available().map(t -> ", " + t + " available").orElse("");
        return bot.name() + "  (" + whose + version + used + state + ")  " + bot.dir();
    }

    static String openProject(Shell shell, String path, boolean run) {
        Path wanted = projectAt(path);
        // A project made after the user agrees would switch the window away from this one.
        String asked = shell.busy();
        if (!asked.isEmpty()) throw new IllegalArgumentException(asked + " Wait for it: an answer after it says how it ended.");
        shell.open(wanted, run);
        return "Opening " + wanted.getFileName() + ". " + opening(run);
    }

    /** The project folder {@code path} names, absolute; refused, saying why, when it names none. */
    static Path projectAt(String path) {
        Path dir;
        try {
            dir = Path.of(given(path).strip());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not a path: " + path);
        }
        if (!dir.isAbsolute()) throw new IllegalArgumentException("Give the folder's absolute path: " + path);
        Path wanted = dir.normalize();
        if (!Files.isDirectory(wanted)) throw new IllegalArgumentException("No folder " + wanted + ".");
        if (!UnpackedBots.isUnpacked(wanted) && new ProjectManager().projectAt(wanted).isEmpty()) {
            throw new IllegalArgumentException(wanted + " has no " + ProjectManager.PROJECT_SHAPE
                    + ", so there is no project here to open.");
        }
        return wanted;
    }

    private static String opening(boolean run) {
        return run ? "The Runner serves no project's tools: ui drives it, and these tools stay."
                : "The editor's tools are served on this same address once it is open.";
    }
}
