package com.botmaker.core.edit;

import java.util.List;

/**
 * Edits of one file, in order, applied as one step: all land or none does.
 *
 * @param file  the file, by its path below the bot's source root ({@code com/mybot/Main.java})
 * @param edits the edits; one edit is a list of one
 * @param guard how far the compiler judges them
 */
public record EditRequest(String file, List<Edit> edits, Guard guard) {

    public EditRequest {
        if (file == null || file.isBlank()) throw new IllegalArgumentException("an edit request names its file");
        if (edits == null || edits.isEmpty()) throw new IllegalArgumentException("an edit request needs an edit");
        edits = List.copyOf(edits);
        if (guard == null) throw new IllegalArgumentException("an edit request names its guard");
    }

    /** A request whose every edit must compile: what a client that writes for the user sends. */
    public EditRequest(String file, List<Edit> edits) {
        this(file, edits, Guard.EVERY_EDIT);
    }

    /** How far the compiler judges a request's edits. */
    public enum Guard {
        /** Any edit that adds a compile error is refused: an assistant cannot be asked what it meant. */
        EVERY_EDIT,
        /**
         * The editor's own rules only: what writes Java the user did not type (an insert) is refused for a new error,
         * what the user did by hand (a move, a delete) lands and the error is shown for them to fix.
         */
        EDITOR_RULES
    }
}
