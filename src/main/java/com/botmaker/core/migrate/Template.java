package com.botmaker.core.migrate;

import com.botmaker.java.edit.helpers.AstRewriteHelper;
import com.botmaker.java.edit.helpers.Precedence;
import com.botmaker.java.grammar.SourceParser;
import com.botmaker.java.parse.JavaLevel;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BreakStatement;
import org.eclipse.jdt.core.dom.ContinueStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.LabeledStatement;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.TypeMethodReference;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A rule's body with a site's parts put where its parameters stand. The body is never run (2026-10-16).
 *
 * <p>Since 2026-11-05 the body is a tree, parsed once per rule ({@link #body}), and filled through an
 * {@link ASTRewrite}: each use of a parameter is replaced by its part. A part is the site's own text, with the uses
 * inside it already rewritten, so it keeps the spelling and the comments the bot wrote it with; it goes in as a
 * placeholder node, in a {@link ParenthesizedExpression} where {@link Precedence} says its place needs one.
 *
 * <p>A simple name spelled like a parameter is that parameter: only a member's, a type's, a label's or an annotation
 * member's own name and the right of a qualified name are left alone. A body that declares a name a parameter
 * has (a lambda's own {@code file}) is refused: which is which is not clear.
 */
public final class Template {

    private Template() {}

    /**
     * One part of a site: its text, with the uses inside it already rewritten.
     *
     * @param shape the expression it binds as, for {@link Precedence}; {@code null} when no operator can split it
     */
    public record Part(String text, Expression shape) {

        /** The receiver a site leaves unwritten. */
        static final Part THIS = new Part("this", null);

        public static Part of(Expression node, String text) {
            return new Part(text, node);
        }
    }

    /** A rule's body, parsed: its one expression or statement, and the text it was read from. */
    public record Body(ASTNode root, String source) {}

    /**
     * A body filled in.
     *
     * @param shape the expression what it became binds as; {@code null} for a statement
     */
    public record Filled(String text, Expression shape) {}

    /**
     * {@code body} read as one expression, or as one statement when {@code statement}; empty when it is neither.
     */
    public static Optional<Body> body(String body, boolean statement) {
        ASTNode root = statement ? statement(body) : SourceParser.expression(JavaLevel.LATEST, body);
        return root == null ? Optional.empty() : Optional.of(new Body(root, body));
    }

    /**
     * {@code body} with each parameter replaced by its part, or empty when it declares a parameter's name.
     *
     * @throws AstRewriteHelper.RewriteFailed when the rewrite cannot be applied
     */
    public static Optional<Filled> filled(Body body, Map<String, Part> parts) {
        ASTNode root = body.root();
        List<SimpleName> uses = uses(root, parts.keySet());
        if (uses == null) return Optional.empty();
        AST ast = root.getAST();
        ASTRewrite rewrite = ASTRewrite.create(ast);
        Expression shape = root instanceof Expression expression ? expression : null;
        for (SimpleName use : uses) {
            Part part = parts.get(use.getIdentifier());
            Expression put = (Expression) rewrite.createStringPlaceholder(part.text(), ASTNode.SIMPLE_NAME);
            if (use == root) shape = part.shape();
            if (Precedence.needsParentheses(part.shape(), use)) {
                ParenthesizedExpression wrapped = ast.newParenthesizedExpression();
                wrapped.setExpression(put);
                put = wrapped;
            }
            rewrite.replace(use, put, null);
        }
        String all = AstRewriteHelper.applyRewrite(rewrite, body.source());
        int from = root.getStartPosition();
        int to = from + root.getLength() + all.length() - body.source().length();
        return Optional.of(new Filled(all.substring(from, to), shape));
    }

    /**
     * The expression {@code body} with each parameter replaced by a copy of its part: for parts that are values the
     * host wrote, with no spelling of the bot's to keep. Empty when {@code body} is no expression, declares a
     * parameter's name, or uses a parameter where only a name fits.
     */
    public static Optional<Expression> substituted(String body, Map<String, Expression> parts) {
        Expression root = SourceParser.expression(JavaLevel.LATEST, body);
        if (root == null) return Optional.empty();
        List<SimpleName> uses = uses(root, parts.keySet());
        if (uses == null) return Optional.empty();
        AST ast = root.getAST();
        Expression result = root;
        try {
            for (SimpleName use : uses) {
                Expression put = Precedence.wrapIfNeeded(ast, JavaLevel.copy(ast, parts.get(use.getIdentifier())), use);
                ASTNode replaced = use;
                // A qualifier that is no longer a name makes the qualified name a field read, all the way up.
                while (replaced.getLocationInParent() == QualifiedName.QUALIFIER_PROPERTY && !(put instanceof Name)) {
                    QualifiedName qualified = (QualifiedName) replaced.getParent();
                    FieldAccess read = ast.newFieldAccess();
                    read.setExpression(put);
                    read.setName(ast.newSimpleName(qualified.getName().getIdentifier()));
                    put = read;
                    replaced = qualified;
                }
                if (replaced == root) result = put;
                else replace(replaced, put);
            }
        } catch (ClassCastException | IllegalArgumentException onlyAName) {
            return Optional.empty();
        }
        return Optional.of(result);
    }

    /** {@code node} replaced by {@code value} in its parent. */
    @SuppressWarnings("unchecked")
    private static void replace(ASTNode node, ASTNode value) {
        StructuralPropertyDescriptor at = node.getLocationInParent();
        ASTNode parent = node.getParent();
        if (at.isChildListProperty()) {
            List<ASTNode> list = (List<ASTNode>) parent.getStructuralProperty(at);
            list.set(list.indexOf(node), value);
        } else {
            parent.setStructuralProperty(at, value);
        }
    }

    /**
     * Each simple name under {@code root} that is one of {@code parameters}, or {@code null} when {@code root}
     * declares one of them.
     */
    private static List<SimpleName> uses(ASTNode root, Set<String> parameters) {
        List<SimpleName> uses = new ArrayList<>();
        boolean[] shadowed = {false};
        root.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName name) {
                if (!parameters.contains(name.getIdentifier())) return false;
                StructuralPropertyDescriptor at = name.getLocationInParent();
                if (at == VariableDeclarationFragment.NAME_PROPERTY || at == SingleVariableDeclaration.NAME_PROPERTY) {
                    shadowed[0] = true;
                } else if (!ownName(at)) {
                    uses.add(name);
                }
                return false;
            }
        });
        return shadowed[0] ? null : uses;
    }

    /** Whether a simple name at {@code at} names a member, a type or a label rather than reads a variable. */
    private static boolean ownName(StructuralPropertyDescriptor at) {
        return at == MethodInvocation.NAME_PROPERTY || at == FieldAccess.NAME_PROPERTY
                || at == QualifiedName.NAME_PROPERTY || at == SimpleType.NAME_PROPERTY
                || at == MemberValuePair.NAME_PROPERTY || at == SuperMethodInvocation.NAME_PROPERTY
                || at == SuperFieldAccess.NAME_PROPERTY || at == ExpressionMethodReference.NAME_PROPERTY
                || at == TypeMethodReference.NAME_PROPERTY || at == SuperMethodReference.NAME_PROPERTY
                || at == MethodDeclaration.NAME_PROPERTY || at == LabeledStatement.LABEL_PROPERTY
                || at == BreakStatement.LABEL_PROPERTY || at == ContinueStatement.LABEL_PROPERTY;
    }

    /** {@code source} as one statement, or {@code null} when it is not exactly one. */
    private static Statement statement(String source) {
        Block block = SourceParser.statements(JavaLevel.LATEST, source);
        if (block == null || block.statements().size() != 1) return null;
        return (Statement) block.statements().getFirst();
    }
}
