package com.botmaker.core.server;

import com.botmaker.core.edit.Edit;
import com.botmaker.core.edit.EditRequest;
import com.botmaker.core.edit.HistoryStep;
import com.botmaker.core.events.FileEdit;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * What a client asks the core over its {@link Channel}, one message each, written by {@code CoreJson}. The core answers
 * each with a {@link Reply.Answer} of the same {@link #id}, in the order asked; what follows from it reaches every client
 * attached to the project as {@link Reply.Told}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "ask")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Request.Open.class, name = "open"),
        @JsonSubTypes.Type(value = Request.Show.class, name = "show"),
        @JsonSubTypes.Type(value = Request.Apply.class, name = "apply"),
        @JsonSubTypes.Type(value = Request.UserEdit.class, name = "userEdit"),
        @JsonSubTypes.Type(value = Request.Step.class, name = "step"),
        @JsonSubTypes.Type(value = Request.Commit.class, name = "commit"),
        @JsonSubTypes.Type(value = Request.Rewritten.class, name = "rewritten"),
        @JsonSubTypes.Type(value = Request.Recorded.class, name = "recorded"),
        @JsonSubTypes.Type(value = Request.Reload.class, name = "reload"),
        @JsonSubTypes.Type(value = Request.Close.class, name = "close")})
public sealed interface Request {

    /** Which ask this is; the answer carries it back. */
    long id();

    /** Attaches the client to the project in {@code project}, an absolute directory, opening it when no client has. */
    record Open(long id, String project) implements Request {
        public Open {
            if (project == null || project.isBlank()) throw new IllegalArgumentException("an open names its project");
        }
    }

    /** Makes {@code file}, by its path below the bot's source root, the project's open file. */
    record Show(long id, String file) implements Request {
        public Show {
            if (file == null || file.isBlank()) throw new IllegalArgumentException("a show names its file");
        }
    }

    /** An assistant's or a tool's edits, judged by the request's guard. */
    record Apply(long id, EditRequest request) implements Request {
        public Apply {
            if (request == null) throw new IllegalArgumentException("an apply carries its request");
        }
    }

    /** One edit the user made by hand on the open file. */
    record UserEdit(long id, Edit edit) implements Request {
        public UserEdit {
            if (edit == null) throw new IllegalArgumentException("a user's edit carries its edit");
        }
    }

    /** An undo or a redo of the project's history; refused when there is no step that way. */
    record Step(long id, HistoryStep step) implements Request {
        public Step {
            if (step == null) throw new IllegalArgumentException("a step says which way");
        }
    }

    /**
     * An assistant's finished session, whole: the file's text as the session started from it and as it ended. It lands
     * only while the open file still reads {@code original}. Java text from a client, until the plan's phase 10d runs
     * the assistant's session in the core and this goes (2026-11-05, the user's call).
     */
    record Commit(long id, String file, String original, String source, String label) implements Request {
        public Commit {
            if (file == null || file.isBlank()) throw new IllegalArgumentException("a commit names its file");
            if (original == null || source == null) throw new IllegalArgumentException("a commit carries both texts");
        }
    }

    /**
     * A write of the open file a client made outside an edit (an open-time migration), landed as one step while the
     * file still reads {@code original}. Java text from a client, as {@link Commit} is (2026-11-05, plan phase 10a1).
     */
    record Rewritten(long id, String file, String original, String source) implements Request {
        public Rewritten {
            if (file == null || file.isBlank()) throw new IllegalArgumentException("a rewrite names its file");
            if (original == null || source == null) throw new IllegalArgumentException("a rewrite carries both texts");
        }
    }

    /**
     * What a client's plugin already wrote, buffer and disk, recorded as one step named {@code label}; the core takes
     * the new texts as its own. Java text from a client until the plan's phase 10c runs a plugin's writes in the core.
     */
    record Recorded(long id, String label, List<FileEdit> edits) implements Request {
        public Recorded {
            if (edits == null) throw new IllegalArgumentException("a record carries its writes");
            edits = List.copyOf(edits);
        }
    }

    /** The project's libraries changed: the core resolves them again and binds their plugins again. */
    record Reload(long id) implements Request {
    }

    /** Detaches the client from its project; the last client to leave closes it. */
    record Close(long id) implements Request {
    }
}
