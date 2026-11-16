// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.run.agent;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The values this run was dealt, built once in its JVM before the bot's {@code main}, which every method
 * {@link PooledMethods} rewrote returns (spec {@code 2026-11-15-run-claims-design.md} §5). Public, as the rewritten
 * classes call {@link #value} from the bot's own packages.
 */
public final class PooledValues {

    private static final Map<String, Object> VALUES = new ConcurrentHashMap<>();

    private PooledValues() {}

    /**
     * What the rewritten method {@code key} returns ({@link RunPlan.Pooled#key}).
     *
     * @throws IllegalStateException when the method was rewritten with nothing built for it, which a run never
     *                               reaches: the agent builds every value before the bot starts
     */
    public static Object value(String key) {
        Object value = VALUES.get(key);
        if (value == null && !VALUES.containsKey(key)) {
            throw new IllegalStateException("BotMaker dealt this run no value for " + key);
        }
        return value instanceof Null ? null : value;
    }

    /**
     * Builds each of {@code pooled}'s values with the bot's {@code loader}, and each pool it came from again, which must
     * list what the core read: else why not, and nothing is kept.
     */
    static Optional<String> load(List<RunPlan.Pooled> pooled, ClassLoader loader) {
        Map<String, Object> built = new ConcurrentHashMap<>();
        for (RunPlan.Pooled one : pooled) {
            try {
                Optional<String> differs = differs(one, loader);
                if (differs.isPresent()) return differs;
                Object value = WrittenValues.build(one.value(), returned(one, loader), loader, writtenIn(one));
                built.put(one.key(), value == null ? Null.NULL : value);
            } catch (WrittenValues.Unbuilt e) {
                return Optional.of(one.said() + " could not take its value: " + e.getMessage());
            }
        }
        VALUES.clear();
        VALUES.putAll(built);
        return Optional.empty();
    }

    /** The class {@code one}'s value was written in: its pool's group, or for a plugin's pool the parameter's group. */
    private static String writtenIn(RunPlan.Pooled one) {
        int hash = one.pool().indexOf('#');
        return hash > 0 ? one.pool().substring(0, hash) : one.owner();
    }

    /** For tests: the values the rewritten methods return, built elsewhere. */
    static void install(Map<String, Object> values) {
        VALUES.clear();
        values.forEach((key, value) -> VALUES.put(key, value == null ? Null.NULL : value));
    }

    /**
     * Why {@code one}'s pool, built here, lists other elements than the core dealt from: an element the core read as
     * two that are equal here, a constant added since, or a pool that no longer builds. Empty for a plugin's pool.
     */
    private static Optional<String> differs(RunPlan.Pooled one, ClassLoader loader) {
        if (one.pool().isEmpty()) return Optional.empty();
        int hash = one.pool().indexOf('#');
        String group = one.pool().substring(0, hash);
        String name = one.pool().substring(hash + 1);
        String said = group.substring(group.lastIndexOf('.') + 1) + "." + name + "()";
        Collection<?> here;
        try {
            Class<?> type = Class.forName(group, true, loader);
            Method method = type.getDeclaredMethod(name);
            method.trySetAccessible();
            Object owner = null;
            if (!Modifier.isStatic(method.getModifiers())) {
                var made = type.getDeclaredConstructor();
                made.trySetAccessible();
                owner = made.newInstance();
            }
            Object pool = method.invoke(owner);
            Method elements = pool.getClass().getMethod("elements");
            here = (Collection<?>) elements.invoke(pool);
        } catch (InvocationTargetException e) {
            return Optional.of(said + " threw " + e.getCause() + " when built in this run");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return Optional.of(said + " could not be built in this run: " + e);
        }
        Class<?> element = one.elements().isEmpty() ? Object.class : elementType(one, loader);
        List<Object> read = new ArrayList<>();
        for (String written : one.elements()) read.add(WrittenValues.build(written, element, loader, writtenIn(one)));
        for (Object listed : read) {
            long same = here.stream().filter(listed::equals).count();
            long again = read.stream().filter(listed::equals).count();
            if (same != 1 || again != 1) {
                return Optional.of(said + (again > 1 ? " lists " + listed + " twice, as the values are equal"
                        : " no longer lists " + listed) + ": compile again, then start the runs again");
            }
        }
        if (here.size() != read.size()) {
            return Optional.of(said + " lists " + here.size() + " elements in this run, not the " + read.size()
                    + " dealt from: compile again, then start the runs again");
        }
        return Optional.empty();
    }

    /** The element a pool lists: the parameter's own type, or Object for a list parameter ({@code take}). */
    private static Class<?> elementType(RunPlan.Pooled one, ClassLoader loader) {
        Class<?> returned = returned(one, loader);
        return List.class.isAssignableFrom(returned) ? Object.class : returned;
    }

    /** The class {@code one}'s method returns, loaded without starting it. */
    private static Class<?> returned(RunPlan.Pooled one, ClassLoader loader) {
        ClassDesc type = MethodTypeDesc.ofDescriptor(one.descriptor()).returnType();
        try {
            return classOf(type, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new WrittenValues.Unbuilt("its type " + type.displayName() + " is not on this run's class path", e);
        }
    }

    private static Class<?> classOf(ClassDesc type, ClassLoader loader) throws ClassNotFoundException {
        if (type.isPrimitive()) {
            return switch (type.descriptorString()) {
                case "Z" -> boolean.class;
                case "B" -> byte.class;
                case "C" -> char.class;
                case "S" -> short.class;
                case "I" -> int.class;
                case "J" -> long.class;
                case "F" -> float.class;
                case "D" -> double.class;
                default -> void.class;
            };
        }
        if (type.isArray()) return Class.forName(type.descriptorString().replace('/', '.'), false, loader);
        String descriptor = type.descriptorString();
        return Class.forName(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'), false, loader);
    }

    /** A dealt {@code null}, which a concurrent map cannot hold. */
    private enum Null { NULL }
}
