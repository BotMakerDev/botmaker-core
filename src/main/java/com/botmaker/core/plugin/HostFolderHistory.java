package com.botmaker.core.plugin;

import com.botmaker.core.project.vcs.ProjectVcs;
import com.botmaker.core.project.vcs.VersionOrigin;
import com.botmaker.plugin.api.History;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The undo of a plugin's values of this PC ({@code StudioServices.hostHistory()}): ↶ and ↷ over its folder's commits,
 * each write being one ({@link HostValues}). Never a step of the bot's history: the folder is no bot's.
 *
 * <p><b>A step is a commit, and undo is a commit too.</b> ↶ puts back the tree of the commit before the current step
 * as a new commit, "Undo Set GREETING"; ↷ puts the step's tree back the same way, so nothing is ever rewritten and
 * every undo can itself be found in {@code git log}. The steps are read from the log, newest first, down to the
 * newest upgrade or first commit, which is never undone: the pom it wrote names the version serving the folder.
 *
 * <p><b>Where ↶ stands is the folder's, in this process</b> ({@link State}): the copy a newer version's bind puts in
 * serves on from it, and a window's listener stays heard. A write made since (by this process or the other) starts
 * again from the newest commit, dropping what could be redone, as an editor does. An undo's own commit is skipped
 * when the steps are read again, through what this process remembers it restored; a host that started since reads it
 * as a step.
 */
final class HostFolderHistory implements History {

    /** {@link HostValues}'s folder lock: {@code work} under it, or why it could not be taken. */
    interface Locked {
        Optional<String> run(Supplier<Optional<String>> work);
    }

    /** What one folder's history holds in this process, whichever copy serves it. */
    private static final class State {
        final List<Runnable> listeners = new CopyOnWriteArrayList<>();
        /** Each commit an undo or redo made, by full SHA, to the step whose tree it put back. */
        final Map<String, String> restored = new ConcurrentHashMap<>();
        /** The steps, newest first, as read at {@link #head}; guarded by this. */
        List<ProjectVcs.CommitInfo> steps = List.of();
        /** The index in {@link #steps} of the step whose tree the folder holds; guarded by this. */
        int at;
        /** The commit the folder was at when this process last looked or moved; guarded by this. */
        String head = "";
    }

    private static final Map<Path, State> STATES = new ConcurrentHashMap<>();

    private final HostProject project;
    private final Locked locked;
    private final Runnable ready;
    private final Supplier<Optional<String>> refusal;
    private final Consumer<String> warn;
    private final State state;
    private final ThreadLocal<String> batch = new ThreadLocal<>();

    /**
     * @param ready   brings the folder up to this version before a step, as before a write
     * @param refusal under the lock, why this version may not change the folder, as a write is refused
     */
    HostFolderHistory(Path folder, HostProject project, Locked locked, Runnable ready,
                      Supplier<Optional<String>> refusal, Consumer<String> warn) {
        this.project = project;
        this.locked = locked;
        this.ready = ready;
        this.refusal = refusal;
        this.warn = warn;
        this.state = STATES.computeIfAbsent(folder.toAbsolutePath().normalize(), key -> new State());
    }

    @Override
    public boolean canUndo() {
        synchronized (state) {
            return synced() && state.at + 1 < state.steps.size();
        }
    }

    @Override
    public boolean canRedo() {
        synchronized (state) {
            return synced() && state.at > 0;
        }
    }

    @Override
    public String undoLabel() {
        synchronized (state) {
            return canUndo() ? state.steps.get(state.at).title() : "";
        }
    }

    @Override
    public String redoLabel() {
        synchronized (state) {
            return canRedo() ? state.steps.get(state.at - 1).title() : "";
        }
    }

    @Override
    public void undo() {
        step(true);
    }

    @Override
    public void redo() {
        step(false);
    }

    private void step(boolean back) {
        ready.run();
        locked.run(() -> {
            Optional<String> refused = refusal.get();
            if (refused.isPresent()) return refused;
            synchronized (state) {
                if (back ? !canUndo() : !canRedo()) return Optional.empty();
                ProjectVcs.CommitInfo step = state.steps.get(back ? state.at : state.at - 1);
                ProjectVcs.CommitInfo target = back ? state.steps.get(state.at + 1) : step;
                Optional<String> failed = move(target, (back ? "Undo " : "Redo ") + step.title());
                if (failed.isEmpty()) state.at += back ? 1 : -1;
                return failed;
            }
        }).ifPresent(warn);
        changed();
    }

    @Override
    public AutoCloseable onChanged(Runnable listener) {
        if (listener == null) return () -> {
        };
        state.listeners.add(listener);
        return () -> state.listeners.remove(listener);
    }

    /** Runs {@code writes}, each committing nothing, then commits what they changed as one step, {@code label}. */
    @Override
    public void batch(String label, Runnable writes) {
        if (writes == null) return;
        if (batching()) {
            writes.run();
            return;
        }
        batch.set(label);
        try {
            writes.run();
        } finally {
            batch.remove();
            // What passed is the step even when a later write threw.
            locked.run(() -> project.commit(VersionOrigin.SAVE, label)).ifPresent(warn);
            changed();
        }
    }

    /** Whether a write on this thread belongs to a {@link #batch}, which commits it. */
    boolean batching() {
        return batch.get() != null;
    }

    /** Tells each listener the history changed: a write was committed, or a step undone or redone. */
    void changed() {
        if (batching()) return;
        for (Runnable listener : state.listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                warn.accept("A listener of this PC's history failed: " + e);
            }
        }
    }

    /** Puts {@code target}'s tree back as a commit labelled {@code label}; empty when done, else why not. */
    private Optional<String> move(ProjectVcs.CommitInfo target, String label) {
        try {
            String tip = project.vcs().restoreTo(target.sha(), VersionOrigin.RESTORE, label);
            if (!tip.equals(state.head)) state.restored.put(tip, target.sha());
            state.head = tip;
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.of("Could not \"" + label + "\" in this PC's values: " + e.getMessage());
        }
    }

    /**
     * Reads the steps again when the folder moved since this process looked; false when it has no history. Under
     * {@code state}'s monitor. The head alone is read when nothing moved: a window asks four times per redraw.
     */
    private boolean synced() {
        try {
            ProjectVcs vcs = project.vcs();
            String head = project.isProject() ? vcs.head() : "";
            if (head.isEmpty()) {
                state.steps = List.of();
                state.head = "";
                state.at = 0;
                return false;
            }
            if (head.equals(state.head)) return true;
            state.steps = steps(vcs.history());
            state.head = head;
            state.at = 0;
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** {@code log}'s steps: an undo's own commit and what it skipped left out, down to the newest upgrade or first. */
    private List<ProjectVcs.CommitInfo> steps(List<ProjectVcs.CommitInfo> log) {
        List<ProjectVcs.CommitInfo> out = new ArrayList<>();
        String skipTo = null;
        for (ProjectVcs.CommitInfo commit : log) {
            if (skipTo != null && !commit.sha().equals(skipTo)) continue;
            skipTo = state.restored.get(commit.sha());
            if (skipTo != null) continue;
            out.add(commit);
            if (commit.origin() == VersionOrigin.UPDATE || commit.origin() == VersionOrigin.CREATE) break;
        }
        return List.copyOf(out);
    }
}
