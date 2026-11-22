// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.plugin;

import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.project.ProjectWrites;
import com.botmaker.core.project.managed.HolderFiles;
import com.botmaker.core.project.managed.JavaManagedValues;
import com.botmaker.core.project.managed.ManagedHolders;
import com.botmaker.core.project.managed.ManagedIds;
import com.botmaker.core.project.managed.ManagedRepairs;
import com.botmaker.core.project.managed.MisplacedParams;
import com.botmaker.core.project.params.JavaParameters;
import com.botmaker.core.services.BotSources;
import com.botmaker.plugin.api.source.ManagedOpenSet;
import com.botmaker.plugin.api.source.ManagedParam;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.api.source.PluginValues;
import com.botmaker.plugin.host.LoadedPlugin;
import com.botmaker.plugin.host.PluginCoordinate;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Writes the holders of the bound plugins' values: one a window asks for, and on every bind those the bot lacks, with
 * the parameters of a holder it has kept as their plugin declares them.
 */
final class HolderWrites {

    private final ProjectConfig config;
    private final ProjectState state;
    private final BotWrites writes;

    HolderWrites(ProjectConfig config, ProjectState state, BotWrites writes) {
        this.config = config;
        this.state = state;
        this.writes = writes;
    }

    /** {@link HostPluginValues#create(String)}, made on the bot. */
    Optional<String> create(String id) {
        if (JavaManagedValues.find(config, state, id).isPresent()) return Optional.empty();
        for (LoadedPlugin plugin : PluginHost.plugins()) {
            List<ManagedValue<?>> declared;
            try {
                declared = plugin.plugin().managedValues();
            } catch (RuntimeException | LinkageError e) {
                continue;
            }
            if (declared == null) continue;
            declared = declared.stream().filter(Objects::nonNull).toList();
            for (ManagedValue<?> value : declared) {
                if (!id.equals(value.id())) continue;
                if (value.onHost()) return Optional.of(HostValues.notTheBots(value));
                // A method value its holder lacks, or a parameter (2026-10-12: a conditional one, now that its
                // condition holds), is written into the holder the bot has.
                Map<Path, List<ManagedValue<?>>> kept = value instanceof ManagedOpenSet<?>
                        ? Map.of()
                        : ManagedHolders.valuesToKeep(config, writes.index(),
                                Map.of(plugin.coordinate(), List.of(value)));
                if (!kept.isEmpty()) {
                    if (state.isReaderMode()) return Optional.of("This project is open read-only.");
                    return keep(kept).isEmpty() ? Optional.of("BotMaker cannot write a starting value for "
                            + ManagedIds.spelled(id, PluginHost.managedValues()) + ".") : Optional.empty();
                }
                ManagedHolders.Plan plan = ManagedHolders.plan(config, plugin.coordinate(), value, declared,
                        PluginHost.grammar());
                if (plan instanceof ManagedHolders.Plan.Refused refused) return Optional.of(refused.reason());
                ManagedHolders.Plan.Write write = (ManagedHolders.Plan.Write) plan;
                try {
                    writes.create(write.file(), write.source(), "Create " + write.relative());
                } catch (UncheckedIOException e) {
                    return Optional.of("Could not write " + write.relative() + ": " + e.getCause().getMessage());
                }
                return Optional.empty();
            }
        }
        return Optional.of("No plugin in this project declares the value a bot marks "
                + ManagedIds.spelled(id, PluginHost.managedValues()) + ".");
    }

