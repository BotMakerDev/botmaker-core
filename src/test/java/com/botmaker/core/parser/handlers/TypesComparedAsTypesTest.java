package com.botmaker.core.parser.handlers;

import com.botmaker.core.TestSources;
import com.botmaker.core.plugin.PluginHost;
import com.botmaker.core.project.LockResolver;
import com.botmaker.core.project.ProjectState;
import com.botmaker.java.edit.EditContext;
import com.botmaker.java.edit.handlers.InstantiationHandler;
import com.botmaker.java.edit.helpers.FileTypeDetector;
import com.botmaker.java.edit.helpers.MethodSignatures;
import com.botmaker.java.edit.palette.FunctionDraft;
import com.botmaker.java.edit.palette.SignatureType;
import com.botmaker.java.grammar.ValueTypes;
import com.botmaker.java.types.ProjectAnalyzer;
import com.botmaker.java.types.ResolvedType;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a signature edit, a lock and a block decide from a type, asked of the type and not of its printout. */
class TypesComparedAsTypesTest {

    private static CompilationUnit unit(String source) {
        return ProjectAnalyzer.createCompilationUnit(TestSources.runtimeClassPath(), source, TestSources.SOURCE_ROOT);
    }

    private static MethodDeclaration first(CompilationUnit unit) {
        return ((TypeDeclaration) unit.types().getFirst()).getMethods()[0];
    }

    private static String retyped(String source, SignatureType type) {
        CompilationUnit unit = unit(source);
        MethodDeclaration method = first(unit);
        FunctionDraft draft = new FunctionDraft(method.getName().getIdentifier(), SignatureType.kept("void"),
                List.of(new FunctionDraft.Parameter("m", type, 0)));
        EditContext ctx = EditContext.of(unit, new ProjectAnalyzer(null, new ProjectState()), null);
        return MethodHandler.applyFunctionSignature(ctx, source, method, draft);
    }

    @Test
    void aPickedTypeTheFileAlreadyNamesIsLeftAsWritten() {
        String source = """
                package com.mybot;
                public class Subject {
                    public void f(java.util.Map<String, Integer> m) { }
                }
                """;
        String result = retyped(source, new SignatureType.Typed(
                new ValueTypes.Parameterized(Map.class, List.of(String.class, Integer.class)), "null"));
        assertEquals(source, result);
    }

    @Test
    void aPickedClassThatOnlySharesASimpleNameIsWritten() {
        String source = """
                package com.mybot;
                import a.Point;
                public class Subject {
                    public void f(Point m) { }
                }
                """;
        String result = retyped(source, new SignatureType.Typed(new ValueTypes.BotClass("b.Point", List.of()), "null"));
        assertNotEquals(source, result, "the file named a.Point and the pick is b.Point");
    }

    @Test
    void theChoosersPickComparesByItsWholeName() {
        String source = """
                package com.mybot;
                public class Subject {
                    public void f(a.Point m) { }
                }
                """;
        assertNotEquals(source, retyped(source,
                MethodSignatures.signatureTypeOf(ResolvedType.named("b.Point"), PluginHost.grammar())));
        assertEquals(source, retyped(source,
                MethodSignatures.signatureTypeOf(ResolvedType.named("a.Point"), PluginHost.grammar())));
    }

    @Test
    void aTypeWithItsArgumentsIsNotTheRawOne() {
        String source = """
                package com.mybot;
                import java.util.List;
                public class Subject {
                    public void f(List<String> m) { }
                }
                """;
        assertNotEquals(source, retyped(source, SignatureType.kept("List")));
    }

    @Test
    void aPickedJdkClassIsNotTheBotsOfTheSameName() {
        String source = """
                package com.mybot;
                import com.mybot.time.Duration;
                public class Subject {
                    public void f(Duration m) { }
                }
                """;
        assertNotEquals(source, retyped(source, new SignatureType.Typed(Duration.class, "null")));
    }

    @Test
    void aNestedClassAsAnArgumentIsLeftAsWritten() {
        String source = """
                package com.mybot;
                import java.util.List;
                import java.util.Map;
                public class Subject {
                    public void f(List<Map.Entry> m) { }
                }
                """;
        assertEquals(source, retyped(source, new SignatureType.Typed(
                new ValueTypes.Parameterized(List.class, List.of(Map.Entry.class)), "null")));
    }

    @Test
    void theBotsOwnClassIsNotTheCataloguesOfTheSameName() {
        String mine = """
                package com.mybot;
                import com.mybot.time.Duration;
                public class Subject {
                    public void f(Duration wait) { }
                }
                """;
        String theirs = mine.replace("com.mybot.time.Duration", "java.time.Duration");
        assertTrue(draftedParameter(mine).isKept(), "a class of the bot is carried");
        assertFalse(draftedParameter(theirs).isKept(), "java.time.Duration is the catalogue's");
    }

    private static SignatureType draftedParameter(String source) {
        return MethodSignatures.draftOf(first(unit(source)), PluginHost.grammar()).orElseThrow()
                .parameters().getFirst().type();
    }

    @Test
    void everySpellingOfAnArrayIsOneSignature() {
        assertEquals(key("int[] x"), key("int x[]"));
        assertEquals(key("int[] x"), key("int... x"));
        assertNotEquals(key("int x"), key("int x[]"));
        assertNotEquals(key("int x"), key("int... x"));
    }

    private static String key(String declared) {
        return MethodSignatures.keyOf(first(unit("class Subject { void f(" + declared + ") { } }")));
    }

    @Test
    void anArrayTypeIsTheShapeADraftRefuses() {
        CompilationUnit unit = unit("class Subject { void f(java.util.List<int[]> fine, int[] notFine) { } }");
        assertTrue(MethodSignatures.unrepresentable(first(unit)).orElseThrow().contains("notFine"));
    }

    @Test
    void everyMainTheJvmStartsIsLocked() {
        for (String args : List.of("String[] args", "String args[]", "String... args", "java.lang.String[] args")) {
            MethodDeclaration main = first(unit("class Subject { public static void main(" + args + ") { } }"));
            assertTrue(LockResolver.isEntryPointMain(main), args);
            assertTrue(FileTypeDetector.isJvmMain(main), args);
        }
        assertFalse(LockResolver.isEntryPointMain(first(unit("class Subject { public static void main(int[] args) { } }"))));
    }

    @Test
    void anInstantiationOfTheSameClassKeepsItsTypeArguments() {
        String source = """
                package com.mybot;
                import java.util.ArrayList;
                public class Subject {
                    Object made = make();
                    public Object make() { return new ArrayList<String>(); }
                }
                """;
        CompilationUnit unit = unit(source);
        ClassInstanceCreation[] found = new ClassInstanceCreation[1];
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(ClassInstanceCreation creation) {
                found[0] = creation;
                return true;
            }
        });
        EditContext ctx = EditContext.of(unit, new ProjectAnalyzer(null, new ProjectState()), null);
        String result = InstantiationHandler.updateInstantiation(ctx, source, found[0], ResolvedType.named("ArrayList"), null);
        assertTrue(result.contains("new ArrayList<String>()"), result);
    }
}
