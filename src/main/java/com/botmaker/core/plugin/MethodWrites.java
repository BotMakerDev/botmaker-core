// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.plugin;

import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.managed.BotMethods;
import com.botmaker.core.project.managed.ManagedSets;
import com.botmaker.java.index.BotIndex;
import com.botmaker.java.index.nav.Refactor;
import com.botmaker.plugin.api.source.PluginValues.MethodWrite;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/** Writes the bot's methods a plugin asks for, each in its class, which is created when the bot has none. */
final class MethodWrites {

    private final ProjectConfig config;
    private final BotWrites writes;

    MethodWrites(ProjectConfig config, BotWrites writes) {
        this.config = config;
        this.writes = writes;
    }

    /** A method returning the enum {@code group} of the set {@code id}, or returning nothing when both are null. */
    MethodWrite create(String id, String group, String className, String method) {
        BotIndex index = writes.index();
        String returns = null;
        String imported = null;
        String first = null;
        if (id != null || group != null) {
            Optional<ManagedSets.Group> found = ManagedSets.group(index, id, group);
            if (found.isEmpty()) return new MethodWrite(null, DeclaredValues.missing(id, group));
            if (found.get().members().isEmpty()) {
                return new MethodWrite(null, found.get().qualified() + " has no constant to return.");
            }
            returns = found.get().qualified();
            String canonical = canonical(index, found.get());
            imported = canonical.substring(0, canonical.length() - group.length() - 1);
            first = found.get().members().getFirst().name();
        }
        return written(index, className, method, returns, imported, first);
    }

    /** A method returning a boolean. */
    MethodWrite createBoolean(String className, String method) {
        return written(writes.index(), className, method, BotMethods.BOOLEAN, null, null);
    }

    /** {@link BotMethods#create}'s method written into the bot, creating its class when there is none. */
    private MethodWrite written(BotIndex index, String className, String method, String returns, String imported,
                                String first) {
        Path file = className == null ? null : config.mainPackageDir().resolve(className + ".java");
        Refactor.Outcome planned = BotMethods.create(index, config.mainPackage(), file, className, method, returns,
                imported, first);
        if (planned instanceof Refactor.Planned plan && file != null && plan.rewrites().containsKey(file)
                && !index.sources().containsKey(file)) {
            // A new class: nothing in the bot has that file yet, so it is created rather than rewritten.
            if (Files.exists(file)) {
                // A file the bot's sources do not declare the class in: never written over.
                return new MethodWrite(null, className + ".java already exists but declares no class "
                        + className + ", so nothing was written.");
            }
            writes.checkpoint("Before: " + plan.summary());
            try {
                writes.create(file, plan.rewrites().get(file), plan.summary());
            } catch (UncheckedIOException e) {
                return new MethodWrite(null, "Could not write " + className + ".java: " + e.getCause().getMessage());
            }
            writes.redraw(Set.of(file.toAbsolutePath().normalize()));
            return new MethodWrite(className + "::" + method, null);
        }
        Optional<String> refused = writes.apply(planned);
        return refused.isPresent() ? new MethodWrite(null, refused.get())
                : new MethodWrite(className + "::" + method, null);
    }

    /** The canonical name of a nested enum — {@code com.bot.plugins.sdk.Activities.Collect}. */
    static String canonical(BotIndex index, ManagedSets.Group group) {
        String pkg = index.read(units -> {
            var unit = units.get(group.file());
            return unit == null || unit.getPackage() == null ? ""
                    : unit.getPackage().getName().getFullyQualifiedName();
        });
        return (pkg.isEmpty() ? "" : pkg + ".") + group.qualified();
    }
}
