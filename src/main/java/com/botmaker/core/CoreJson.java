package com.botmaker.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * The one JSON mapper of the core's protocol: every {@code Edit}, {@code EditRequest}, {@code EditResult} and
 * {@code CoreEvent} is written and read with it, and the channel of the plan's phase 10 carries exactly this text.
 *
 * <p>A client and its core are the same version, so an unknown field is an error, not something to skip.
 */
public final class CoreJson {

    /** Strict on reading: a missing component, a null where an int goes or text after the message is refused. */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private CoreJson() {
    }

    /** The JSON of one protocol record. */
    public static String write(Object message) {
        try {
            return MAPPER.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not a protocol record: " + message.getClass().getName(), e);
        }
    }

    /** The record {@code json} holds, as {@code type}; a message that is not one throws, saying why. */
    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not a " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }
}
