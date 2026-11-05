package com.botmaker.core.project.params;

import com.botmaker.java.grammar.JavaValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A row's defaults and its value equality — the window compares a row before and after an edit, so equality
 * is load-bearing rather than cosmetic. Moved from the contract's {@code ParameterDataTest} with the row
 * (2026-09-28).
 */
class ParameterRowTest {

    private static ParameterRow.Builder row(String name) {
        return ParameterRow.named(name, "String");
    }

    private static JavaValue java(String source) {
        return JavaValue.parse(source).orElseThrow();
    }

    @Test
    void aRowNeedsAName() {
        assertThrows(IllegalArgumentException.class, () -> ParameterRow.named("  ", "String"));
        assertThrows(IllegalArgumentException.class, () -> ParameterRow.named(null, "String"));
    }

    /** Every default is the reading that keeps a project open: a value and a visibility absent. */
    @Test
    void everyDefaultIsTheSafeReading() {
        ParameterRow bare = row("rest").build();

        assertEquals(Optional.empty(), bare.value());
        assertEquals("", bare.valueText());
        assertEquals("", bare.description());
        assertEquals("rest", bare.displayLabel());
        assertEquals("", bare.category());
        assertEquals(ParameterRow.GENERAL, bare.categoryOrGeneral());
        assertEquals(Visibility.PUBLIC, bare.visibility());
        assertTrue(bare.isPublic());
    }

    /** A row with no type at all is blank rather than a {@code null} the host would trip over. */
    @Test
    void aRowWithNoTypeHoldsABlankOne() {
        ParameterRow untyped = ParameterRow.named("legacy", null).value(java("kept")).build();

        assertEquals("", untyped.typeName());
        assertEquals("kept", untyped.valueText());
    }

    @Test
    void aRowCarriesItsTypeAsWritten() {
        assertEquals("java.util.List<String>", ParameterRow.named("keys", "java.util.List<String>")
                .build().typeName());
        assertEquals("Map<String, List<Point>>", ParameterRow.named("retries", "Map<String, List<Point>>")
                .build().typeName());
    }

    @Test
    void withValueKeepsEverythingElseAndEqualityIsByComponent() {
        ParameterRow declared = ParameterRow.named("rest", "java.time.Duration")
                .value(java("java.time.Duration.ofSeconds(3)")).description("How long to wait").category("Timing")
                .visibility(Visibility.EDITOR_ONLY)
                .build();

        ParameterRow edited = declared.withValue(java("java.time.Duration.ofSeconds(5)"));

        assertEquals("java.time.Duration.ofSeconds(5)", edited.valueText());
        assertNotEquals(declared, edited);
        assertEquals(declared, edited.withValue(java("java.time.Duration.ofSeconds(3)")));
        assertEquals(declared.hashCode(), edited.withValue(java("java.time.Duration.ofSeconds(3)")).hashCode());
        assertEquals(declared, declared.toBuilder().build());
        assertEquals("How long to wait", edited.displayLabel());
        assertEquals("Timing", edited.categoryOrGeneral());
    }

    /** The value is a tree (2026-11-05): two spellings of one expression are one value, and its text is what was written. */
    @Test
    void aValueIsComparedAsATreeAndShownAsWritten() {
        ParameterRow spaced = row("rest").value(java("Duration.ofSeconds( 3 )")).build();
        ParameterRow tight = row("rest").value(java("Duration.ofSeconds(3)")).build();

        assertEquals(spaced, tight);
        assertEquals(spaced.hashCode(), tight.hashCode());
        assertTrue(spaced.sameValue(tight));
        assertEquals("Duration.ofSeconds( 3 )", spaced.valueText());
        assertNotEquals(spaced, row("rest").build());
    }

    /** A category is free text the window files a row under — trimmed, and never a vocabulary. */
    @Test
    void aCategoryIsFreeTextTheWindowFilesARowUnder() {
        ParameterRow filed = row("rest").category("  Timing ").build();

        assertEquals("Timing", filed.category());
        assertEquals(ParameterRow.GENERAL, row("rest").build().categoryOrGeneral());
    }

    @Test
    void anUnknownVisibilityIdReadsAsEditorOnly() {
        assertEquals(Visibility.PUBLIC, Visibility.fromId("public"));
        assertEquals(Visibility.EDITOR_ONLY, Visibility.fromId("something newer"));
        assertEquals(Visibility.EDITOR_ONLY, Visibility.fromId(null));
    }
}
