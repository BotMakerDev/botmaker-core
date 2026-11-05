package com.botmaker.core.edit;

import java.util.List;

/**
 * Edits of one file, in order, applied as one step: all land or none does.
 *
 * @param file  the file, by its path below the bot's source root ({@code com/mybot/Main.java})
 * @param edits the edits; one edit is a list of one
 */
public record EditRequest(String file, List<Edit> edits) {

    public EditRequest {
        if (file == null || file.isBlank()) throw new IllegalArgumentException("an edit request names its file");
        if (edits == null || edits.isEmpty()) throw new IllegalArgumentException("an edit request needs an edit");
        edits = List.copyOf(edits);
    }
}