    /** {@link HostPluginValues#createMissing}, for the values {@code values} reads the bot as. */
    List<String> writeMissing(PluginValues values) {
        Map<PluginCoordinate, List<ManagedValue<?>>> byPlugin = new LinkedHashMap<>();
        // A holder is found by every value its plugin declares: one the user moved may carry only the marks of
        // conditional parameters that no longer apply.
        Map<PluginCoordinate, List<ManagedValue<?>>> allByPlugin = new LinkedHashMap<>();
        // A conditional parameter that does not apply to this bot is not written (2026-10-12):
        // ManagedParam.ConditionStep.when, read against the bot as it stands, once for every plugin.
        HostParamScope scope = HostParamScope.over(values);
        for (LoadedPlugin plugin : PluginHost.plugins()) {
            try {
                List<ManagedValue<?>> offered = plugin.plugin().managedValues();
                List<ManagedValue<?>> all = offered == null ? List.of()
                        : offered.stream().filter(Objects::nonNull).toList();
                List<ManagedValue<?>> declared = all.stream()
                        .filter(value -> !(value instanceof ManagedParam<?> param) || param.appliesIn(scope))
                        .toList();
                if (!declared.isEmpty()) byPlugin.put(plugin.coordinate(), declared);
                if (!all.isEmpty()) allByPlugin.put(plugin.coordinate(), all);
            } catch (RuntimeException | LinkageError e) {
                // A plugin that cannot say what it manages gets no file; every other plugin still does.
            }
        }
        List<ManagedValue<?>> everyValue = allByPlugin.values().stream().flatMap(List::stream).toList();
        if (byPlugin.isEmpty()) {
            Set<Path> files = new LinkedHashSet<>(moveMisplaced(allByPlugin));
            files.addAll(repairLeftovers(everyValue));
            return files.stream().map(file -> config.projectPath().relativize(file).toString()).toList();
        }
        Set<HolderFiles.Key> present = writes.index().read(units -> HolderFiles.find(units, allByPlugin)).keySet();
        List<String> written = new ArrayList<>();
        for (ManagedHolders.Plan plan : ManagedHolders.missing(config, byPlugin, Set.copyOf(values.ids()),
                present, PluginHost.grammar())) {
            if (plan instanceof ManagedHolders.Plan.Refused refused) {
                System.err.println("Warning: " + refused.reason());
                continue;
            }
            ManagedHolders.Plan.Write write = (ManagedHolders.Plan.Write) plan;
            try {
                writes.create(write.file(), write.source(), "Create " + write.relative());
                written.add(write.relative());
            } catch (UncheckedIOException e) {
                System.err.println("Warning: could not write " + write.relative() + ": "
                        + e.getCause().getMessage());
            }
        }
        // A plugin's parameter is kept as the plugin declares it (2026-10-11): one the holder lacks goes back in,
        // since the bot's main reads it by name, and a changed category, note or audience is written over the
        // old one. A method value the holder lacks is not: the user may have deleted it, and create() writes it
        // when a plugin window asks for it (2026-10-12).
        Map<PluginCoordinate, List<ManagedValue<?>>> params = new LinkedHashMap<>();
        byPlugin.forEach((plugin, declared) ->
                params.put(plugin, declared.stream().filter(ManagedParam.class::isInstance).toList()));
        keep(ManagedHolders.valuesToKeep(config, writes.index(), params))
                .forEach(file -> written.add(config.projectPath().relativize(file).toString()));
        // A parameter its plugin moved to another holder goes to that group, whole (2026-11-05), whether it
        // applies to this bot or not: it is already written.
        moveMisplaced(allByPlugin).forEach(file -> written.add(config.projectPath().relativize(file).toString()));
        // The old shape beside the new (2026-11-05): a field the group above now holds goes, on every bind.
        repairLeftovers(everyValue).forEach(file -> written.add(config.projectPath().relativize(file).toString()));
        return List.copyOf(written);
    }

    /**
     * Applies {@link ManagedRepairs#leftoverFields} on the live buffers, after one snapshot an undo can return to,
     * and answers the files changed or deleted. What it kept is said on the log, as the bind's other warnings are.
     *
     * <p>A holder the plan empties is deleted only when no editor tab holds it: an open buffer would write the old
     * field back on its next save, so that file stays as it was, its duplicate harmless, until the next bind.
     */
    private Set<Path> repairLeftovers(List<ManagedValue<?>> live) {
        ManagedRepairs.Planned plan = ManagedRepairs.leftoverFields(writes.index(), live);
        plan.left().forEach(sentence -> System.err.println("Warning: " + sentence));
        Set<Path> changed = new LinkedHashSet<>();
        if (plan.isEmpty()) return changed;
        Set<Path> open = writes.openFiles();
        writes.snapshot();
        writes.recorded(() -> BotSources.forEach(config, state, (file, source) -> {
            String after = plan.rewrites().get(file);
            if (after == null || plan.deleted().contains(file)) return null;
            changed.add(file);
            return after;
        }));
        BotWrites.deleteUnlessOpen(plan.deleted(), open, changed);
        plan.repaired().forEach(sentence -> System.err.println("Repaired: " + sentence));
        // A class deleted or a field made a method: the batch's next write reads the bot's types again.
        WriteSteps.Step step = WriteSteps.open();
        if (step != null) step.typesChanged();
        writes.redraw(changed);
        return changed;
    }

