// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.run.claims;

import com.botmaker.java.grammar.JavaValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanonicalTest {

    private static String of(String source, String... imports) {
        JavaValue parsed = JavaValue.parse(source).orElseThrow();
        return Canonical.of(JavaValue.built(parsed.node(), List.of(imports)));
    }

    @Test
    void oneValueIsWrittenOneWayHoweverItWasSpelled() {
        assertEquals("new com.bot.Login(\"a\", 1)", of("new Login( \"a\",1 )", "com.bot.Login"));
        assertEquals(of("1000"), of("1_000"));
        assertEquals("255", of("0xFF"));
        assertEquals("-3", of("-3"));
        assertEquals(of("2.50"), of("2.5"));
        assertEquals("5L", of("5l"));
        assertEquals("\"a\\\"b\"", of("\"a\\\"b\""));
        assertEquals("'x'", of("'x'"));
        assertEquals("\"a\"", of("(\"a\")"));
        assertEquals("3.0D", of("0x1.8p1"));
        assertEquals("8.0F", of("0x1p3f"));
    }

    @Test
    void aClassIsQualifiedByWhatTheValueImports() {
        assertEquals("com.bot.Accounts.MAIN", of("Accounts.MAIN", "com.bot.Accounts"));
        assertEquals("Accounts.MAIN", of("Accounts.MAIN"));
        assertEquals("java.time.Duration.ofSeconds(5).plusMillis(2)",
                of("Duration.ofSeconds(5).plusMillis(2)", "java.time.Duration"));
        assertEquals("com.bot.Kind.class", of("Kind.class", "com.bot.Kind"));
    }

    @Test
    void whatIsNotAValueIsRefused() {
        assertThrows(Canonical.Unwritten.class, () -> of("a + b"));
        assertThrows(Canonical.Unwritten.class, () -> of("make()"));
    }

    @Test
    void aTakeIsAListOfItsElements() {
        assertEquals("java.util.List.of(\"a\", \"b\")", Canonical.listOf(List.of("\"a\"", "\"b\"")));
    }
}
