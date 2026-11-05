package com.botmaker.core.plugin;

import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.index.nav.Refactor;
import com.botmaker.plugin.api.source.ManagedClasses;
import com.botmaker.plugin.api.source.ParameterWrite;
import com.botmaker.plugin.api.source.PluginValues;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.lang.annotation.Annotation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * One write a plugin asked of the bot's values, as it crosses from a client's copy of the plugin to the core, which
 * alone writes the bot ({@link HostPluginValues#perform}). A value is named by its {@code ManagedValue} id, as both
 * copies of the plugin declare it; what a plugin hands over crosses as a {@link JavaValue}, which reads back only as one
 * expression, and everything else the core plans again from its own tree. {@code CoreJson} registers each record.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "write")
public sealed interface PluginWrite {

    /** A method value's expression. */
    record SetValue(String id, JavaValue value) implements PluginWrite {
    }

    /** An open set's constant's initializer. */
    record SetMember(String id, String member, JavaValue value) implements PluginWrite {
    }

    /** What the setup of a class of a set of classes returns. */
    record SetSetup(String id, String constant, JavaValue value) implements PluginWrite {
    }

    /** A value's holder, or the value in the holder the bot has. */
    record Create(String id) implements PluginWrite {
    }

    /** A class joining a set of classes, with its setup. */
    record AddClass(String id, String member, JavaValue setup) implements PluginWrite {
    }

    /** An enum's constant, which is its name alone. */
    record AddConstant(String id, String member) implements PluginWrite {
    }

    /** A constant joining an open set; {@code type} is the binary name of its class. */
    record Add(String id, String member, String type, JavaValue value) implements PluginWrite {
    }

    record Rename(String id, String member, String newName) implements PluginWrite {
    }

    record Repoint(String id, String member, String replacement, String note) implements PluginWrite {
    }

    record Remove(String id, String member) implements PluginWrite {
    }

    record MoveBefore(String id, String member, String before) implements PluginWrite {
    }

    record AddGroup(String id, String group, List<String> constants) implements PluginWrite {
    }

    record RenameGroup(String id, String group, String newName) implements PluginWrite {
    }

    record RemoveGroup(String id, String group) implements PluginWrite {
    }

    /** A method returning {@code group} of the set {@code id}; both null for a void one. */
    record CreateMethod(String id, String group, String className, String method) implements PluginWrite {
    }

    /** A method returning a boolean. */
    record CreateBooleanMethod(String className, String method) implements PluginWrite {
    }

    /** One call of {@code owner.name}, a canonical class and a public static method, appended to a bot's method. */
    record AppendCall(String method, String owner, String name, List<JavaValue> arguments) implements PluginWrite {
    }

    /** A during-a-run parameter; {@code start} is a String, an int or a boolean, as a {@link ParameterWrite} takes. */
    record AddRunParameter(String group, String name, Object start, List<Mark> marks, List<Mark> groupMarks)
            implements PluginWrite {

        /** One annotation of a {@link ParameterWrite}, its members by the kind of value each holds. */
        public record Mark(String annotation, Map<String, String> texts, Map<String, Integer> numbers,
                           Map<String, ClassOf> classes) {
        }

        /** The class of the constant {@code constant} of the set of classes {@code id}. */
        public record ClassOf(String id, String constant) {
        }

        static AddRunParameter of(ParameterWrite write) {
            return new AddRunParameter(write.group(), write.name(), write.start(), marks(write.marks()),
                    marks(write.groupMarks()));
        }

        private static List<Mark> marks(List<ParameterWrite.Mark> marks) {
            List<Mark> crossing = new ArrayList<>();
            for (ParameterWrite.Mark mark : marks) {
                Map<String, String> texts = new LinkedHashMap<>();
                Map<String, Integer> numbers = new LinkedHashMap<>();
                Map<String, ClassOf> classes = new LinkedHashMap<>();
                mark.members().forEach((member, value) -> {
                    switch (value) {
                        case String text -> texts.put(member, text);
                        case Integer number -> numbers.put(member, number);
                        case ParameterWrite.ClassOf classOf -> classes.put(member,
                                new ClassOf(classOf.classes().id(), classOf.constant().toString()));
                        default -> throw new IllegalArgumentException(member + " holds no value a mark can have");
                    }
                });
                crossing.add(new Mark(mark.annotation(), texts, numbers, classes));
            }
            return crossing;
        }

        /**
         * The write this crossed as, with each annotation's class from {@code loader} and each set from {@code sets}.
         *
         * @throws ClassNotFoundException when no bound plugin has an annotation named
         */
        ParameterWrite write(ClassLoader loader, Function<String, ManagedClasses<?>> sets)
                throws ClassNotFoundException {
            ParameterWrite write = switch (start) {
                case String text -> ParameterWrite.of(group, name, text);
                case Integer number -> ParameterWrite.of(group, name, number);
                case Boolean flag -> ParameterWrite.of(group, name, flag);
                case null, default -> throw new IllegalArgumentException(name + " starts from no value it can hold");
            };
            for (Mark mark : marks) write = write.marked(mark(mark, loader, sets));
            for (Mark mark : groupMarks) write = write.groupMarked(mark(mark, loader, sets));
            return write;
        }

        private static ParameterWrite.Mark mark(Mark mark, ClassLoader loader, Function<String, ManagedClasses<?>> sets)
                throws ClassNotFoundException {
            Class<? extends Annotation> type = Class.forName(mark.annotation(), false, loader)
                    .asSubclass(Annotation.class);
            ParameterWrite.Mark built = ParameterWrite.Mark.of(type);
            for (Map.Entry<String, String> text : mark.texts().entrySet()) {
                built = built.with(text.getKey(), text.getValue());
            }
            for (Map.Entry<String, Integer> number : mark.numbers().entrySet()) {
                built = built.with(number.getKey(), number.getValue());
            }
            for (Map.Entry<String, ClassOf> classOf : mark.classes().entrySet()) {
                ClassOf named = classOf.getValue();
                ManagedClasses<?> set = sets.apply(named.id());
                PluginValues.Member constant = PluginValues.Member.read(named.constant()).orElseThrow(() ->
                        new IllegalArgumentException(named.constant() + " names no constant"));
                built = built.withClassOf(classOf.getKey(), set, constant);
            }
            return built;
        }
    }

    record ForgetRunParameter(String group, String name) implements PluginWrite {
    }

    /**
     * A rename of a plugin's open-set member the user asked for, past the lock on the plugin's file, by each file's new
     * text under its absolute path. The user's own hands, as {@code CanvasEdit.ApplyRefactor} is, and it lands only on
     * the bot's own sources.
     */
    record ApplyPlan(String summary, Map<String, String> rewrites, Map<String, String> moves) implements PluginWrite {

        static ApplyPlan of(Refactor.Planned plan) {
            Map<String, String> rewrites = new LinkedHashMap<>();
            plan.rewrites().forEach((file, text) -> rewrites.put(file.toString(), text));
            Map<String, String> moves = new LinkedHashMap<>();
            plan.moves().forEach((from, to) -> moves.put(from.toString(), to.toString()));
            return new ApplyPlan(plan.summary(), rewrites, moves);
        }

        Refactor.Planned plan() {
            Map<Path, String> texts = new LinkedHashMap<>();
            rewrites.forEach((file, text) -> texts.put(Path.of(file), text));
            Map<Path, Path> moved = new LinkedHashMap<>();
            moves.forEach((from, to) -> moved.put(Path.of(from), Path.of(to)));
            return new Refactor.Planned(summary, texts, moved);
        }
    }
}
