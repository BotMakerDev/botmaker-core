package com.botmaker.core.run.agent;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds a value in the bot's JVM from the way the core writes it ({@code run.claims.Canonical}): Java with every
 * class fully qualified, and only what a pool's element can be (spec {@code 2026-11-15-run-claims-design.md} §2) —
 * a literal, a constant, a constructor or static factory over those, and methods chained on one. A name is resolved
 * by loading it, so {@code com.bot.Outer.Inner.NAME} finds the nested class; a member is reached by reflection.
 *
 * <p>Names only the JDK: it runs in the agent's bootstrap half, on the bot's own class path.
 */
final class WrittenValues {

    /** A writing this reader cannot build: the words say what and where. */
    static final class Unbuilt extends RuntimeException {
        Unbuilt(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final Map<Class<?>, Class<?>> BOXES = Map.of(boolean.class, Boolean.class, byte.class, Byte.class,
            char.class, Character.class, short.class, Short.class, int.class, Integer.class, long.class, Long.class,
            float.class, Float.class, double.class, Double.class);
    private static final Map<String, Class<?>> PRIMITIVES = Map.of("boolean", boolean.class, "byte", byte.class,
            "char", char.class, "short", short.class, "int", int.class, "long", long.class, "float", float.class,
            "double", double.class);

    private final String text;
    private final ClassLoader loader;
    /** The class the value was written in, by binary name: where a name nothing imports is found; empty for none. */
    private final String context;
    private int at;

    private WrittenValues(String text, ClassLoader loader, String context) {
        this.text = text;
        this.loader = loader;
        this.context = context;
    }

    /** {@link #build(String, Class, ClassLoader, String)} with no class it was written in. */
    static Object build(String written, Class<?> target, ClassLoader loader) {
        return build(written, target, loader, "");
    }

    /**
     * The value {@code written} builds, as a {@code target} (a number widened to it, as a return would). A class
     * {@code Canonical} left unqualified, which no import named, is looked for as javac would from {@code context}:
     * nested in it or a class around it, then in its package.
     *
     * @throws Unbuilt when it does not read, names what is not there, or throws while built
     */
    static Object build(String written, Class<?> target, ClassLoader loader, String context) {
        WrittenValues reader = new WrittenValues(written, loader, context);
        Object value;
        try {
            value = reader.expression();
            reader.blanks();
            if (reader.at < written.length()) throw reader.unread("more after the value");
        } catch (Unbuilt e) {
            throw e;
        } catch (RuntimeException | LinkageError e) {
            throw new Unbuilt(written + " could not be built: " + e, e);
        }
        if (value == null && !target.isPrimitive()) return null;
        return converted(value, target).orElseThrow(() -> new Unbuilt(written + " is not a " + target.getName(), null));
    }

    // ---- reading

    private Object expression() {
        blanks();
        Object value;
        char c = peek();
        if (c == '"') value = string();
        else if (c == '\'') value = character();
        else if (c == '-' || Character.isDigit(c)) value = number();
        else if (Character.isJavaIdentifierStart(c)) value = named();
        else throw unread("a value");
        return chained(value);
    }

    /** {@code .field} and {@code .method(args)} after a value. */
    private Object chained(Object value) {
        while (true) {
            blanks();
            if (peek() != '.') return value;
            at++;
            String name = identifier();
            blanks();
            value = peek() == '(' ? call(value.getClass(), value, name, arguments()) : field(value.getClass(), value,
                    name);
        }
    }

    private Object named() {
        String first = identifier();
        switch (first) {
            case "true" -> { return true; }
            case "false" -> { return false; }
            case "null" -> { return null; }
            case "new" -> {
                blanks();
                List<String> type = dotted(identifier());
                Class<?> made = classOf(type, type.size())
                        .orElseThrow(() -> new Unbuilt("no class " + String.join(".", type) + " in " + text, null));
                return construct(made, arguments());
            }
            default -> {
                List<String> names = dotted(first);
                blanks();
                boolean calls = peek() == '(';
                if (names.getLast().equals("class")) {
                    List<String> type = names.subList(0, names.size() - 1);
                    if (type.size() == 1 && PRIMITIVES.containsKey(type.getFirst())) return PRIMITIVES.get(type.getFirst());
                    return classOf(type, type.size())
                            .orElseThrow(() -> new Unbuilt("no class " + String.join(".", type) + " in " + text, null));
                }
                // The longest start that is a class; what follows is its static member, then members of that.
                // A class alone is no value: at least one member follows it.
                for (int classEnd = names.size() - 1; classEnd >= 1; classEnd--) {
                    var type = classOf(names, classEnd);
                    if (type.isEmpty()) continue;
                    Object value = null;
                    Class<?> owner = type.get();
                    boolean isStatic = true;
                    for (int i = classEnd; i < names.size(); i++) {
                        boolean last = i == names.size() - 1;
                        if (last && calls) return call(owner, isStatic ? null : value, names.get(i), arguments());
                        value = field(owner, isStatic ? null : value, names.get(i));
                        isStatic = false;
                        owner = value == null ? Object.class : value.getClass();
                    }
                    return value;
                }
                throw new Unbuilt("no class in " + String.join(".", names) + " (" + text + ")", null);
            }
        }
    }

    private List<String> dotted(String first) {
        List<String> names = new ArrayList<>(List.of(first));
        while (true) {
            int mark = at;
            blanks();
            if (peek() != '.') {
                at = mark;
                return names;
            }
            at++;
            blanks();
            names.add(identifier());
        }
    }

    private List<Object> arguments() {
        blanks();
        expect('(');
        List<Object> out = new ArrayList<>();
        blanks();
        if (peek() == ')') {
            at++;
            return out;
        }
        while (true) {
            out.add(expression());
            blanks();
            char c = next();
            if (c == ')') return out;
            if (c != ',') throw unread("a comma or a closing parenthesis");
        }
    }

    private String identifier() {
        blanks();
        int start = at;
        if (at >= text.length() || !Character.isJavaIdentifierStart(text.charAt(at))) throw unread("a name");
        while (at < text.length() && Character.isJavaIdentifierPart(text.charAt(at))) at++;
        return text.substring(start, at);
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') return out.toString();
            out.append(c == '\\' ? escaped() : c);
        }
    }

