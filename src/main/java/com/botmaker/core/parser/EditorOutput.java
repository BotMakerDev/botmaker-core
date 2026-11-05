package com.botmaker.core.parser;

import com.botmaker.core.events.FileEdit;
import com.botmaker.java.edit.palette.BlockType;

import java.util.List;

/**
 * What {@link CodeEditor} tells whoever drives it: an edit that landed, a sentence for the user, a block added. The
 * editor names no client; Studio turns each into its own events, a headless client may log them.
 */
public interface EditorOutput {

    /** A sentence for the user: why an edit was refused, or what a wider change did. */
    void status(String message);

    /** An edit that landed in the open file: the buffer becomes {@code write.newCode()}. */
    void written(Write write);

    /** A palette block that was added, after its {@link #written} landed. */
    void blockAdded(BlockType type);

    /**
     * One edit of the open file.
     *
     * @param markNewIdentifiersAsUnedited whether names the edit introduced are shown as not yet chosen by the user
     * @param label                        the edit, in the words the Undo menu shows
     * @param alsoChanged                  every other file the same edit wrote, so history takes them back together
     */
    record Write(String newCode, String previousCode, boolean markNewIdentifiersAsUnedited, String label,
                 List<FileEdit> alsoChanged) {
        public Write {
            alsoChanged = alsoChanged == null ? List.of() : List.copyOf(alsoChanged);
        }
    }
}
