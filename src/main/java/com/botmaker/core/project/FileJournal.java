// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What each file a run of writes touched said before the first of them, so the run can be taken back whole
 * (2026-11-21, a plugin's {@code History.together}: all its writes made, or none).
 *
 * <p>It hears every way a plugin's write reaches a file: a walk's rewrite of the open buffer and the disk
 * ({@code services.BotSources#forEach}), a file created or replaced whole on disk ({@link ProjectWrites}), and one a
 * plan deletes. A file that was not there is kept as absent, so taking the run back deletes it rather than leaving it
 * empty; a write that reached only the disk is put back on the disk only, never over an open buffer's unsaved text.
 * Per thread, as the walk's recorder is.
 */
public final class FileJournal {

    private static final ThreadLocal<FileJournal> OPEN = new ThreadLocal<>();

    /** A file's text before the first write to it (null: not there), and whether that write reached its buffer too. */
    private record Was(String text, boolean buffer) {}

    /** Each file touched, by its absolute path, in the order first touched. */
    private final Map<Path, Was> before = new LinkedHashMap<>();

    /**
     * Runs {@code writes} with this journal hearing every file they touch on this thread. A journal already open hears
     * them too, once this one ends, so an outer run still takes back what an inner one kept.
     */
    public <T> T keeping(Supplier<T> writes) {
        FileJournal outer = OPEN.get();
        OPEN.set(this);
        try {
            return writes.get();
        } finally {
            if (outer == null) {
                OPEN.remove();
            } else {
                OPEN.set(outer);
                before.forEach(outer.before::putIfAbsent);
            }
        }
    }

    /**
     * {@code file} is about to change, and said {@code was} (null: it was not there); {@code buffer} when the write
     * rewrites its open buffer as well as the disk. Heard by this thread's journal, if one is open.
     */
    public static void heard(Path file, String was, boolean buffer) {
        FileJournal journal = OPEN.get();
        if (journal != null && file != null) {
            journal.before.putIfAbsent(file.toAbsolutePath().normalize(), new Was(was, buffer));
        }
    }

    /** Every file touched, in the order first touched, with what it said before; null for one the writes created. */
    public Map<Path, String> before() {
        Map<Path, String> texts = new LinkedHashMap<>();
        before.forEach((file, was) -> texts.put(file, was.text()));
        return Collections.unmodifiableMap(texts);
    }

    /**
     * Puts every file back as it was, newest first: a file the writes created is deleted, a buffer they rewrote set
     * back with the disk. Nothing here is heard by a journal or recorded by the history. Answers the files it could not
     * put back, by name; empty when every one is as it was.
     */
    public List<String> restore(ProjectState state) {
        Map<Path, ProjectFile> open = new LinkedHashMap<>();
        if (state != null) {
            for (ProjectFile file : state.getAllFiles()) {
                if (file.getPath() != null) open.put(file.getPath().toAbsolutePath().normalize(), file);
            }
        }
        List<Path> files = new ArrayList<>(before.keySet());
        Collections.reverse(files);
        List<String> failed = new ArrayList<>();
        for (Path file : files) {
            Was was = before.get(file);
            ProjectFile buffer = was.buffer() ? open.get(file) : null;
            try {
                if (was.text() == null) {
                    Files.deleteIfExists(file);
                    continue;
                }
                if (buffer != null) buffer.setContent(was.text());
                // A buffer that never was on disk stays off it.
                if (buffer == null || Files.isRegularFile(file)) {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, was.text());
                }
            } catch (IOException e) {
                failed.add(file.getFileName() + " (" + e.getMessage() + ")");
            }
        }
        return failed;
    }
}
