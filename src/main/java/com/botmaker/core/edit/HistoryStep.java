package com.botmaker.core.edit;

/**
 * A step through the project's one history, which every client shares: undo takes back the last accepted step,
 * whoever made it and in whichever file, and redo makes it again. It is sent on its own, never inside an
 * {@link EditRequest}, because a request is one file's and the history is the project's.
 */
public enum HistoryStep {
    UNDO,
    REDO
}
