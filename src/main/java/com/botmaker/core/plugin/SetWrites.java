// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.plugin;

import com.botmaker.core.parser.refactor.ReviewMarker;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.project.managed.ManagedMigrations;
import com.botmaker.core.project.managed.ManagedSets;
import com.botmaker.core.project.managed.NestedClasses;
import com.botmaker.core.project.params.JavaParameterEdits;
import com.botmaker.core.services.BotSources;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.index.BotIndex;
import com.botmaker.java.index.nav.Refactor;
import com.botmaker.plugin.api.source.ManagedClasses;
import com.botmaker.plugin.api.source.ManagedOpenSet;
import com.botmaker.plugin.api.source.ManagedValue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Writes the members of a plugin's open set: its constants, its nested enums and its classes, planned by
 * {@link ManagedSets} and {@link NestedClasses}. Each answer is empty when written, else why not.
 */
final class SetWrites {

    private final ProjectConfig config;
    private final ProjectState state;
    private final BotWrites writes;

    SetWrites(ProjectConfig config, ProjectState state, BotWrites writes) {
        this.config = config;
        this.state = state;
        this.writes = writes;
    }

    /** What the setup of {@code constant}'s class returns, written. */
    Optional<String> setSetup(ManagedClasses<?> set, String constant, JavaValue expression) {
        String setup = set.setup().getName();
        // Inside a batch, the holder found by the first write serves the next ones, checked on its file (Tidy).
        WriteSteps.Step open = WriteSteps.open();
        WriteSteps.HolderAt at = open == null ? null : open.holder(set.id());
        if (at != null && setSetup(at, set.id(), constant, setup, expression)) return Optional.empty();
        Optional<NestedClasses.Nested> nested = NestedClasses.named(writes.index(), set.id(), constant,
                PluginHost.grammar(), setup).filter(found -> found.setup() != null);
        if (nested.isEmpty()) return Optional.of(DeclaredValues.missing(set.id(), constant));
        if (open != null) {
            open.holder(set.id(), new WriteSteps.HolderAt(nested.get().file().toAbsolutePath().normalize(),
                    nested.get().holder()));
        }
        setSetup(nested.get(), setup, expression);
        return Optional.empty();
    }

    /** The setup written in the holder {@code at}; false, and nothing written, when the holder is not there as it was. */
    private boolean setSetup(WriteSteps.HolderAt at, String id, String constant, String setup, JavaValue expression) {
        Optional<String> source = BotSources.sourceOf(config, state, at.file());
        if (source.isEmpty()) return false;
        String written = NestedClasses.setupSetIn(source.get(), id, at.holder(), constant, setup,
                writes.imported(expression), PluginHost.managedValues());
        if (written == null) return false;
        writes.recorded(() -> {
            writes.snapshot();
            BotSources.forEach(config, state, (visited, text) -> visited.equals(at.file()) ? written : null);
        });
        writes.redraw(Set.of(at.file()));
        return true;
    }

    private void setSetup(NestedClasses.Nested nested, String setup, JavaValue expression) {
        Path file = nested.file().toAbsolutePath().normalize();
        writes.recorded(() -> {
            writes.snapshot();
            BotSources.forEach(config, state, (visited, source) -> visited.equals(file)
                    ? NestedClasses.setupSet(source, nested.holder(), nested.constant(), setup,
                    writes.imported(expression)) : null);
        });
        writes.redraw(Set.of(file));
    }

    void setInitializer(ManagedSets.Member member, JavaValue expression) {
        Path file = member.file().toAbsolutePath().normalize();
        // A field of a nested class (ManagedClasses) is in the class whose instance its group holds.
        boolean nested = member.group() != null;
        writes.recorded(() -> {
            writes.snapshot();
            // An edit makes a migrated value the author's: a rerun of the migration leaves it alone from now on.
            BotSources.forEach(config, state, (visited, source) -> !visited.equals(file) ? null
                    : nested ? NestedClasses.fieldSet(source, member.className(), member.group(), member.name(),
                            expression)
                    : ManagedMigrations.unmarked(JavaParameterEdits.setValue(source, member.className(),
                            member.name(), expression), member.className(), member.name()));
        });
        writes.redraw(Set.of(file));
    }

    Optional<String> addGroup(String id, String group, List<String> constants) {
        Optional<ManagedOpenSet<?>> set = DeclaredValues.openSet(PluginHost.managedValues(), id)
                .filter(ManagedOpenSet.EnumGroups.class::isInstance);
        if (set.isEmpty()) return Optional.of(DeclaredValues.notGrouped(id));
        return writes.apply(ManagedSets.addGroup(writes.index(), id, group, constants, set.get().type()));
    }