    private Character character() {
        expect('\'');
        char c = next();
        char value = c == '\\' ? escaped() : c;
        expect('\'');
        return value;
    }

    private char escaped() {
        char c = next();
        return switch (c) {
            case 'n' -> '\n';
            case 't' -> '\t';
            case 'r' -> '\r';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 's' -> ' ';
            case '0' -> '\0';
            case '"', '\'', '\\' -> c;
            case 'u' -> {
                while (peek() == 'u') at++;
                if (at + 4 > text.length()) throw unread("four hex digits");
                char unicode = (char) Integer.parseInt(text.substring(at, at + 4), 16);
                at += 4;
                yield unicode;
            }
            default -> throw unread("an escape");
        };
    }

    private Object number() {
        int start = at;
        if (peek() == '-') at++;
        while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '.'
                || (text.charAt(at) == '-' || text.charAt(at) == '+') && "eE".indexOf(text.charAt(at - 1)) >= 0)) {
            at++;
        }
        String token = text.substring(start, at);
        char suffix = Character.toUpperCase(token.charAt(token.length() - 1));
        String digits = suffix == 'L' || suffix == 'F' || suffix == 'D' ? token.substring(0, token.length() - 1) : token;
        try {
            return switch (suffix) {
                case 'L' -> new BigInteger(digits).longValueExact();
                case 'F' -> Float.parseFloat(digits);
                case 'D' -> Double.parseDouble(digits);
                default -> digits.contains(".") || digits.contains("e") || digits.contains("E")
                        ? (Object) Double.parseDouble(digits) : new BigInteger(digits).intValueExact();
            };
        } catch (NumberFormatException | ArithmeticException e) {
            throw new Unbuilt("not a number: " + token + " in " + text, e);
        }
    }

    // ---- reaching members

    /** The class {@code names[0..end)} writes: a package then classes nested in one another, tried longest first. */
    private Optional<Class<?>> classOf(List<String> names, int end) {
        String nestedAll = String.join("$", names.subList(0, end));
        if (!context.isEmpty()) {
            // Nested in the class it was written in, or in one around it.
            for (String around = context; ; around = around.substring(0, around.lastIndexOf('$'))) {
                Optional<Class<?>> found = load(around + "$" + nestedAll);
                if (found.isPresent()) return found;
                if (around.lastIndexOf('$') < 0) break;
            }
            int dot = context.lastIndexOf('.');
            if (dot > 0) {
                Optional<Class<?>> found = load(context.substring(0, dot) + "." + nestedAll);
                if (found.isPresent()) return found;
            }
        }
        if (end == 1) {
            Optional<Class<?>> lang = load("java.lang." + names.getFirst());
            if (lang.isPresent()) return lang;
        }
        for (int packageEnd = end - 1; packageEnd >= 0; packageEnd--) {
            String pack = String.join(".", names.subList(0, packageEnd));
            String nested = String.join("$", names.subList(packageEnd, end));
            Optional<Class<?>> found = load(pack.isEmpty() ? nested : pack + "." + nested);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    private Optional<Class<?>> load(String name) {
        try {
            return Optional.of(Class.forName(name, false, loader));
        } catch (ClassNotFoundException | LinkageError e) {
            return Optional.empty();
        }
    }

    private Object field(Class<?> owner, Object on, String name) {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.getName().equals(name) || Modifier.isStatic(field.getModifiers()) != (on == null)) continue;
                try {
                    field.trySetAccessible();
                    return field.get(on);
                } catch (IllegalAccessException e) {
                    throw new Unbuilt(owner.getName() + "." + name + " cannot be read: " + e.getMessage(), e);
                }
            }
        }
        throw new Unbuilt("no field " + name + " in " + owner.getName() + " (" + text + ")", null);
    }

    private Object call(Class<?> owner, Object on, String name, List<Object> arguments) {
        List<Executable> named = new ArrayList<>();
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && Modifier.isStatic(method.getModifiers()) == (on == null)) {
                named.add(method);
            }
        }
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(name) && Modifier.isStatic(method.getModifiers()) == (on == null)
                    && !named.contains(method)) {
                named.add(method);
            }
        }
        return invoke(owner.getName() + "." + name, named, on, arguments);
    }

    private Object construct(Class<?> made, List<Object> arguments) {
        return invoke("new " + made.getName(), new ArrayList<>(Arrays.asList(made.getDeclaredConstructors())), null,
                arguments);
    }

    /**
     * The one of {@code candidates} the arguments fit, called: exact before widened before varargs, and within a pass
     * the most specific, as javac picks ({@code Login(String)} over {@code Login(CharSequence)}).
     */
    private Object invoke(String said, List<Executable> candidates, Object on, List<Object> arguments) {
        for (int pass = 0; pass < 3; pass++) {
            Executable chosen = null;
            Object[] chosenArguments = null;
            for (Executable candidate : candidates) {
                Object[] fitted = fitted(candidate, arguments, pass);
                if (fitted == null) continue;
                if (chosen == null || moreSpecific(candidate, chosen)) {
                    chosen = candidate;
                    chosenArguments = fitted;
                }
            }
            if (chosen == null) continue;
            try {
                chosen.trySetAccessible();
                return chosen instanceof Method method ? method.invoke(on, chosenArguments)
                        : ((Constructor<?>) chosen).newInstance(chosenArguments);
            } catch (InvocationTargetException e) {
                throw new Unbuilt(said + " threw " + e.getCause() + " (" + text + ")", e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new Unbuilt(said + " cannot be called: " + e.getMessage(), e);
            }
        }
        throw new Unbuilt(said + " takes no " + arguments.size() + " such arguments (" + text + ")", null);
    }

    /** Whether each of {@code one}'s parameters is a kind of {@code other}'s, and they differ. */
    private static boolean moreSpecific(Executable one, Executable other) {
        Class<?>[] mine = one.getParameterTypes();
        Class<?>[] theirs = other.getParameterTypes();
        if (mine.length != theirs.length || Arrays.equals(mine, theirs)) return false;
        for (int i = 0; i < mine.length; i++) {
            if (!theirs[i].isAssignableFrom(mine[i])) return false;
        }
        return true;
    }

    /**
     * {@code arguments} as {@code candidate} takes them, or null when they do not fit: on pass 0 each argument is of
     * its parameter's own (boxed) class, on pass 1 it may widen, on pass 2 the last parameters may be varargs.
     */
    private static Object[] fitted(Executable candidate, List<Object> arguments, int pass) {
        Class<?>[] types = candidate.getParameterTypes();
        boolean varargs = pass == 2 && candidate.isVarArgs();
        if (!varargs && types.length != arguments.size()) return null;
        if (varargs && arguments.size() < types.length - 1) return null;
        int fixed = varargs ? types.length - 1 : types.length;
        Object[] out = new Object[types.length];
        for (int i = 0; i < fixed; i++) {
            Optional<Object> one = fit(arguments.get(i), types[i], pass > 0);
            if (one == null) return null;
            out[i] = one.orElse(null);
        }
        if (varargs) {
            Class<?> component = types[fixed].getComponentType();
            Object rest = Array.newInstance(component, arguments.size() - fixed);
            for (int i = fixed; i < arguments.size(); i++) {
                Optional<Object> one = fit(arguments.get(i), component, true);
                if (one == null) return null;
                Array.set(rest, i - fixed, one.orElse(null));
            }
            out[fixed] = rest;
        }
        return out;
    }

    /** {@code value} as a {@code type}: empty for null, null when it does not fit. */
    private static Optional<Object> fit(Object value, Class<?> type, boolean widens) {
        if (value == null) return type.isPrimitive() ? null : Optional.empty();
        Class<?> boxed = type.isPrimitive() ? BOXES.get(type) : type;
        if (boxed.isInstance(value)) return Optional.of(value);
        if (!widens) return null;
        Optional<Object> widened = converted(value, type);
        return widened.isPresent() ? widened : null;
    }

    /** {@code value} as a {@code target}, a number or char widened as Java widens it; empty when it is not one. */
    static Optional<Object> converted(Object value, Class<?> target) {
        Class<?> boxed = target.isPrimitive() ? BOXES.get(target) : target;
        if (value == null) return Optional.empty();
        if (boxed.isInstance(value)) return Optional.of(value);
        Object number = value instanceof Character c ? (Object) (int) c : value;
        if (number instanceof Integer || number instanceof Short || number instanceof Byte) {
            long whole = ((Number) number).longValue();
            if (boxed == Long.class) return Optional.of(whole);
            if (boxed == Float.class) return Optional.of((float) whole);
            if (boxed == Double.class) return Optional.of((double) whole);
            if (boxed == Integer.class && !(value instanceof Integer)) return Optional.of((int) whole);
        } else if (number instanceof Long whole) {
            if (boxed == Float.class) return Optional.of((float) whole);
            if (boxed == Double.class) return Optional.of((double) whole);
        } else if (number instanceof Float real && boxed == Double.class) {
            return Optional.of((double) real);
        }
        return Optional.empty();
    }

    // ---- characters

    private void blanks() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
    }

    private char peek() {
        return at < text.length() ? text.charAt(at) : '\0';
    }

    private char next() {
        if (at >= text.length()) throw unread("more");
        return text.charAt(at++);
    }

    private void expect(char c) {
        blanks();
        if (next() != c) {
            at--;
            throw unread("'" + c + "'");
        }
    }

    private Unbuilt unread(String wanted) {
        return new Unbuilt("expected " + wanted + " at " + at + " in " + text, null);
    }
}
