// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.project.params;

import com.botmaker.core.project.managed.ManagedHolders;
import com.botmaker.java.edit.helpers.AstRewriteHelper;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.grammar.SourceFormatter;
import com.botmaker.java.grammar.ValueTypes;
import com.botmaker.java.index.PoolReader;
import com.botmaker.java.parse.BotParser;
import com.botmaker.java.parse.JavaLevel;
import com.botmaker.plugin.api.params.Pool;
import com.botmaker.plugin.api.params.Pools;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.IExtendedModifier;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeLiteral;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.lang.invoke.MethodType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The rewrites the Parameters window makes on a bot's {@code @Pools} group (2026-11-16, spec
 * {@code 2026-11-15-run-claims-design.md} §7): one pool's method put or taken out, one source in, one source out.
 * Pure, like {@link JavaParameterEdits}; {@link JavaPools} is the half that knows about files.
 *
 * <p>A pool is written as {@code Pool.of(…)} or {@code Pool.all(X.class)}, then {@code take}, {@code each} and
 * {@code rotate} when they differ from one, one and sticky, then {@code fills(Group::parameter)} last, as
 * {@link PoolReader} reads it back.
 */
public final class PoolEdits {

    private PoolEdits() {}

    /**
     * One pool as the window holds it, its elements as Java text so two readings of the same file compare equal.
     *
     * @param group      the {@code @Pools} class's simple name
     * @param name       the pool's method
     * @param fillsClass the simple name of the {@code @Param} group it fills, in the same package
     * @param fills      the parameter's method
     * @param parameter  the parameter's type
     * @param element    each element's type: the parameter's, or its list's element with {@code take}
     * @param all        every constant of {@code element}, new ones included; {@code elements} is then not written
     * @param elements   the elements as the grammar writes each
     * @param imports    what the elements name
     * @param take       how many elements a run takes; {@code 0} for one
     * @param each       how many live runs share one element
     * @param rotates    the least recently used element first, rather than the run's last one
     */
    public record Shape(String group, String name, String fillsClass, String fills, Type parameter, Type element,
                        boolean all, List<String> elements, List<String> imports, int take, int each, boolean rotates) {

        public Shape {
            elements = List.copyOf(elements);
            imports = List.copyOf(imports);
            each = Math.max(1, each);
            take = Math.max(0, take);
        }

        /** {@code reading} as a shape; empty for a pool that does not read. */
        public static Optional<Shape> of(PoolReader.Reading reading) {
            if (!reading.reads() || reading.fills().isEmpty()) return Optional.empty();
            PoolReader.Filled filled = reading.fills().get();
            Set<String> imports = new LinkedHashSet<>();
            List<String> elements = new ArrayList<>();
            if (reading.allOf().isEmpty()) {
                for (JavaValue value : reading.elements()) {
                    elements.add(value.source());
                    imports.addAll(value.imports());
                }
            }
            return Optional.of(new Shape(simpleName(reading.group()), reading.method(), simpleName(filled.owner()),
                    filled.method(), filled.type(), filled.element(), reading.allOf().isPresent(), elements,
                    List.copyOf(imports), reading.take(), reading.each(), reading.rotates()));
        }

        /** The same pool with no element listed: its starting copy's, which deals nothing until this PC lists some. */
        public Shape blank() {
            return withElements(List.of(), List.of());
        }

        /** The same pool listing {@code listed}, which names {@code named}. */
        public Shape withElements(List<String> listed, List<String> named) {
            // A run cannot take more than are listed: the reader refuses take(3) of two.
            int taking = take > 0 && !listed.isEmpty() ? Math.min(take, listed.size()) : take;
            return new Shape(group, name, fillsClass, fills, parameter, element, all, listed, named, taking, each,
                    rotates);
        }

        /** The same pool dealing every constant of its element, or the ones it lists. */
        public Shape withAll(boolean every) {
            return new Shape(group, name, fillsClass, fills, parameter, element, every, elements, imports, take, each,
                    rotates);
        }

        /** The same pool taking {@code count} elements a run. */
        public Shape withTake(int count) {
            return new Shape(group, name, fillsClass, fills, parameter, element, all, elements, imports, count, each,
                    rotates);
        }

        /** The same pool lending one element to {@code count} live runs. */
        public Shape withEach(int count) {
            return new Shape(group, name, fillsClass, fills, parameter, element, all, elements, imports, take, count,
                    rotates);
        }

        /** The same pool, the least recently used element first when {@code rotating}. */
        public Shape withRotates(boolean rotating) {
            return new Shape(group, name, fillsClass, fills, parameter, element, all, elements, imports, take, each,
                    rotating);
        }

        /** Whether this pool fills {@code className.method}. */
        public boolean fills(String className, String method) {
            return fillsClass.equals(className) && fills.equals(method);
        }
    }