    Optional<String> renameGroup(ManagedValue<?> set, String group, String newName) {
        if (set instanceof ManagedClasses<?>) {
            BotIndex index = writes.index();
            return NestedClasses.named(index, set.id(), group, PluginHost.grammar(), null)
                    .map(nested -> writes.apply(NestedClasses.rename(index, nested, newName)))
                    .orElseGet(() -> Optional.of(DeclaredValues.missing(set.id(), group)));
        }
        return renameGroup(set.id(), group, newName);
    }

    /** A group of a set of nested enums renamed; {@link WritePerformer#perform}'s, for a set no bound plugin declares. */
    Optional<String> renameGroup(String id, String group, String newName) {
        BotIndex index = writes.index();
        Optional<ManagedSets.Group> found = ManagedSets.group(index, id, group);
        if (found.isEmpty()) return Optional.of(DeclaredValues.missing(id, group));
        return writes.apply(Refactor.rename(index, found.get().file(), found.get().start(), newName));
    }

    Optional<String> removeGroup(ManagedValue<?> set, String group) {
        if (set instanceof ManagedClasses<?>) {
            BotIndex index = writes.index();
            return NestedClasses.named(index, set.id(), group, PluginHost.grammar(), null)
                    .map(nested -> writes.apply(NestedClasses.remove(index, nested)))
                    .orElseGet(() -> Optional.of(DeclaredValues.missing(set.id(), group)));
        }
        return removeGroup(set.id(), group);
    }

    Optional<String> removeGroup(String id, String group) {
        BotIndex index = writes.index();
        Optional<ManagedSets.Group> found = ManagedSets.group(index, id, group);
        if (found.isEmpty()) return Optional.of(DeclaredValues.missing(id, group));
        return writes.apply(ManagedSets.removeGroup(index, found.get()));
    }

    Optional<String> rename(String id, String member, String newName) {
        BotIndex index = writes.index();
        Optional<ManagedSets.Member> found = ManagedSets.member(index, id, member, PluginHost.grammar());
        if (found.isEmpty()) return Optional.of(DeclaredValues.missing(id, member));
        // A nested enum's constant is named "Collect.WON", and its new name may be given the same way; it stays
        // in its enum, so only the part after the group is the new identifier.
        String group = found.get().group();
        String name = group != null && newName != null && newName.startsWith(group + ".")
                ? newName.substring(group.length() + 1) : newName;
        return writes.apply(Refactor.rename(index, found.get().file(), found.get().start(), name));
    }

    Optional<String> repoint(String id, String member, String replacement, String note) {
        BotIndex index = writes.index();
        Optional<ManagedSets.Member> from = ManagedSets.member(index, id, member, PluginHost.grammar());
        Optional<ManagedSets.Member> to = ManagedSets.member(index, id, replacement, PluginHost.grammar());
        if (from.isEmpty()) return Optional.of(DeclaredValues.missing(id, member));
        if (to.isEmpty()) return Optional.of(DeclaredValues.missing(id, replacement));
        return writes.apply(ManagedSets.repoint(index, from.get(), to.get(),
                ReviewMarker.available(state) ? note : null));
    }

    Optional<String> remove(String id, String member) {
        BotIndex index = writes.index();
        Optional<ManagedSets.Member> found = ManagedSets.member(index, id, member, PluginHost.grammar());
        if (found.isEmpty()) return Optional.of(DeclaredValues.missing(id, member));
        return writes.apply(ManagedSets.remove(index, found.get()));
    }

    Optional<String> moveBefore(String id, String member, String before) {
        BotIndex index = writes.index();
        List<ManagedSets.Member> members = ManagedSets.members(index, id, PluginHost.grammar());
        Optional<ManagedSets.Member> moving = members.stream().filter(m -> m.memberName().equals(member)).findFirst();
        Optional<ManagedSets.Member> anchor = members.stream().filter(m -> m.memberName().equals(before)).findFirst();
        if (moving.isEmpty()) return Optional.of(DeclaredValues.missing(id, member));
        if (anchor.isEmpty()) return Optional.of(DeclaredValues.missing(id, before));
        Refactor.Outcome planned = ManagedSets.moveBefore(index, moving.get(), anchor.get());
        // Already above: nothing to write, and no snapshot either.
        if (planned instanceof Refactor.Planned plan && plan.rewrites().isEmpty()) return Optional.empty();
        return writes.apply(planned);
    }
}
