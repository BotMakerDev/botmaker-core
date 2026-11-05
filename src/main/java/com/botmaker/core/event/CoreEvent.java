package com.botmaker.core.event;

import com.botmaker.core.edit.ChangedMethod;
import com.botmaker.core.run.debug.DebugSnapshot;
import com.botmaker.plugin.api.TraceLine;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

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
        @JsonSubTypes.Type(value = CoreEvent.RunLogged.class, name = "runLogged"),
        @JsonSubTypes.Type(value = CoreEvent.RunTraced.class, name = "runTraced"),
        @JsonSubTypes.Type(value = CoreEvent.RunFrame.class, name = "runFrame"),
        @JsonSubTypes.Type(value = CoreEvent.RunProcess.class, name = "runProcess"),
        @JsonSubTypes.Type(value = CoreEvent.DebugSession.class, name = "debugSession"),
        @JsonSubTypes.Type(value = CoreEvent.DebugPaused.class, name = "debugPaused"),
        @JsonSubTypes.Type(value = CoreEvent.DebugFollowed.class, name = "debugFollowed"),
        @JsonSubTypes.Type(value = CoreEvent.SourcesChanged.class, name = "sourcesChanged"),
        @JsonSubTypes.Type(value = CoreEvent.InputAsked.class, name = "inputAsked"),
        @JsonSubTypes.Type(value = CoreEvent.Status.class, name = "status"),
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

    /**
     * The problems in one file, all of them: an empty list clears the file's marks. A run's check tells them before it
     * compiles (since plan phase 7a): a slot left empty, a deprecated call, a {@code main} that never starts the bot.
     */
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

        /** {@code INFO} is advice that stops nothing, such as a {@code main} that never starts the bot. */
        public enum Severity { ERROR, WARNING, INFO }
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
     * The bot's process ended after a run (not a try or a debug session), after its {@link RunState} ended: its run
     * folder is written and its problems can be reported. {@code folder} is that folder's absolute path, empty when it
     * kept no log.
     */
    record RunLogged(String folder) implements CoreEvent {
        public RunLogged {
            folder = folder == null ? "" : folder;
        }
    }

    /**
     * One line of the running bot's trace, the contract's {@link TraceLine} in plain parts: {@code level} is its
     * level's id, {@code atLine} its source line ({@code -1} when the bot did not know), {@code where} the part of the
     * screen it is about, or null.
     */
    record RunTraced(long atMillis, String level, String source, String text, int count, String writerClass,
                     String writerMethod, String className, int atLine, Region where,
                     Map<String, String> values) implements CoreEvent {

        /** A part of the screen, in desktop pixels. */
        public record Region(int x, int y, int width, int height) {
        }

        public static RunTraced of(TraceLine line) {
            return new RunTraced(line.at().toEpochMilli(), line.level().id(), line.source(), line.text(), line.count(),
                    line.writerClass(), line.writerMethod(), line.className(), line.line().orElse(-1),
                    line.where().map(at -> new Region(at.x(), at.y(), at.width(), at.height())).orElse(null),
                    line.values());
        }

        /** The contract's line this event carries. */
        public TraceLine traceLine() {
            return new TraceLine(Instant.ofEpochMilli(atMillis), TraceLine.Level.fromId(level), source, text, count,
                    writerClass, writerMethod, className, atLine > 0 ? OptionalInt.of(atLine) : OptionalInt.empty(),
                    where == null ? Optional.empty()
                            : Optional.of(new TraceLine.Region(where.x(), where.y(), where.width(), where.height())),
                    values);
        }
    }

    /** A telemetry frame the core does not read, as the bytes the bot wrote: what a plugin's listener decodes. */
    record RunFrame(byte[] payload) implements CoreEvent {
        public RunFrame {
            if (payload == null) throw new IllegalArgumentException("a frame has its bytes");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RunFrame frame && Arrays.equals(payload, frame.payload);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(payload);
        }
    }

    /** The bot's process started as {@code pid}, what a client's pause signals; it is gone at the run's end. */
    record RunProcess(long pid) implements CoreEvent {
    }

    /** A debug session started, ran on after a pause, or finished. */
    record DebugSession(Phase phase) implements CoreEvent {
        public DebugSession {
            if (phase == null) throw new IllegalArgumentException("a debug session has a phase");
        }

        public enum Phase { STARTED, RESUMED, FINISHED }
    }

    /**
     * The debuggee stopped at {@code line} of {@code file}, or outside the bot's own code when {@code file} is empty,
     * its frames read in {@code snapshot}.
     */
    record DebugPaused(String file, int line, DebugSnapshot snapshot) implements CoreEvent {
        public DebugPaused {
            file = file == null ? "" : file;
            if (snapshot == null) throw new IllegalArgumentException("a pause has its snapshot");
        }
    }

    /** One window of Follow: the line the bot was last on, every line it ran, and whether it came round to one again. */
    record DebugFollowed(Place latest, List<Place> hits, boolean looping) implements CoreEvent {
        public DebugFollowed {
            if (latest == null) throw new IllegalArgumentException("a window of Follow saw a line");
            hits = hits == null ? List.of() : List.copyOf(hits);
        }

        /** A line of one of the bot's files. */
        public record Place(String file, int line) {
        }
    }

    /**
     * Files other than the open one changed on disk, by another's write: read them there. The names go without their
     * text: a parameter's value stays on disk, and a client reads what is there by the time it looks.
     */
    record SourcesChanged(List<String> files) implements CoreEvent {
        public SourcesChanged {
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    /**
     * The running bot asks its user a question ({@code Ask.choice(…)}); a client answers {@code id}. {@code kind} is a
     * {@code TelemetryEvent.Ask.Kind} id and stays a string, as on the bot's wire, so a kind a newer bot adds still
     * reads; a client asks one it does not know as plain text. {@code line} is the bot's source line asking, {@code -1}
     * when unknown.
     */
    record InputAsked(long id, String kind, String prompt, List<String> choices, int line) implements CoreEvent {
        public InputAsked {
            kind = kind == null ? "" : kind;
            prompt = prompt == null ? "" : prompt;
            choices = choices == null ? List.of() : List.copyOf(choices);
        }
    }

    /** A line for the user's status bar: what a compile, a run or a debug session is doing now. */
    record Status(String message) implements CoreEvent {
        public Status {
            if (message == null) throw new IllegalArgumentException("a status has a message");
        }
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
