// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.plugin;

import com.botmaker.core.plugin.PluginHost.OwnedEditor;
import com.botmaker.core.plugin.PluginHost.OwnedFilled;
import com.botmaker.core.plugin.PluginHost.OwnedSlot;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.slot.SlotEditor;
import com.botmaker.plugin.api.value.EditableType;
import com.botmaker.plugin.api.value.PluginType;
import com.botmaker.plugin.host.LoadedPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Every slot claimant of one set of plugins, in plugin order: the editors and the {@code @Fills} types a UI draws,
 * each in its plugin's place. Merged once, for the set's whole binding.
 */
final class PluginSlots {

    private final List<OwnedSlot> owned;

    /** The editors alone. */
    private final List<OwnedEditor> ownedEditors;

    /** The same editors without their owner — derived, so the two can never disagree. */
    private final List<SlotEditor> editors;

    PluginSlots(List<LoadedPlugin> set) {
        owned = merge(set);
        ownedEditors = editorsIn(owned);
        editors = strip(ownedEditors);
    }

    /** {@link PluginHost#ownedSlots}. */
    List<OwnedSlot> owned() {
        return owned;
    }

    /** {@link PluginHost#ownedSlotEditors}. */
    List<OwnedEditor> ownedEditors() {
        return ownedEditors;
    }

    /** {@link PluginHost#slotEditors}. */
    List<SlotEditor> editors() {
        return editors;
    }

    private static List<OwnedEditor> editorsIn(List<OwnedSlot> slots) {
        List<OwnedEditor> editors = new ArrayList<>();
        for (OwnedSlot slot : slots) {
            if (slot instanceof OwnedEditor editor) editors.add(editor);
        }
        return List.copyOf(editors);
    }

    private static List<SlotEditor> strip(List<OwnedEditor> owned) {
        List<SlotEditor> editors = new ArrayList<>();
        for (OwnedEditor each : owned) editors.add(each.editor());
        return List.copyOf(editors);
    }

    /**
     * Each plugin's {@link StudioPlugin#slotEditors()} first — the editors chosen by the call, and overrides
     * of somebody else's type, which are narrower matches — and then, in declaration order, one editor per type
     * it declares <em>and draws</em>, an {@link EditableType}, and each {@link FilledType} for the UI to draw. A
     * declared type is named once, in its {@code PluginType}, so this is where it becomes an editor rather than
     * the plugin listing it a second time. A plain {@code PluginType} (2026-09-27) is drawn by another plugin's
     * {@code forType} or the host's fallback.
     */
    private static List<OwnedSlot> merge(List<LoadedPlugin> set) {
        List<OwnedSlot> merged = new ArrayList<>();
        for (LoadedPlugin loaded : set) {
            StudioPlugin plugin = loaded.plugin();
            List<SlotEditor> offered = PluginCalls.quietly(loaded, "offer slot editors", plugin::slotEditors);
            if (offered != null) {
                for (SlotEditor editor : offered) {
                    if (editor != null) merged.add(new OwnedEditor(loaded.coordinate(), loaded.name(), editor));
                }
            }
            List<PluginType<?>> types = PluginCalls.quietly(loaded, "declare types",
                    () -> PluginDeclarations.typesOf(plugin));
            if (types == null) continue;
            for (PluginType<?> type : types) {
                if (type instanceof FilledType<?> filled) {
                    merged.add(new OwnedFilled(loaded.coordinate(), loaded.name(), filled));
                    continue;
                }
                if (!(type instanceof EditableType<?> drawn)) continue;   // a plain type: somebody else draws it
                Class<?> cls = PluginCalls.quietly(loaded, "name a type", drawn::type);
                if (cls == null) continue;
                merged.add(new OwnedEditor(loaded.coordinate(), loaded.name(),
                        SlotEditor.forType(cls).draw(() -> drawn::editor, () -> drawn::preview)));
            }
        }
        return List.copyOf(merged);
    }
}