    /**
     * Applies {@link MisplacedParams#plan} after one snapshot an undo can return to: each group in {@code params/}
     * on the live buffers, a new group written beside them, each starting copy on disk, and an emptied group pair
     * deleted unless an editor tab holds it. Answers the files changed, created or deleted.
     */
    private Set<Path> moveMisplaced(Map<PluginCoordinate, List<ManagedValue<?>>> byPlugin) {
        MisplacedParams.Moves moves = MisplacedParams.plan(config, writes.index(), byPlugin);
        moves.left().forEach(sentence -> System.err.println("Warning: " + sentence));
        Set<Path> changed = new LinkedHashSet<>();
        if (moves.isEmpty()) return changed;
        // A group an editor tab holds would keep its parameter after the move, a second copy: nothing moves.
        Set<Path> open = writes.openFiles();
        for (Path gone : moves.deleted()) {
            if (open.contains(gone)) {
                System.err.println("Warning: " + gone + " is open in an editor, so its parameters were not moved"
                        + " to their groups; close it and open the bot again.");
                return changed;
            }
        }
        Set<Path> existing = Set.copyOf(writes.index().sources().keySet());
        writes.snapshot();
        // Where the parameter goes is written first: a write that fails leaves it where it was, never nowhere.
        try {
            moves.rewrites().forEach((file, text) -> {
                if (!existing.contains(file)
                        && ProjectWrites.create(config, file, text, BotWrites.HISTORY_LABEL)) {
                    changed.add(file);
                }
            });
            moves.starting().forEach((file, text) -> {
                if (ProjectWrites.replace(config, file, text, BotWrites.HISTORY_LABEL)) changed.add(file);
            });
        } catch (UncheckedIOException e) {
            System.err.println("Warning: parameters were not moved to their groups: " + e.getCause().getMessage());
            writes.redraw(changed);
            return changed;
        }
        writes.recorded(() -> BotSources.forEach(config, state, (file, source) -> {
            String after = moves.rewrites().get(file);
            if (after == null) return null;
            changed.add(file);
            return after;
        }));
        BotWrites.deleteUnlessOpen(moves.deleted(), open, changed);
        moves.moved().forEach(sentence -> System.err.println("Repaired: " + sentence));
        WriteSteps.Step step = WriteSteps.open();
        if (step != null) step.typesChanged();
        writes.redraw(changed);
        return changed;
    }

    /**
     * Writes each file's values as their plugins declare them ({@code ManagedHolders.keptValues}), on the live
     * buffer, after one snapshot an undo can return to, and answers the files changed.
     */
    private Set<Path> keep(Map<Path, List<ManagedValue<?>>> kept) {
        Set<Path> changed = new LinkedHashSet<>();
        if (kept.isEmpty()) return changed;
        writes.recorded(() -> BotSources.forEach(config, state, (file, source) -> {
            List<ManagedValue<?>> values = kept.get(file);
            if (values == null) return null;
            String after = ManagedHolders.keptValues(source, values, PluginHost.grammar());
            if (after.equals(source)) return null;
            if (changed.isEmpty()) writes.snapshot();
            changed.add(file);
            return after;
        }));
        // A parameter added to a group is added to its starting copy too, or the next open's fit drops it.
        kept.forEach((file, values) -> JavaParameters.rewriteStarting(config, file,
                source -> ManagedHolders.keptValues(source, values, PluginHost.grammar())));
        if (!changed.isEmpty()) writes.redraw(changed);
        return changed;
    }
}
