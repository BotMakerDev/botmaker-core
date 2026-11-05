package com.botmaker.core.edit;

import com.botmaker.core.plugin.PluginHost;
import com.botmaker.java.edit.palette.BotType;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.grammar.ValueTypes;
import com.botmaker.java.types.ResolvedType;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.type.TypeFactory;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * How the values a {@link CanvasEdit} carries cross as JSON, none of which can be a plain record on the wire. A type
 * crosses by name, since a binding belongs to one parse; a value as its source and the classes it names, since a tree
 * belongs to one parse too. {@code botmaker-java} holds no Jackson, so this is here.
 *
 * <p>A name read back is a {@link ResolvedType.Named} or a primitive, however rich the type that was written.
 */
public final class EditPayloads {

    private EditPayloads() {
    }

    /** The module {@code CoreJson} installs. */
    public static SimpleModule module() {
        SimpleModule module = new SimpleModule("edit-payloads");
        // A record is its components: a default isStatement() is no field of it.
        module.setSerializerModifier(new BeanSerializerModifier() {
            @Override
            public List<BeanPropertyWriter> changeProperties(SerializationConfig config, BeanDescription description,
                                                             List<BeanPropertyWriter> properties) {
                RecordComponent[] components = description.getBeanClass().getRecordComponents();
                if (components != null) {
                    Set<String> names = Arrays.stream(components).map(RecordComponent::getName).collect(Collectors.toSet());
                    properties.removeIf(property -> !names.contains(property.getName()));
                }
                return properties;
            }
        });
        module.addSerializer(ResolvedType.class, new JsonSerializer<>() {
            @Override
            public void serialize(ResolvedType type, JsonGenerator out, SerializerProvider ignored) throws IOException {
                out.writeString(type.qualifiedName());
            }
        });
        module.addDeserializer(ResolvedType.class, new JsonDeserializer<>() {
            @Override
            public ResolvedType deserialize(JsonParser in, DeserializationContext ignored) throws IOException {
                return ResolvedType.named(in.getValueAsString());
            }
        });
        module.addSerializer(JavaValue.class, new JsonSerializer<>() {
            @Override
            public void serialize(JavaValue value, JsonGenerator out, SerializerProvider ignored) throws IOException {
                out.writeStartObject();
                out.writeStringField("source", value.source());
                out.writeArrayFieldStart("imports");
                for (String each : value.imports()) out.writeString(each);
                out.writeEndArray();
                out.writeEndObject();
            }
        });
        module.addDeserializer(JavaValue.class, new JsonDeserializer<>() {
            @Override
            public JavaValue deserialize(JsonParser in, DeserializationContext context) throws IOException {
                JsonNode tree = context.readTree(in);
                String source = tree.get("source").asText();
                JavaValue parsed = JavaValue.parse(source)
                        .orElseThrow(() -> new IllegalArgumentException("not one expression: " + source));
                List<String> imports = new ArrayList<>();
                tree.get("imports").forEach(each -> imports.add(each.asText()));
                return new JavaValue(parsed.node(), imports, source);
            }
        });
        module.addSerializer(BotType.Choice.class, new JsonSerializer<>() {
            @Override
            public void serialize(BotType.Choice choice, JsonGenerator out, SerializerProvider ignored) throws IOException {
                out.writeString(choice.sourceName());
            }
        });
        module.addDeserializer(BotType.Choice.class, new JsonDeserializer<>() {
            @Override
            public BotType.Choice deserialize(JsonParser in, DeserializationContext ignored) throws IOException {
                String name = in.getValueAsString();
                return BotType.Choice.fromSourceName(name, PluginHost.grammar())
                        .orElseThrow(() -> new IllegalArgumentException("no such offered type: " + name));
            }
        });
        module.addSerializer(Type.class, new JsonSerializer<>() {
            @Override
            public void serialize(Type type, JsonGenerator out, SerializerProvider ignored) throws IOException {
                writeType(type, out);
            }
        });
        module.addDeserializer(Type.class, new JsonDeserializer<>() {
            @Override
            public Type deserialize(JsonParser in, DeserializationContext context) throws IOException {
                return readType(context.readTree(in), context.getTypeFactory());
            }
        });
        return module;
    }

    private static void writeType(Type type, JsonGenerator out) throws IOException {
        switch (type) {
            case Class<?> plain -> out.writeString(plain.getName());
            case ValueTypes.Unknown unknown -> {
                out.writeStartObject();
                out.writeStringField("written", unknown.written());
                out.writeEndObject();
            }
            case ValueTypes.BotClass bot -> {
                out.writeStartObject();
                out.writeStringField("bot", bot.qualifiedName());
                writeArguments(bot.arguments(), out);
                out.writeEndObject();
            }
            case ParameterizedType parameterized -> {
                out.writeStartObject();
                out.writeStringField("raw", ((Class<?>) parameterized.getRawType()).getName());
                writeArguments(List.of(parameterized.getActualTypeArguments()), out);
                out.writeEndObject();
            }
            default -> throw new IllegalArgumentException("a type with no wire form: " + type);
        }
    }

    private static void writeArguments(List<Type> arguments, JsonGenerator out) throws IOException {
        out.writeArrayFieldStart("arguments");
        for (Type each : arguments) writeType(each, out);
        out.writeEndArray();
    }

    private static Type readType(JsonNode tree, TypeFactory types) {
        if (tree.isTextual()) return classNamed(tree.asText(), types);
        if (tree.has("written")) return new ValueTypes.Unknown(tree.get("written").asText());
        List<Type> arguments = new ArrayList<>();
        tree.get("arguments").forEach(each -> arguments.add(readType(each, types)));
        if (tree.has("bot")) return new ValueTypes.BotClass(tree.get("bot").asText(), arguments);
        return new ValueTypes.Parameterized(classNamed(tree.get("raw").asText(), types), arguments);
    }

    private static Class<?> classNamed(String name, TypeFactory types) {
        try {
            return types.findClass(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("no such class here: " + name, e);
        }
    }
}
