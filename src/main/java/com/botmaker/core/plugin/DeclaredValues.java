// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.plugin;

import com.botmaker.core.project.managed.ManagedIds;
import com.botmaker.plugin.api.source.ManagedClasses;
import com.botmaker.plugin.api.source.ManagedOpenSet;
import com.botmaker.plugin.api.source.ManagedValue;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** The values the bound plugins declare, found by the id a bot marks, and what is said when one cannot be written. */
final class DeclaredValues {

    private DeclaredValues() {
    }

    /** The value a bound plugin declares by {@code id}. */
    static Optional<ManagedValue<?>> declared(String id) {
        return PluginHost.managedValues().stream().filter(value -> value.id().equals(id)).findFirst();
    }

    /** The set of classes a bound plugin declares by {@code id}. */
    static Optional<ManagedClasses<?>> classes(String id) {
        return declared(id).filter(ManagedClasses.class::isInstance).map(value -> (ManagedClasses<?>) value);
    }

    /** The open set {@code id} among what the plugins declare, or empty. */
    static Optional<ManagedOpenSet<?>> openSet(List<ManagedValue<?>> declared, String id) {
        return declared.stream()
                .<ManagedOpenSet<?>>flatMap(value -> value instanceof ManagedOpenSet<?> set && set.id().equals(id)
                        ? Stream.of(set) : Stream.empty())
                .findFirst();
    }

    /**
     * Why {@code value} may not join the open set {@code id}, or empty when it may: the set's declaration
     * names the class of each constant, and a value of another class would compile into a set the plugin then
     * reads as the wrong type. Compared by binary name, the class or any supertype — the plugin's class loader
     * is not Studio's. A set no plugin declares with an element type is not checked here.
     */
    static Optional<String> notAnElement(List<ManagedValue<?>> declared, String id, String member, Object value) {
        return openSet(declared, id).flatMap(set -> notAnElement(set, member, value));
    }

    /** The same, against the set already found. */
    static Optional<String> notAnElement(ManagedOpenSet<?> set, String member, Object value) {
        // A set of classes takes fields here, each of its field type; a whole class is addClass's.
        boolean classes = set instanceof ManagedClasses<?>;
        Class<?> element = set instanceof ManagedClasses<?> nested ? nested.fieldType() : set.type();
        if (classes && !member.contains(".")) {
            return Optional.of(member + " names a whole class of " + set.holder() + ", which is added with its setup.");
        }
        if (element == null || isA(value.getClass(), element.getName())) return Optional.empty();
        return Optional.of(member + " cannot join " + set.holder() + ": it is " + article(value.getClass())
                + ", and every " + (classes ? "field" : "constant") + " there is " + article(element) + ".");
    }

    private static boolean isA(Class<?> cls, String name) {
        if (cls == null) return false;
        if (cls.getName().equals(name) || isA(cls.getSuperclass(), name)) return true;
        for (Class<?> face : cls.getInterfaces()) if (isA(face, name)) return true;
        return false;
    }

    static String article(Class<?> cls) {
        String name = cls.getSimpleName().isEmpty() ? cls.getName() : cls.getSimpleName();
        return ("AEIOU".indexOf(name.charAt(0)) >= 0 ? "an " : "a ") + name;
    }

    static String notClasses(String id) {
        return "No plugin in this project declares " + ManagedIds.spelled(id, PluginHost.managedValues())
                + " as a set of classes.";
    }

    static String notGrouped(String id) {
        return "No plugin in this project declares " + ManagedIds.spelled(id, PluginHost.managedValues())
                + " as a set of enums.";
    }

    static String notEditable(String id) {
        return "The bot has no " + ManagedIds.spelled(id, PluginHost.managedValues()) + " a plugin may change.";
    }

    static String missing(String id, String member) {
        return "The project has no " + member + " in the class marked "
                + ManagedIds.spelled(id, PluginHost.managedValues()) + ".";
    }
}
