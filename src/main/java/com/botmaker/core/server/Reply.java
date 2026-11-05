package com.botmaker.core.server;

import com.botmaker.core.edit.EditResult;
import com.botmaker.core.event.CoreEvent;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** What the core sends a client over its {@link Channel}: an answer to one of its {@link Request}s, or a change. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "reply")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Reply.Answer.class, name = "answer"),
        @JsonSubTypes.Type(value = Reply.Told.class, name = "told")})
public sealed interface Reply {

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