    /** The source of an empty {@code @Pools} group {@code className} in {@code pack}. */
    static String emptyGroup(String pack, String className) {
        AST ast = JavaLevel.LATEST.newAst();
        TypeDeclaration group = ast.newTypeDeclaration();
        group.setName(ast.newSimpleName(className));
        group.setJavadoc(ManagedHolders.javadoc(ast, List.of(
                "Pools of this bot: what several runs at once are dealt from, each filling one parameter. This file",
                "is never versioned; its starting copy is.")));
        MarkerAnnotation pools = ast.newMarkerAnnotation();
        pools.setTypeName(ast.newSimpleName(Pools.class.getSimpleName()));
        @SuppressWarnings("unchecked")
        List<IExtendedModifier> modifiers = group.modifiers();
        modifiers.add(pools);
        modifiers.add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        modifiers.add(ast.newModifier(Modifier.ModifierKeyword.FINAL_KEYWORD));
        return ManagedHolders.unit(ast, pack,
                Set.of(Pools.class.getCanonicalName()), group);
    }

    /**
     * {@code source} with {@code shape}'s method in its group: the method of that name replaced where it is, else
     * appended. Unchanged when there is no such group, a type the grammar cannot name, or an element that does not
     * parse.
     */
    public static String put(String source, Shape shape) {
        return put(source, shape, false);
    }

    /**
     * The same, keeping the elements the method of that name lists already, when it lists some by {@code Pool.of}:
     * a starting copy follows every setting while its author's elements stay its own.
     */
    public static String putKeepingListed(String source, Shape shape) {
        return put(source, shape, true);
    }

    @SuppressWarnings("unchecked")
    private static String put(String source, Shape shape, boolean keepListed) {
        CompilationUnit unit = BotParser.syntax(source);
        AST ast = unit.getAST();
        TypeDeclaration type = group(unit, shape.group());
        if (type == null) return source;
        MethodDeclaration was = method(type, shape.name());
        Set<String> imports = new LinkedHashSet<>(shape.imports());
        imports.add(Pool.class.getCanonicalName());
        List<Expression> kept = keepListed && !shape.all() && was != null ? listed(was) : List.of();
        Optional<Expression> chain = chain(ast, shape, imports);
        if (chain.isPresent() && !kept.isEmpty()) {
            MethodInvocation root = root(chain.get());
            root.typeArguments().clear();
            root.arguments().clear();
            for (Expression element : kept) root.arguments().add(ASTNode.copySubtree(ast, element));
        }
        Optional<org.eclipse.jdt.core.dom.Type> parameter = ValueTypes.node(ast, boxed(shape.parameter()), imports);
        if (chain.isEmpty() || parameter.isEmpty()) return source;
        ParameterizedType returns = ast.newParameterizedType(ast.newSimpleType(ast.newSimpleName(
                Pool.class.getSimpleName())));
        returns.typeArguments().add(parameter.get());
        MethodDeclaration method = ast.newMethodDeclaration();
        method.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        method.setReturnType2(returns);
        method.setName(ast.newSimpleName(shape.name()));
        Block body = ast.newBlock();
        ReturnStatement ret = ast.newReturnStatement();
        ret.setExpression(chain.get());
        body.statements().add(ret);
        method.setBody(body);
        // What the author wrote about the pool stays with it.
        if (was != null && was.getJavadoc() != null) {
            method.setJavadoc((Javadoc) ASTNode.copySubtree(ast, was.getJavadoc()));
        }
        ASTRewrite rewrite = ASTRewrite.create(ast);
        if (was != null) {
            rewrite.replace(was, method, null);
        } else {
            rewrite.getListRewrite(type, TypeDeclaration.BODY_DECLARATIONS_PROPERTY).insertLast(method, null);
        }
        String written = SourceFormatter.formatChanged(JavaLevel.of(rewrite.getAST()), source,
                AstRewriteHelper.applyRewrite(rewrite, source));
        CompilationUnit reparsed = BotParser.syntax(written);
        ASTRewrite importing = ASTRewrite.create(reparsed.getAST());
        JavaParameterEdits.addImports(reparsed, importing, List.copyOf(imports));
        return AstRewriteHelper.applyRewrite(importing, written);
    }

    /** {@code source} without the pool {@code name} of {@code group}; unchanged when it has none. */
    public static String remove(String source, String group, String name) {
        CompilationUnit unit = BotParser.syntax(source);
        TypeDeclaration type = group(unit, group);
        MethodDeclaration was = type == null ? null : method(type, name);
        if (was == null) return source;
        ASTRewrite rewrite = ASTRewrite.create(unit.getAST());
        rewrite.remove(was, null);
        return AstRewriteHelper.applyRewrite(rewrite, source);
    }

    /**
     * The pool's chain as one line of Java, for a window to show under its settings; empty when it cannot be written.
     * Only shown, never written back: {@link #put} writes the tree.
     */
    public static Optional<String> shown(Shape shape) {
        return chain(JavaLevel.LATEST.newAst(), shape, new LinkedHashSet<>()).map(Expression::toString);
    }

