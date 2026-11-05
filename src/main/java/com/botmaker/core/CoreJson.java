package com.botmaker.core;

import com.botmaker.core.edit.CanvasEdit;
import com.botmaker.core.edit.EditPayloads;
import com.botmaker.core.plugin.PluginWrite;
import com.botmaker.java.edit.ExpressionChoice;
import com.botmaker.java.edit.UseFix;
import com.botmaker.java.edit.palette.BlockType;
import com.botmaker.java.edit.palette.ExpressionType;
import com.botmaker.java.edit.palette.Initializer;
import com.botmaker.java.edit.palette.SignatureType;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;

import java.util.List;

/**
 * The one JSON mapper of the core's protocol: every {@code Edit}, {@code EditRequest}, {@code EditResult} and
 * {@code CoreEvent} is written and read with it, and the channel of the plan's phase 10 carries exactly this text.
 *
 * <p>A client and its core are the same version, so an unknown field is an error, not something to skip.
 */
public final class CoreJson {

    /** Strict on reading: a missing component, a null where an int goes or text after the message is refused. */
    private static final ObjectMapper MAPPER = mapper();

    /** The closed sets {@code botmaker-java} defines, which holds no Jackson: each kind named here, by its class. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    private interface Kinded {
    }

    private CoreJson() {
    }

    private static ObjectMapper mapper() {
        JsonMapper.Builder builder = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .addModule(EditPayloads.module());
        List<Class<?>> kinded = List.of(BlockType.class, Initializer.class, ExpressionType.class, ExpressionChoice.class,
                UseFix.class, SignatureType.class);
        kinded.forEach(type -> builder.addMixIn(type, Kinded.class));
        ObjectMapper mapper = builder.build();
        kinded.forEach(type -> register(mapper, type));
        register(mapper, CanvasEdit.class);
        register(mapper, PluginWrite.class);
        return mapper;
    }

    private static void register(ObjectMapper mapper, Class<?> sealed) {
        for (Class<?> kind : sealed.getPermittedSubclasses()) {
            String name = kind.getSimpleName();
            mapper.registerSubtypes(new NamedType(kind, Character.toLowerCase(name.charAt(0)) + name.substring(1)));
        }
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
