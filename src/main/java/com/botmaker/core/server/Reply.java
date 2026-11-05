package com.botmaker.core.server;

import com.botmaker.core.edit.EditResult;
import com.botmaker.core.event.CoreEvent;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** What the core sends a client over its {@link Channel}: an answer to one of its {@link Request}s, or a change. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "reply")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Reply.Answer.class, name = "answer"),
        @JsonSubTypes.Type(value = Reply.Told.class, name = "told"),
        @JsonSubTypes.Type(value = Reply.Call.class, name = "call")})
public sealed interface Reply {

    /**
     * The core asks the client that registered {@code tool} ({@link Request.Tools}) to run it with {@code arguments}, an
     * MCP call's arguments as one JSON object, or to {@link Kind#ADMIT} a call of the core's own tool {@code tool}: the
     * client says whether what it has on screen lets that call run (plan phase 10d). The client answers with
     * {@link Request.Called} of the same {@code call}, carrying an MCP {@code CallToolResult}.
     */
    record Call(long call, Kind kind, String tool, String arguments) implements Reply {
        public Call {
            if (kind == null || tool == null || tool.isBlank()) throw new IllegalArgumentException("a call names its tool");
            arguments = arguments == null || arguments.isBlank() ? "{}" : arguments;
        }

        public enum Kind {
            /** Run the client's own tool; its result is the call's answer. */
            RUN,
            /**
             * May the core's own tool run now? A result that is an error is the refusal to answer with; any other is
             * what to add after the tool's own answer (how the calls that asked on screen ended).
             */
            ADMIT
        }
    }

    /**
     * The answer to the request {@code id}: {@link EditResult.Accepted} when it was done (with the methods an edit
     * reshaped, none for the rest), else {@link EditResult.Refused} with the sentence to show.
     */
    record Answer(long id, EditResult result) implements Reply {
        public Answer {
            if (result == null) throw new IllegalArgumentException("an answer carries its result");
        }
    }

    /**
     * A change of the project, told to every client attached to it, whoever caused it. A client hears the change an
     * ask of its own made before that ask's answer.
     */
    record Told(CoreEvent event) implements Reply {
        public Told {
            if (event == null) throw new IllegalArgumentException("a change carries its event");
        }
    }
}
