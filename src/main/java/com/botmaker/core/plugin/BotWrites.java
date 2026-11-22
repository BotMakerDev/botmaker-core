// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.plugin;

import com.botmaker.core.project.FileJournal;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectFile;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.project.ProjectWrites;
import com.botmaker.core.project.managed.BotMethods;
import com.botmaker.core.project.managed.JavaManagedValues;
import com.botmaker.core.project.managed.ManagedMethod;
import com.botmaker.core.project.params.JavaParameters;
import com.botmaker.core.project.vcs.Checkpoints;
import com.botmaker.core.project.vcs.VersionOrigin;
import com.botmaker.core.services.BotSources;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.grammar.ShortNames;
import com.botmaker.java.index.BotIndex;
import com.botmaker.java.index.nav.Refactor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * How a plugin's write reaches the bot's files, whatever it writes: a snapshot first, one step of the editor's undo,
 * and the open file redrawn when the write changed it.
 */
final class BotWrites {

    /** The label the snapshot before a write is recorded under. */
    static final String HISTORY_LABEL = "Change a plugin's value";

    private final ProjectConfig config;
    private final ProjectState state;
    /** Null when nobody hears a write: no history, and nothing to redraw. */
    private final HostPluginValues.Edits edits;

    BotWrites(ProjectConfig config, ProjectState state, HostPluginValues.Edits edits) {
        this.config = config;
        this.state = state;
        this.edits = edits;
    }

    BotIndex index() {
        return BotSources.index(config, state);
    }

    /**
     * One write: a snapshot, then the rewrite of one expression.
     *
     * <p>The snapshot is taken before the file is touched and per {@code set} call, so an editor that writes
     * on every keystroke would record every one of them. That is why the built-in editors commit on OK
     * rather than as the user types (2026-09-20), and why a plugin's should too.
     */
    void write(ManagedMethod value, JavaValue expression) {
        recorded(() -> {
            snapshot();
            JavaManagedValues.setValue(config, state, value, expression);
        });
    }

    /**
     * A plan written as one step in Project History: the snapshot first, then every file it rewrites, buffer
     * and disk, then the open file redrawn when it was one of them. It is one step of the editor's ↶ as well
     * (2026-10-15), or part of the plugin's batch.
     */
    Optional<String> apply(Refactor.Outcome outcome) {
        return switch (outcome) {
            case Refactor.Refused refused -> Optional.of(refused.reason());
            case Refactor.Planned plan -> {
                recorded(() -> {
                    checkpoint("Before: " + plan.summary());
                    BotSources.forEach(config, state, (file, source) -> plan.rewrites().get(file));
                });
                redraw(plan.rewrites().keySet());
                yield Optional.empty();
            }
        };
    }

    /** One undoable parameter write, its snapshot first, and whatever shows the file redrawn. */
    Optional<String> written(Supplier<Optional<String>> write) {
        List<Optional<String>> refused = new ArrayList<>();
        recorded(() -> {
            snapshot();
            refused.add(write.get());
        });
        ProjectFile active = state == null ? null : state.getActiveFile();
        if (active != null && active.getPath() != null) redraw(Set.of(active.getPath().toAbsolutePath().normalize()));
        return refused.getFirst();
    }

    /** A new class's file written; inside a batch, the next write reads the bot's types again (WriteSteps.Step#types). */
    void create(Path file, String source, String label) {
        ProjectWrites.create(config, file, source, label);
        // A plugin's group in params/ is written with its starting copy, which the fitter keeps params/ to.
        JavaParameters.startingFile(config, file).ifPresent(starting ->
                ProjectWrites.create(config, starting, source, label));
        WriteSteps.Step open = WriteSteps.open();
        if (open != null) open.typesChanged();
    }

    /**
     * {@code value} as it may be written into any of the bot's files: a part a plugin hands over as the Java it
     * is written as, {@code Research::body}, imports its class, and a full name is written short (2026-10-13).
     */
    JavaValue imported(JavaValue value) {
        // Inside a plugin's batch, read once for all its writes (WriteSteps.Step#types).
        WriteSteps.Step open = WriteSteps.open();
        List<String> types = open == null ? BotMethods.topLevelTypes(index())
                : open.types(() -> BotMethods.topLevelTypes(index()));
        return ShortNames.imported(value, types);
    }

    void snapshot() {
        checkpoint(HISTORY_LABEL);
    }

    /**
     * The Project History snapshot before a write: one per undo step, so a batch takes one, not one per write, and
     * one per plugin tool call ({@link ToolCalls}).
     */
    void checkpoint(String label) {
        WriteSteps.Step open = WriteSteps.open();
        if (open != null && !open.firstCheckpoint()) return;
        if (!ToolCalls.firstCheckpoint()) return;
        Checkpoints.take(config.projectPath(), VersionOrigin.SAFETY, label);
    }

    /**
     * Runs {@code writes} as one step of the editor's undo ({@link WriteSteps}), or as part of the plugin's batch
     * when one is open — so ↶ takes a plugin's write back like an edit on the canvas.
     */
    void recorded(Runnable writes) {
        step(HISTORY_LABEL, () -> {
            writes.run();
            return null;
        });
    }

    /** {@code writes} as one step named {@code label}, or as part of the step open on this thread, told to the host. */
    <T> T step(String label, Supplier<T> writes) {
        return WriteSteps.step(label, writes, (named, changed) -> {
            if (edits != null) edits.step(named, changed);
        });
    }

    /** Every file an editor tab holds, by its absolute path. */
    Set<Path> openFiles() {
        Set<Path> open = new LinkedHashSet<>();
        for (ProjectFile file : state.getAllFiles()) {
            if (file.getPath() != null) open.add(file.getPath().toAbsolutePath().normalize());
        }
        return open;
    }

    /**
     * Deletes each of {@code gone} but those in {@code open}, adding what it deleted to {@code changed}: an open
     * buffer would write the file back on its next save, so it stays as it was until the next bind.
     */
    static void deleteUnlessOpen(Set<Path> gone, Set<Path> open, Set<Path> changed) {
        for (Path file : gone) {
            if (open.contains(file)) {
                System.err.println("Warning: " + file + " is open in an editor, so it was not deleted.");
                continue;
            }
            try {
                if (Files.isRegularFile(file)) FileJournal.heard(file, Files.readString(file), false);
                if (Files.deleteIfExists(file)) changed.add(file);
            } catch (IOException e) {
                System.err.println("Warning: could not delete " + file + ": " + e.getMessage());
            }
        }
    }

    /**
     * Tells the editor to re-render the file it shows when a write here changed it underneath — the buffer
     * really changed, so a stale view is the only thing wrong, which is the kind of bug that reads as data loss.
     */
    void redraw(Collection<Path> changed) {
        if (edits == null) return;
        ProjectFile active = state.getActiveFile();
        if (active == null || active.getPath() == null
                || !changed.contains(active.getPath().toAbsolutePath().normalize())) return;
        edits.redraw(active.getContent());
    }
}
