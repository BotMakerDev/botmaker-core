package com.botmaker.core.edit;

import com.botmaker.java.parse.JavaLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodDiffTest {

    private static String file(String... methods) {
        return "class Subject {\n" + String.join("\n", methods) + "\n}\n";
    }

    private static MethodDiff diff(String before, String after) {
        return MethodDiff.between(JavaLevel.LATEST, before, after);
    }

    @Test
    void onlyTheMethodThatChangedIsCarried() {
        MethodDiff diff = diff(file("void a() { int x = 1; }", "void b() { }"),
                file("void a() { int x = 2; }", "void b() { }"));

        assertEquals(List.of("a"), diff.changed().stream().map(ChangedMethod::name).toList());
        assertEquals("void a() { int x = 2; }", diff.changed().getFirst().source());
        assertTrue(diff.removed().isEmpty());
    }

    /** An id is a place: the method after the one removed moves up into its id, and the last id is gone. */
    @Test
    void aRemovedMethodRenumbersThoseAfterIt() {
        MethodDiff diff = diff(file("void a() { }", "void b() { }"), file("void b() { }"));

        assertEquals(List.of("b"), diff.changed().stream().map(ChangedMethod::name).toList());
        assertEquals(1, diff.removed().size());
    }

    @Test
    void aMethodsNodesAreRelativeToItsSourceWhichLeavesItsJavadocOut() {
        MethodDiff diff = diff(file("void a() { }"), file("/** Says what. */\nvoid a() { int x = 1; }"));

        ChangedMethod method = diff.changed().getFirst();
        assertEquals("void a() { int x = 1; }", method.source());
        for (ChangedMethod.Node node : method.nodes()) {
            assertTrue(node.start() >= 0 && node.start() + node.length() <= method.source().length(), node.id());
        }
        assertEquals(method.id(), method.nodes().getFirst().id());
    }

    @Test
    void nothingChangedSaysNothing() {
        MethodDiff diff = diff(file("void a() { }"), file("void a() { }"));

        assertTrue(diff.changed().isEmpty());
        assertTrue(diff.removed().isEmpty());
    }
}
