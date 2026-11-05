package com.botmaker.core.validation;

import com.botmaker.core.plugin.PluginHost;
import com.botmaker.java.edit.BlockId;
import com.botmaker.java.edit.factories.UnfilledSlot;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * What can be said of a file before it is compiled, read off its tree: nothing here knows what a client drew, and a
 * problem is placed by {@code BlockId}. Because every edit already flows through the type-aware pickers, the realistic
 * authoring errors collapse to a small, structurally detectable set; {@link UnfilledSlot} says which nulls count, since
 * a {@code null} written on purpose is not one of them.
 */
public final class SourceChecks {

    private SourceChecks() {}

    /** Whether {@code node}'s own subtree has an unfilled slot; a nested body's statements answer for themselves. */
    public static boolean hasUnfilledSlot(ASTNode node) {
        if (node == null) return false;
        boolean[] found = {false};
        node.accept(new ASTVisitor() {
            @Override public boolean visit(NullLiteral literal) {
                if (UnfilledSlot.isUnfilled(literal)) found[0] = true;
                return false;
            }
            @Override public boolean visit(Block nested) {
                return nested == node;
            }
        });
        return found[0];
    }

    /** The placeholders in {@code unit} that are unfilled slots, one per slot. */
    public static List<ASTNode> unfilledSlots(CompilationUnit unit) {
        List<ASTNode> slots = new ArrayList<>();
        if (unit == null) return slots;
        unit.accept(new ASTVisitor() {
            @Override public boolean visit(NullLiteral literal) {
                if (UnfilledSlot.isUnfilled(literal)) slots.add(literal);
                return false;
            }
        });
        return slots;
    }

    /** The error an unfilled slot stops a run with, placed on the slot. */
    public static Diagnostic unfilled(ASTNode slot, String source) {
        return at(DiagnosticSeverity.Error, slot.getStartPosition(), slot.getLength(), source,
                "This slot needs one of your variables — choose one before running.");
    }

    /**
     * One warning per call site on a facade that {@code deprecated} says is going: advisory, never blocking, since a
     * deprecated call compiles and runs today and that is the point of a deprecation cycle. Only a qualified call counts
     * ({@code Wait.seconds(1)}): the receiver identifies the class without a binding.
     */
    public static List<Diagnostic> deprecatedCalls(CompilationUnit unit, String source,
                                                   BiPredicate<String, String> deprecated) {
        List<Diagnostic> out = new ArrayList<>();
        if (unit == null || deprecated == null) return out;
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                if (!(node.getExpression() instanceof SimpleName receiver)) return true;
                String className = receiver.getIdentifier();
                if (!PluginHost.isFacadeClass(className)) return true;
                String method = node.getName().getIdentifier();
                if (deprecated.test(className, method)) {
                    out.add(at(DiagnosticSeverity.Warning, node.getStartPosition(), node.getLength(), source,
                            className + "." + method + "() is deprecated and will be removed in a future"
                                    + " SDK version. It still works — open the block's ? for the replacement."));
                }
                return true;
            }
        });
        return out;
    }

    /** A diagnostic over {@code length} characters from {@code start} of {@code source}. */
    public static Diagnostic at(DiagnosticSeverity severity, int start, int length, String source, String message) {
        Diagnostic diagnostic = new Diagnostic();
        diagnostic.setSeverity(severity);
        diagnostic.setRange(new Range(positionOf(source, start), positionOf(source, start + length)));
        diagnostic.setMessage(message);
        return diagnostic;
    }

    /** A source offset as an LSP (line, character) position. */
    public static Position positionOf(String source, int offset) {
        if (source == null || offset < 0) return new Position(0, 0);
        int cap = Math.min(offset, source.length());
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < cap; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new Position(line, cap - lineStart);
    }

    /**
     * The ids of what covers {@code length} characters from {@code start}, innermost first and out to the file's top
     * declaration: a client marks the first one it drew.
     */
    public static List<String> idsAround(CompilationUnit unit, int start, int length) {
        List<String> ids = new ArrayList<>();
        if (unit == null || start < 0) return ids;
        for (ASTNode at = NodeFinder.perform(unit, start, Math.max(0, length)); at != null && at != unit; at = at.getParent()) {
            ids.add(BlockId.of(at));
        }
        return ids;
    }
}
