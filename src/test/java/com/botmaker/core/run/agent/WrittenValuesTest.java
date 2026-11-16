// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.run.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WrittenValuesTest {

    public record Login(String name, int slot) {}

    public enum Kind { MAIN, ALT }

    private static final String HERE = WrittenValuesTest.class.getCanonicalName();

    private static Object build(String written, Class<?> target) {
        return WrittenValues.build(written, target, WrittenValuesTest.class.getClassLoader());
    }

    @Test
    void literalsAreBuiltByValue() {
        assertEquals("a\"b\n", build("\"a\\\"b\\n\"", String.class));
        assertEquals('x', build("'x'", char.class));
        assertEquals(-3, build("-3", int.class));
        assertEquals(5L, build("5L", long.class));
        assertEquals(2.5, build("2.5D", double.class));
        assertEquals(true, build("true", boolean.class));
        assertNull(build("null", String.class));
    }

    @Test
    void aNumberWidensToTheTypeItIsGivenTo() {
        assertEquals(4L, build("4", long.class));
        assertEquals(4.0, build("4", double.class));
    }

    @Test
    void nestedClassesConstantsConstructorsAndFactoriesAreBuilt() {
        assertEquals(new Login("a", 1), build("new " + HERE + ".Login(\"a\", 1)", Login.class));
        assertEquals(Kind.ALT, build(HERE + ".Kind.ALT", Kind.class));
        assertEquals(Duration.ofSeconds(5), build("java.time.Duration.ofSeconds(5)", Duration.class));
        assertEquals(Duration.ofSeconds(5).plusMillis(2),
                build("java.time.Duration.ofSeconds(5).plusMillis(2)", Duration.class));
        assertEquals(List.of("a", "b"), build("java.util.List.of(\"a\", \"b\")", List.class));
        assertEquals(String.class, build("java.lang.String.class", Class.class));
    }

    /** Two constructors both fit a String: javac takes the more specific. */
    public record Named(String said) {
        public Named(CharSequence said) {
            this("any:" + said);
        }
    }

    @Test
    void theMostSpecificOverloadIsCalled() {
        assertEquals(new Named("a"), build("new " + HERE + ".Named(\"a\")", Named.class));
    }

    @Test
    void aClassNoImportNamedIsFoundFromWhereItWasWritten() {
        assertEquals(new Login("a", 1), WrittenValues.build("new Login(\"a\", 1)", Login.class,
                WrittenValuesTest.class.getClassLoader(), WrittenValuesTest.class.getName()));
        assertEquals(Kind.MAIN, WrittenValues.build("Kind.MAIN", Kind.class,
                WrittenValuesTest.class.getClassLoader(), Login.class.getName()));
    }

    @Test
    void aClassThatIsNotThereIsRefused() {
        assertThrows(WrittenValues.Unbuilt.class, () -> build("com.nowhere.Nothing.X", Object.class));
        assertThrows(WrittenValues.Unbuilt.class, () -> build("new " + HERE + ".Login(1)", Login.class));
    }
}
