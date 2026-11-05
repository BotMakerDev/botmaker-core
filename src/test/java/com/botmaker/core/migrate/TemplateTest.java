package com.botmaker.core.migrate;

import com.botmaker.java.grammar.SourceParser;
import com.botmaker.java.parse.JavaLevel;
import org.eclipse.jdt.core.dom.Expression;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A rule's body with its parameters filled: which names are parameters, and which only share the spelling. */
class TemplateTest {

    private static Expression read(String text) {
        return SourceParser.expression(JavaLevel.LATEST, text);
    }

    private static String filled(String body, String parameter, String part) {
        return Template.filled(Template.body(body, false).orElseThrow(),
                Map.of(parameter, Template.Part.of(read(part), part))).orElseThrow().text();
    }

    @Test
    void aMethodReferencesOwnNameIsNoParameter() {
        assertEquals("Foo::step", filled("Foo::step", "step", "other"));
        assertEquals("Foo::step", Template.substituted("Foo::step", Map.of("step", read("1 + 2")))
                .orElseThrow().toString());
    }

    @Test
    void aQualifierTakesParenthesesAndBecomesAFieldRead() {
        assertEquals("(a + b).x", filled("value.x", "value", "a + b"));
        assertEquals("(a + b).x", Template.substituted("value.x", Map.of("value", read("a + b")))
                .orElseThrow().toString());
    }

    @Test
    void anArgumentNeedsNoParentheses() {
        assertEquals("f(a + b)", filled("f(value)", "value", "a + b"));
    }
}
