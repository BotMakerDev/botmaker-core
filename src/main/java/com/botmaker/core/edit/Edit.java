package com.botmaker.core.edit;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One edit a client asks the core for, in the shape the assistant's {@code edit_code} takes. A node is addressed by
 * its {@code BlockId} path, as the client read it from the core's last {@code MethodsChanged}; the core reads each id
 * against the tree the edit runs on and refuses one that tree no longer has.
 *
 * <p>These carry no Java. A slot takes a {@link SlotValue}, which the slot's type reads; a block comes from the
 * palette by its id. An {@link EditRequest} carries edits in order, applied as one step undo takes back; undo and
 * redo themselves are a {@link HistoryStep}, the project's, not an edit of one file. What only the user does, by hand
 * and sometimes with Java they typed, is a {@link CanvasEdit}, which an assistant's request cannot carry.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "edit")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Edit.Insert.class, name = "insert"),
        @JsonSubTypes.Type(value = Edit.SetSlot.class, name = "setSlot"),
        @JsonSubTypes.Type(value = Edit.Move.class, name = "move"),
        @JsonSubTypes.Type(value = Edit.Delete.class, name = "delete"),
        @JsonSubTypes.Type(value = Edit.Rename.class, name = "rename")})
public sealed interface Edit permits Edit.Insert, Edit.SetSlot, Edit.Move, Edit.Delete, Edit.Rename, CanvasEdit {

    /** A palette entry inserted into a body, before the statement now at {@code index} (0 for first). */
    record Insert(String bodyId, int index, String paletteId) implements Edit {
        public Insert {
            requireId(bodyId, "a body");
            requireIndex(index);
            requireId(paletteId, "a palette entry");
        }
    }

    /** A value written into a slot. */
    record SetSlot(String slotId, SlotValue value) implements Edit {
        public SetSlot {
            requireId(slotId, "a slot");
            if (value == null) throw new IllegalArgumentException("a slot's edit needs a value");
        }
    }

    /**
     * A statement moved into a body, before the statement at {@code index} of that body as it is before the move, the
     * moved statement still counted: within one body, its own index or the next one leaves it where it is.
     */
    record Move(String blockId, String bodyId, int index) implements Edit {
        public Move {
            requireId(blockId, "a statement");
            requireId(bodyId, "a body");
            requireIndex(index);
        }
    }

    /** A statement deleted. */
    record Delete(String blockId) implements Edit {
        public Delete {
            requireId(blockId, "a statement");
        }
    }

    /** A symbol the node declares or names, renamed with every use of it. */
    record Rename(String symbolId, String name) implements Edit {
        public Rename {
            requireId(symbolId, "a symbol");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("a rename needs a new name");
        }
    }

    private static void requireId(String id, String what) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException(what + "'s id is missing");
    }

    private static void requireIndex(int index) {
        if (index < 0) throw new IllegalArgumentException("an index below 0: " + index);
    }
}
