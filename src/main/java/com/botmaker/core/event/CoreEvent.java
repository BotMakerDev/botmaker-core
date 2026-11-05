package com.botmaker.core.event;

import com.botmaker.core.edit.ChangedMethod;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * What the core tells every client attached to a project, whoever caused it: Studio redraws a method another client
 * edited exactly as one it edited itself. A file is named by its path below the bot's source root.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "event")
@JsonSubTypes({
        @JsonSubTypes.Type(value = CoreEvent.MethodsChanged.class, name = "methodsChanged"),
        @JsonSubTypes.Type(value = CoreEvent.HistoryChanged.class, name = "historyChanged"),
        @JsonSubTypes.Type(value = CoreEvent.Diagnostics.class, name = "diagnostics"),
        @JsonSubTypes.Type(value = CoreEvent.RunState.class, name = "runState"),
        @JsonSubTypes.Type(value = CoreEvent.RunOutput.class, name = "runOutput"),
        @JsonSubTypes.Type(value = CoreEvent.UiRequest.class, name = "uiRequest"),
        @JsonSubTypes.Type(value = CoreEvent.Refusal.class, name = "refusal")})
public sealed interface CoreEvent {

    /**
     * An accepted edit, an undo or a change on disk reshaped methods of one file. A method's id is its place in the
     * file, so adding or removing one renumbers those after it: {@code methods} holds every method whose source or
     * id changed, renumbered ones too, and {@code removed} the ids the file no longer has.
     *
     * <p>{@code source} is the whole file after the change, which is what a client draws from: a file's imports, fields
     * and class headers are in no method, and a client parses one text rather than splicing methods into its copy
     * (2026-11-05, the user's call). A client keeps whatever it drew of a method not listed. {@code newNamesUnchosen}
     * says the names the change brought are the editor's guesses, shown as not yet chosen by the user.
     */
    record MethodsChanged(String file, List<ChangedMethod> methods, List<String> removed, String source,
                          boolean newNamesUnchosen) implements CoreEvent {
        public MethodsChanged {
            requireFile(file);
            methods = methods == null ? List.of() : List.copyOf(methods);
            removed = removed == null ? List.of() : List.copyOf(removed);
            if (source == null) throw new IllegalArgumentException("a change carries the file's source");
        }
    }

    /**
     * The project's history moved: an edit was recorded, or a step was undone or redone. The labels are the steps in
     * the words an Undo menu shows, {@code ""} when there is none.
     */
    record HistoryChanged(boolean canUndo, boolean canRedo, String undoLabel, String redoLabel) implements CoreEvent {
        public HistoryChanged {
            undoLabel = undoLabel == null ? "" : undoLabel;
            redoLabel = redoLabel == null ? "" : redoLabel;
        }
    }

    /** The compiler's problems in one file, all of them: an empty list clears the file's marks. */
    record Diagnostics(String file, List<Problem> problems) implements CoreEvent {
        public Diagnostics {
            requireFile(file);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        /** One problem, on the node it names ({@code ""} when it sits on no node) and its 1-based line. */
        public record Problem(String nodeId, Severity severity, int line, String message) {
            public Problem {
                nodeId = nodeId == null ? "" : nodeId;
                if (severity == null) throw new IllegalArgumentException("a problem has a severity");
                if (message == null) throw new IllegalArgumentException("a problem has a message");
            }
        }

        public enum Severity { ERROR, WARNING }
    }

    /** Where the bot's run is; {@code detail} says why it ended, or is empty. */
    record RunState(Phase phase, String detail) implements CoreEvent {
        public RunState {
            if (phase == null) throw new IllegalArgumentException("a run state has a phase");
            detail = detail == null ? "" : detail;
        }

        public enum Phase { COMPILING, RUNNING, PAUSED, ENDED }
    }

    /** Text the running bot printed, in the order it printed it. */
    record RunOutput(Stream stream, String text) implements CoreEvent {
        public RunOutput {
            if (stream == null) throw new IllegalArgumentException("run output names its stream");
            if (text == null) throw new IllegalArgumentException("run output has text");
        }

        public enum Stream { OUT, ERR }
    }

    /**
     * Something running in the core asks for a window or a dialog, which only an attached client can show. The client
     * answers by {@code requestId}; the plan's phase 8 gives the port its answers.
     */
    record UiRequest(String requestId, Kind kind, String title) implements CoreEvent {
        public UiRequest {
            if (requestId == null || requestId.isEmpty()) throw new IllegalArgumentException("a UI request has an id");
            if (kind == null) throw new IllegalArgumentException("a UI request has a kind");
            title = title == null ? "" : title;
        }

        public enum Kind { DIALOG, WINDOW }
    }

    /** An edit to this file was refused: told to every client, so one that did not ask still sees why. */
    record Refusal(String file, String reason) implements CoreEvent {
        public Refusal {
            requireFile(file);
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("a refusal says why");
        }
    }

    private static void requireFile(String file) {
        if (file == null || file.isBlank()) throw new IllegalArgumentException("an event names its file");
    }
}
