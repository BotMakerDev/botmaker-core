package com.botmaker.core.edit;

import com.botmaker.java.edit.BlockId;
import com.botmaker.java.grammar.SourceParser;
import com.botmaker.java.parse.JavaLevel;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an edit did to a file's methods, as {@code MethodsChanged} says it. A method's id is its place, so one added or
 * removed renumbers those after it, and each of those counts as changed too; its id then names no method of the new text,
 * unless the next one moved into that place.
 *
 * @param changed every method whose source or id is new
 * @param removed the ids the file no longer has
 */
record MethodDiff(List<ChangedMethod> changed, List<String> removed) {

    static MethodDiff between(JavaLevel level, String before, String after) {
        Map<String, String> old = new LinkedHashMap<>();
        for (MethodDeclaration method : methods(SourceParser.parse(level, before))) {
            old.put(BlockId.of(method), sourceOf(method, before));
        }
        List<ChangedMethod> changed = new ArrayList<>();
        for (MethodDeclaration method : methods(SourceParser.parse(level, after))) {
            String id = BlockId.of(method);
            String text = sourceOf(method, after);
            if (!text.equals(old.remove(id))) {
                changed.add(new ChangedMethod(id, method.getName().getIdentifier(), text, nodes(method)));
            }
        }
        // What is left of the old ones has no method at its id now; one whose text changed was taken out above.
        return new MethodDiff(changed, List.copyOf(old.keySet()));
    }

    /** The file's methods; one inside another's anonymous class is part of that method's source. */
    private static List<MethodDeclaration> methods(CompilationUnit unit) {
        List<MethodDeclaration> found = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                found.add(method);
                return false;
            }
        });
        return found;
    }

    private static String sourceOf(MethodDeclaration method, String code) {
        return code.substring(startOf(method), method.getStartPosition() + method.getLength());
    }

    /** From the first modifier on: a method's start is its Javadoc's, and that is not what a client draws. */
    private static int startOf(MethodDeclaration method) {
        Javadoc doc = method.getJavadoc();
        if (doc == null) return method.getStartPosition();
        int first = method.getName().getStartPosition();
        if (!method.modifiers().isEmpty()) first = Math.min(first, ((ASTNode) method.modifiers().getFirst()).getStartPosition());
        if (!method.typeParameters().isEmpty()) first = Math.min(first, ((ASTNode) method.typeParameters().getFirst()).getStartPosition());
        if (method.getReturnType2() != null) first = Math.min(first, method.getReturnType2().getStartPosition());
        return first;
    }

    private static List<ChangedMethod.Node> nodes(MethodDeclaration method) {
        int start = startOf(method);
        int end = method.getStartPosition() + method.getLength();
        List<ChangedMethod.Node> nodes = new ArrayList<>();
        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(Javadoc doc) {
                return false;
            }

            @Override
            public void preVisit(ASTNode node) {
                int at = node.getStartPosition();
                if (node == method) {
                    nodes.add(new ChangedMethod.Node(BlockId.of(node), 0, end - start));
                    return;
                }
                if (node instanceof Javadoc || at < start || at + node.getLength() > end) return;
                nodes.add(new ChangedMethod.Node(BlockId.of(node), at - start, node.getLength()));
            }
        });
        return nodes;
    }
}