    /** Whether {@code source}'s group {@code group} has a method {@code name}. */
    public static boolean has(String source, String group, String name) {
        TypeDeclaration type = group(BotParser.syntax(source), group);
        return type != null && method(type, name) != null;
    }

    /** {@code Pool.of(…)} or {@code Pool.all(X.class)}, then its steps, then {@code fills(Group::parameter)}. */
    @SuppressWarnings("unchecked")
    private static Optional<Expression> chain(AST ast, Shape shape, Set<String> imports) {
        MethodInvocation root = ast.newMethodInvocation();
        root.setExpression(ast.newSimpleName(Pool.class.getSimpleName()));
        Optional<org.eclipse.jdt.core.dom.Type> element = ValueTypes.node(ast, boxed(shape.element()), imports);
        if (element.isEmpty()) return Optional.empty();
        if (shape.all()) {
            root.setName(ast.newSimpleName("all"));
            TypeLiteral literal = ast.newTypeLiteral();
            literal.setType(element.get());
            root.arguments().add(literal);
        } else {
            root.setName(ast.newSimpleName("of"));
            for (String written : shape.elements()) {
                Optional<JavaValue> value = JavaValue.parse(written);
                if (value.isEmpty()) return Optional.empty();
                root.arguments().add(value.get().copyInto(ast));
            }
            // With nothing listed, of() cannot infer its element from the chain: Pool.<Hero>of().
            if (shape.elements().isEmpty()) root.typeArguments().add(element.get());
        }
        Expression at = root;
        if (shape.take() > 0) at = step(ast, at, "take", shape.take());
        if (shape.each() > 1) at = step(ast, at, "each", shape.each());
        if (shape.rotates()) at = step(ast, at, "rotate", -1);
        MethodInvocation fills = ast.newMethodInvocation();
        fills.setExpression(at);
        fills.setName(ast.newSimpleName("fills"));
        ExpressionMethodReference reference = ast.newExpressionMethodReference();
        reference.setExpression(ast.newSimpleName(shape.fillsClass()));
        reference.setName(ast.newSimpleName(shape.fills()));
        fills.arguments().add(reference);
        return Optional.of(fills);
    }

    /** {@code on.name(count)}, or {@code on.name()} for a count below zero. */
    @SuppressWarnings("unchecked")
    private static Expression step(AST ast, Expression on, String name, int count) {
        MethodInvocation call = ast.newMethodInvocation();
        call.setExpression(on);
        call.setName(ast.newSimpleName(name));
        if (count >= 0) call.arguments().add(ast.newNumberLiteral(Integer.toString(count)));
        return call;
    }

    /** The elements {@code method}'s {@code Pool.of(…)} lists; none for {@code Pool.all} or a body it cannot read. */
    private static List<Expression> listed(MethodDeclaration method) {
        if (method.getBody() == null || method.getBody().statements().size() != 1
                || !(method.getBody().statements().getFirst() instanceof ReturnStatement ret)
                || ret.getExpression() == null) {
            return List.of();
        }
        MethodInvocation root = root(ret.getExpression());
        if (root == null || !root.getName().getIdentifier().equals("of")) return List.of();
        List<Expression> elements = new ArrayList<>();
        for (Object argument : root.arguments()) elements.add((Expression) argument);
        return elements;
    }

    /** The first call of a chain, {@code Pool.of(…)} in {@code Pool.of(…).each(2).fills(…)}; null when none. */
    private static MethodInvocation root(Expression chain) {
        MethodInvocation at = chain instanceof MethodInvocation call ? call : null;
        while (at != null && at.getExpression() instanceof MethodInvocation inner) at = inner;
        return at;
    }

    private static TypeDeclaration group(CompilationUnit unit, String name) {
        for (Object each : unit.types()) {
            if (each instanceof TypeDeclaration type && type.getName().getIdentifier().equals(name)) return type;
        }
        return null;
    }

    private static MethodDeclaration method(TypeDeclaration type, String name) {
        for (MethodDeclaration method : type.getMethods()) {
            if (!method.isConstructor() && method.getName().getIdentifier().equals(name)
                    && method.parameters().isEmpty()) {
                return method;
            }
        }
        return null;
    }

    /** {@code type}, boxed when primitive: {@code Pool<Integer>} fills an {@code int} parameter. */
    private static Type boxed(Type type) {
        return type instanceof Class<?> primitive && primitive.isPrimitive()
                ? MethodType.methodType(primitive).wrap().returnType() : type;
    }

    /** {@code com.bot.params.bot.Farm} or {@code …Outer$Farm}: {@code Farm}. */
    static String simpleName(String binary) {
        return binary.substring(Math.max(binary.lastIndexOf('.'), binary.lastIndexOf('$')) + 1);
    }
}
