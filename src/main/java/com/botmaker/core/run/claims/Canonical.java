package com.botmaker.core.run.claims;

import com.botmaker.java.grammar.JavaValue;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NameQualifiedType;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TextBlock;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeLiteral;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * An element's identity (spec {@code 2026-11-15-run-claims-design.md} §4, §5): the value's tree written one way,
 * every class fully qualified from the imports the grammar read with it, each literal by its value (so {@code 1_000}
 * and {@code 1000}, or two escapes of one string, are one element). What the live runs, {@code dealt.json} and a pin
 * name an element by, and what the run's agent builds the value from in the bot's JVM ({@code run.agent.WrittenValues}),
 * so the core that deals and the agent that rebuilds read one writing.
 *
 * <p>It is a value's serial form between two processes, never Java put in a file: a file is still written with trees.
 * Only what an element can be is written — a literal, a constant, a constructor or factory over those, a method
 * chained on one; anything else is {@link Unwritten}.
 */
public final class Canonical {

    /** A value that is not written one way: the words say what. */
    public static final class Unwritten extends RuntimeException {
        Unwritten(String message) {
            super(message);
        }
    }

    private final Map<String, String> imports = new HashMap<>();

    private Canonical(List<String> imports) {
        for (String canonical : imports) this.imports.putIfAbsent(canonical.substring(canonical.lastIndexOf('.') + 1),
                canonical);
    }

    /**
     * {@code value} written one way.
     *
     * @throws Unwritten when it is not a literal, a constant, or a constructor or factory over those
     */
    public static String of(JavaValue value) {
        return new Canonical(value.imports()).written(value.node());
    }

    /** {@code elements} as a list a {@code take} parameter is given, {@code java.util.List.of(a, b)}. */
    public static String listOf(List<String> elements) {
        return "java.util.List.of(" + String.join(", ", elements) + ")";
    }

    private String written(Expression node) {
        return switch (node) {
            case StringLiteral literal -> quoted(literal.getLiteralValue(), '"');
            case TextBlock block -> quoted(block.getLiteralValue(), '"');
            case CharacterLiteral literal -> quoted(String.valueOf(literal.charValue()), '\'');
            case NumberLiteral number -> number(number.getToken(), false);
            case PrefixExpression prefix when prefix.getOperand() instanceof NumberLiteral number
                    && (prefix.getOperator() == PrefixExpression.Operator.MINUS
                    || prefix.getOperator() == PrefixExpression.Operator.PLUS) ->
                    number(number.getToken(), prefix.getOperator() == PrefixExpression.Operator.MINUS);
            case BooleanLiteral literal -> Boolean.toString(literal.booleanValue());
            case NullLiteral ignored -> "null";
            case ParenthesizedExpression inner -> written(inner.getExpression());
            case Name name -> qualified(name.getFullyQualifiedName());
            case FieldAccess access -> written(access.getExpression()) + "." + access.getName().getIdentifier();
            case TypeLiteral literal -> type(literal.getType()) + ".class";
            case ClassInstanceCreation made when made.getAnonymousClassDeclaration() == null
                    && made.getExpression() == null -> "new " + type(made.getType()) + arguments(made.arguments());
            case MethodInvocation call when call.getExpression() != null ->
                    written(call.getExpression()) + "." + call.getName().getIdentifier() + arguments(call.arguments());
            default -> throw new Unwritten(node + " is not a value an element can be: a literal, a constant, or a"
                    + " record of those");
        };
    }

    private String arguments(List<?> arguments) {
        List<String> out = new ArrayList<>();
        for (Object argument : arguments) out.add(written((Expression) argument));
        return out.stream().collect(Collectors.joining(", ", "(", ")"));
    }

    /** {@code dotted} with its first name replaced by the class it imports, when it names one. */
    private String qualified(String dotted) {
        int dot = dotted.indexOf('.');
        String first = dot < 0 ? dotted : dotted.substring(0, dot);
        String imported = imports.get(first);
        return imported == null ? dotted : imported + (dot < 0 ? "" : dotted.substring(dot));
    }

    private String type(Type type) {
        return switch (type) {
            case PrimitiveType primitive -> primitive.getPrimitiveTypeCode().toString();
            case SimpleType simple -> qualified(simple.getName().getFullyQualifiedName());
            case QualifiedType nested -> type(nested.getQualifier()) + "." + nested.getName().getIdentifier();
            case NameQualifiedType nested -> qualified(nested.getQualifier().getFullyQualifiedName()) + "."
                    + nested.getName().getIdentifier();
            case ParameterizedType generic -> type(generic.getType());
            default -> throw new Unwritten(type + " is not a class an element can be built from");
        };
    }

    /** A number by its value: decimal, with {@code L}, {@code F} or {@code D} for what is not an int. */
    private static String number(String token, boolean negative) {
        String plain = token.replace("_", "").toLowerCase(Locale.ROOT);
        boolean hex = plain.startsWith("0x");
        char last = plain.charAt(plain.length() - 1);
        // A hex literal is floating only with its binary exponent (0x1.8p1), after which f and d are suffixes.
        boolean floating = hex ? plain.contains("p")
                : last == 'f' || last == 'd' || plain.contains(".") || plain.contains("e");
        if (floating) {
            String digits = last == 'f' || last == 'd' ? plain.substring(0, plain.length() - 1) : plain;
            if (last == 'f') return Float.toString((negative ? -1 : 1) * Float.parseFloat(digits)) + "F";
            return Double.toString((negative ? -1 : 1) * Double.parseDouble(digits)) + "D";
        }
        boolean isLong = last == 'l';
        String digits = isLong ? plain.substring(0, plain.length() - 1) : plain;
        BigInteger value;
        if (hex) value = new BigInteger(digits.substring(2), 16);
        else if (digits.startsWith("0b")) value = new BigInteger(digits.substring(2), 2);
        else if (digits.length() > 1 && digits.startsWith("0")) value = new BigInteger(digits.substring(1), 8);
        else value = new BigInteger(digits);
        if (hex || digits.startsWith("0b") || digits.length() > 1 && digits.startsWith("0")) {
            // A hex, octal or binary literal is the bits of its type: 0xFFFFFFFF is -1.
            value = BigInteger.valueOf(isLong ? value.longValue() : value.intValue());
        }
        if (negative) value = value.negate();
        return isLong ? value.longValueExact() + "L" : Integer.toString(value.intValueExact());
    }

    private static String quoted(String value, char quote) {
        StringBuilder out = new StringBuilder().append(quote);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\\' -> out.append("\\\\");
                case '"', '\'' -> out.append(c == quote ? "\\" + c : String.valueOf(c));
                default -> {
                    if (c < 0x20 || c > 0x7e) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append(quote).toString();
    }
}
