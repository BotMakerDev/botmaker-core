// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.project.params;

import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectFile;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.project.ProjectWrites;
import com.botmaker.java.index.PoolReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The bot's own pools as the Parameters window changes them (2026-11-16, spec {@code 2026-11-15-run-claims-design.md}
 * §7): each one on the card of the parameter it fills. A pool lives in {@code params/bot/<Group>Pools.java}, an
 * {@code @Pools} group beside the {@code @Param} group {@code Group}, private to this PC like the values; its starting
 * copy holds the same pool with no element, so the fitter keeps the method and a fresh clone deals nothing until its
 * runner lists some. {@link PoolEdits} writes the Java.
 */
public final class JavaPools {

    private JavaPools() {}

    /** The bot's pool that fills the bot's own group {@code className}'s {@code method}; empty when none does. */
    public static Optional<PoolReader.Reading> filling(ProjectConfig config, List<PoolReader.Reading> pools,
                                                       String className, String method) {
        String owner = ownPackage(config) + "." + className;
        return pools.stream().filter(pool -> pool.fills()
                .filter(filled -> filled.owner().equals(owner) && filled.method().equals(method))
                .isPresent()).findFirst();
    }

    /**
     * Whether the window may change {@code pool}: a top-level group of {@code params/bot/} in a file of its own name,
     * the only file {@link #put} and {@link #remove} write.
     */
    public static boolean editable(ProjectConfig config, PoolReader.Reading pool) {
        String group = PoolEdits.simpleName(pool.group());
        return pool.group().equals(ownPackage(config) + "." + group)
                && Files.isRegularFile(JavaParameters.ownGroupFile(config, group));
    }

    /**
     * Puts {@code shape} into its group, creating the group (and its starting copy) when the bot has none: the
     * private copy with its elements, the starting copy with none. Answers whether the private copy changed.
     */
    public static boolean put(ProjectConfig config, ProjectState state, PoolEdits.Shape shape) {
        Optional<Path> file = groupFile(config, state, shape.group());
        if (file.isEmpty()) return false;
        Optional<Path> starting = JavaParameters.startingFile(config, file.get());
        if (starting.isPresent() && !Files.isRegularFile(starting.get())) {
            ProjectWrites.create(config, starting.get(), PoolEdits.emptyGroup(ownPackage(config), shape.group()),
                    "Create " + shape.group());
        }
        // The starting copy follows every setting but keeps what its author listed; a pool it lacks lists nothing.
        JavaParameters.rewriteStarting(config, file.get(), source -> PoolEdits.putKeepingListed(source,
                shape.blank()));
        return JavaParameters.rewrite(config, state, file.get(), source -> PoolEdits.put(source, shape));
    }

    /** Takes the pool {@code name} of the group {@code group} out of both copies; answers whether it was there. */
    public static boolean remove(ProjectConfig config, ProjectState state, String group, String name) {
        Path file = JavaParameters.ownGroupFile(config, group);
        if (!Files.isRegularFile(file)) return false;
        JavaParameters.rewriteStarting(config, file, source -> PoolEdits.remove(source, group, name));
        return JavaParameters.rewrite(config, state, file, source -> PoolEdits.remove(source, group, name));
    }

    /** The name a new pool's group takes beside the {@code @Param} group {@code className}. */
    public static String groupFor(String className) {
        return className + "Pools";
    }

    /** {@code params/bot/<group>.java}, created empty when missing; empty when it could not be written. */
    private static Optional<Path> groupFile(ProjectConfig config, ProjectState state, String group) {
        Path file = JavaParameters.ownGroupFile(config, group);
        if (Files.isRegularFile(file)) return Optional.of(file);
        if (!ProjectWrites.create(config, file, PoolEdits.emptyGroup(ownPackage(config), group), "Create " + group)) {
            return Optional.empty();
        }
        if (state != null && state.getFile(file).isEmpty()) {
            try {
                state.addFile(new ProjectFile(file, Files.readString(file)));
            } catch (IOException unreadable) {
                // Read from disk by the next walk instead.
            }
        }
        return Optional.of(file);
    }

    private static String ownPackage(ProjectConfig config) {
        return config.parametersPackage() + "." + JavaParameterSource.BOT;
    }
}
