package com.botmaker.core.edit;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/** The core's answer to an {@link EditRequest}: every edit landed, or none did and this is why. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "result")
@JsonSubTypes({
        @JsonSubTypes.Type(value = EditResult.Accepted.class, name = "accepted"),
        @JsonSubTypes.Type(value = EditResult.Refused.class, name = "refused")})
public sealed interface EditResult {

    /** The edits landed; each method they reshaped, as it is now, with the ids its nodes have now. */
    record Accepted(List<ChangedMethod> methods) implements EditResult {
        public Accepted {
            methods = methods == null ? List.of() : List.copyOf(methods);
        }
    }

    /** Nothing landed: the reason, a sentence the client shows as it is. */
    record Refused(String reason) implements EditResult {
        public Refused {
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("a refusal says why");
        }
    }
}
