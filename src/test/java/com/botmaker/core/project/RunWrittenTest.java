package com.botmaker.core.project;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A group only a run writes is locked whole on the canvas; a parameter group beside it stays the user's. */
class RunWrittenTest {

    private static final ClassLoader PLUGINS = RunWrittenTest.class.getClassLoader();

    private static TypeDeclaration typeOf(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(source.toCharArray());
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);
        return (TypeDeclaration) unit.types().getFirst();
    }

    @Test
    void factsGroupIsLocked() {
        TypeDeclaration type = typeOf("""
                package com.mybot.params.bot;
                import com.botmaker.plugin.api.params.Param;
                import com.botmaker.core.project.managed.TestFacts;
                @Param
                @TestFacts
                public class Progress {
                    public int gems() { return 0; }
                }
                """);
        MethodDeclaration gems = type.getMethods()[0];
        Block body = gems.getBody();

        assertEquals(RunWritten.REASON, LockResolver.managedReason(gems, List.of(), PLUGINS));
        assertEquals(RunWritten.REASON, LockResolver.managedReason((ASTNode) body.statements().getFirst(), List.of(), PLUGINS));
        assertEquals(RunWritten.REASON, LockResolver.managedReason(type, List.of(), PLUGINS));
    }

    @Test
    void anOnDemandImportOrAQualifiedNameFindsItToo() {
        TypeDeclaration star = typeOf("""
                package com.mybot.params.bot;
                import com.botmaker.core.project.managed.*;
                @TestFacts
                public class Progress { public int gems() { return 0; } }
                """);
        TypeDeclaration qualified = typeOf("""
                package com.mybot.params.bot;
                @com.botmaker.core.project.managed.TestFacts
                public class Progress { public int gems() { return 0; } }
                """);

        assertEquals(RunWritten.REASON, LockResolver.managedReason(star.getMethods()[0], List.of(), PLUGINS));
        assertEquals(RunWritten.REASON, LockResolver.managedReason(qualified.getMethods()[0], List.of(), PLUGINS));
    }

    @Test
    void aNestedAnnotationIsFoundThroughItsHoldersImport() {
        TypeDeclaration type = typeOf("""
                package com.mybot.params.bot;
                import com.botmaker.core.project.managed.TestFacts;
                @TestFacts.Inner
                public class Progress { public int gems() { return 0; } }
                """);

        assertEquals(RunWritten.REASON, LockResolver.managedReason(type.getMethods()[0], List.of(), PLUGINS));
    }

    @Test
    void aSingleTypeImportShadowsTheOthers() {
        // Java reads @TestFacts as the imported class, so the on-demand one never counts.
        TypeDeclaration type = typeOf("""
                package com.mybot.params.bot;
                import com.botmaker.core.project.managed.*;
                import com.mybot.own.TestFacts;
                @TestFacts
                public class Progress { public int gems() { return 0; } }
                """);

        assertNull(LockResolver.managedReason(type.getMethods()[0], List.of(), PLUGINS));
    }

    @Test
    void aFileDeclaringAFactsGroupIsTheRuns(@TempDir Path dir) throws IOException {
        Path facts = Files.writeString(dir.resolve("Progress.java"), """
                package com.mybot.params.bot;
                import com.botmaker.core.project.managed.TestFacts;
                @TestFacts
                public class Progress { public int gems() { return 0; } }
                """);
        Path plain = Files.writeString(dir.resolve("Running.java"), """
                package com.mybot.params.bot;
                public class Running { public int rounds() { return 3; } }
                """);

        assertTrue(RunWritten.declaredIn(facts, PLUGINS));
        assertFalse(RunWritten.declaredIn(plain, PLUGINS));
        assertFalse(RunWritten.declaredIn(facts, null));

        Files.writeString(facts, "package com.mybot.params.bot;\npublic class Progress {}\n");
        Files.setLastModifiedTime(facts, FileTime.fromMillis(Files.getLastModifiedTime(facts).toMillis() + 2000));
        assertFalse(RunWritten.declaredIn(facts, PLUGINS));
    }

    @Test
    void plainParamGroupStaysEditable() {
        TypeDeclaration type = typeOf("""
                package com.mybot.params.bot;
                import com.botmaker.plugin.api.params.Param;
                @Param
                public class Running {
                    public int rounds() { return 3; }
                }
                """);

        assertNull(LockResolver.managedReason(type.getMethods()[0], List.of(), PLUGINS));
    }

    @Test
    void anAnnotationOfTheSameNameElsewhereIsNotTheMark() {
        // A class of the bot's own named TestFacts, in its package: no import says it is the plugin's.
        TypeDeclaration type = typeOf("""
                package com.mybot.params.bot;
                @TestFacts
                public class Progress { public int gems() { return 0; } }
                """);

        assertNull(LockResolver.managedReason(type.getMethods()[0], List.of(), PLUGINS));
    }
}
