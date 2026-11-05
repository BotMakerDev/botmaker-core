package com.botmaker.core.edit;

import com.botmaker.core.parser.guard.RefusalJournal;
import com.botmaker.core.plugin.PluginHost;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectFile;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.project.ProjectTemplate;
import com.botmaker.core.project.StudioProjectSettings;
import com.botmaker.core.project.managed.ManagedConstants;
import com.botmaker.java.grammar.ValueGrammar;
import com.botmaker.java.index.LibraryIndex;
import com.botmaker.java.parse.JavaLevel;
import com.botmaker.java.types.ProjectAnalyzer;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything an {@link EditSession} needs to know about one of the bot's files, copied out of {@link ProjectState} once
 * so that a session never reads the live state again.
 *
 * <p><b>Why a copy.</b> A client re-parses on every accepted edit, and what it drew lags the source. A session that
 * mixed the live state with its own edits would hand {@code CodeEditor} a node from one tree and a compilation unit
 * from another. So each step of a session builds its own {@link ProjectState} from this record and its working copy of
 * the source ({@link #stage}), and the live state is touched exactly once, when the session commits.
 *
 * @param index   the library index a staged {@link ProjectAnalyzer} reads, or {@code null} in a test
 * @param journal where a refused rewrite is recorded, or {@code null} for the cache directory
 * @param grammar   the value grammar a slot's value is read and written with
 * @param constants the bot's managed constants, which a slot may be set to by name ({@code Pictures.ORE})
 * @param others    the bot's other files as they were when the session began, read only: an insert that names
 *                  another class of the bot ({@code Bot.runFlow(Sdk.class)}) looks for it among them
 * @param level     the Java the bot is written in, which each staged step is parsed at
 */
public record EditWorkspace(ProjectConfig config,
                            Path file,
                            List<String> classpath,
                            Path sourceRoot,
                            ProjectTemplate template,
                            StudioProjectSettings settings,
                            LibraryIndex index,
                            RefusalJournal journal,
                            ValueGrammar grammar,
                            List<ManagedConstants.Constant> constants,
                            List<ProjectFile> others,
                            JavaLevel level) {

    public EditWorkspace {
        classpath = classpath == null ? List.of() : List.copyOf(classpath);
        if (grammar == null) grammar = PluginHost.grammar();
        constants = constants == null ? List.of() : List.copyOf(constants);
        others = others == null ? List.of() : List.copyOf(others);
        if (level == null) level = JavaLevel.LATEST;
    }

    /** A workspace of a bot with no pom: JDT's newest Java. */
    public EditWorkspace(ProjectConfig config, Path file, List<String> classpath, Path sourceRoot,
                         ProjectTemplate template, StudioProjectSettings settings, LibraryIndex index,
                         RefusalJournal journal, ValueGrammar grammar, List<ManagedConstants.Constant> constants,
                         List<ProjectFile> others) {
        this(config, file, classpath, sourceRoot, template, settings, index, journal, grammar, constants, others,
                JavaLevel.LATEST);
    }

    /** A workspace that knows none of the bot's other files. */
    public EditWorkspace(ProjectConfig config, Path file, List<String> classpath, Path sourceRoot,
                         ProjectTemplate template, StudioProjectSettings settings, LibraryIndex index,
                         RefusalJournal journal, ValueGrammar grammar, List<ManagedConstants.Constant> constants) {
        this(config, file, classpath, sourceRoot, template, settings, index, journal, grammar, constants, List.of());
    }

    /** {@code file} of the open project. On the thread that owns {@link ProjectState}. */
    public static EditWorkspace of(ProjectConfig config, ProjectState state, LibraryIndex index, Path file) {
        return of(config, state, index, file, ManagedConstants.scan(config, state));
    }

    /** The same, with no managed constants: for edits that write no value, which need not pay for the scan. */
    static EditWorkspace withoutConstants(ProjectConfig config, ProjectState state, LibraryIndex index, Path file) {
        return of(config, state, index, file, List.of());
    }

    private static EditWorkspace of(ProjectConfig config, ProjectState state, LibraryIndex index, Path file,
                                    List<ManagedConstants.Constant> constants) {
        // Copied, not shared: the live files change as the canvas re-parses.
        List<ProjectFile> others = state.getAllFiles().stream()
                .filter(each -> !each.getPath().equals(file))
                .map(each -> new ProjectFile(each.getPath(), each.getContent()))
                .toList();
        return new EditWorkspace(config, file, state.getResolvedClasspath(), state.getSourcePath(),
                state.getTemplate(), state.getSettings(), index, null, PluginHost.grammar(), constants, others,
                state.level());
    }

    /** The constants as the grammar reads them. */
    ManagedConstants.Lookup lookup() {
        return new ManagedConstants.Lookup(constants, grammar);
    }

    /** A fresh {@link ProjectState} holding {@code source} as this workspace's file: one step of a session. */
    public ProjectState stage(String source) {
        return fill(new ProjectState(), source);
    }

    /** {@code staged}, empty as it came, filled as {@link #stage} fills one: for a client's own subclass. */
    public <S extends ProjectState> S fill(S staged, String source) {
        others.forEach(each -> staged.addFile(new ProjectFile(each.getPath(), each.getContent())));
        staged.addFile(new ProjectFile(file, source));
        staged.setActiveFile(file);
        staged.setSourcePath(sourceRoot);
        staged.setResolvedClasspath(classpath);
        staged.setLevel(level);
        staged.setTemplate(template);
        if (settings != null) staged.setSettings(settings);
        staged.setCurrentCode(source);
        return staged;
    }

    /** The analyzer a staged edit seeds its arguments with. */
    public ProjectAnalyzer analyzer(ProjectState staged) {
        return new ProjectAnalyzer(index, staged);
    }
}
