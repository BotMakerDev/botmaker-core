// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.parser;

import com.botmaker.core.parser.handlers.TypeHandler;
import com.botmaker.core.plugin.PluginHost;
import com.botmaker.core.project.LockResolver.EditKind;
import com.botmaker.java.edit.EditContext;
import com.botmaker.java.edit.ExpressionChoice;
import com.botmaker.java.edit.ImportManager;
import com.botmaker.java.edit.handlers.EnumManipulationHandler;
import com.botmaker.java.edit.handlers.ExpressionFormHandler;
import com.botmaker.java.edit.handlers.InstantiationHandler;
import com.botmaker.java.edit.handlers.OperatorReplacementHandler;
import com.botmaker.java.edit.handlers.RawExpressionHandler;
import com.botmaker.java.edit.helpers.AstRewriteHelper;
import com.botmaker.java.edit.helpers.Precedence;
import com.botmaker.java.edit.palette.ExpressionCatalog;
import com.botmaker.java.edit.palette.ExpressionType;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.types.ProjectAnalyzer;
import com.botmaker.java.types.ResolvedType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TextBlock;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/** One expression: the value in a slot, a declaration's type and initializer, a literal, a name, an operator. */
final class ExpressionEdits {

    private static final Map<String, InfixExpression.Operator> INFIX_OPS = Map.ofEntries(
            Map.entry("+", InfixExpression.Operator.PLUS),
            Map.entry("-", InfixExpression.Operator.MINUS),
            Map.entry("*", InfixExpression.Operator.TIMES),
            Map.entry("/", InfixExpression.Operator.DIVIDE),
            Map.entry("%", InfixExpression.Operator.REMAINDER),
            Map.entry("==", InfixExpression.Operator.EQUALS),
            Map.entry("!=", InfixExpression.Operator.NOT_EQUALS),
            Map.entry(">", InfixExpression.Operator.GREATER),
            Map.entry(">=", InfixExpression.Operator.GREATER_EQUALS),
            Map.entry("<", InfixExpression.Operator.LESS),
            Map.entry("<=", InfixExpression.Operator.LESS_EQUALS),
            Map.entry("&&", InfixExpression.Operator.CONDITIONAL_AND),
            Map.entry("||", InfixExpression.Operator.CONDITIONAL_OR)
    );
    private static final Map<String, Assignment.Operator> ASSIGNMENT_OPS = Map.ofEntries(
            Map.entry("=", Assignment.Operator.ASSIGN),
            Map.entry("+=", Assignment.Operator.PLUS_ASSIGN),
            Map.entry("-=", Assignment.Operator.MINUS_ASSIGN),
            Map.entry("*=", Assignment.Operator.TIMES_ASSIGN),
            Map.entry("/=", Assignment.Operator.DIVIDE_ASSIGN),
            Map.entry("%=", Assignment.Operator.REMAINDER_ASSIGN),
            Map.entry("&=", Assignment.Operator.BIT_AND_ASSIGN),
            Map.entry("|=", Assignment.Operator.BIT_OR_ASSIGN),
            Map.entry("^=", Assignment.Operator.BIT_XOR_ASSIGN),
            Map.entry("<<=", Assignment.Operator.LEFT_SHIFT_ASSIGN),
            Map.entry(">>=", Assignment.Operator.RIGHT_SHIFT_SIGNED_ASSIGN),
            Map.entry(">>>=", Assignment.Operator.RIGHT_SHIFT_UNSIGNED_ASSIGN)
    );
    private static final Map<String, PrefixExpression.Operator> PREFIX_OPS = Map.ofEntries(
            Map.entry("++", PrefixExpression.Operator.INCREMENT),
            Map.entry("--", PrefixExpression.Operator.DECREMENT)
    );
    private static final Map<String, PostfixExpression.Operator> POSTFIX_OPS = Map.ofEntries(
            Map.entry("++", PostfixExpression.Operator.INCREMENT),
            Map.entry("--", PostfixExpression.Operator.DECREMENT)
    );

    private final EditGate gate;
    private final ProjectAnalyzer analyzer;

    ExpressionEdits(EditGate gate, ProjectAnalyzer analyzer) {
        this.gate = gate;
        this.analyzer = analyzer;
    }

    // =================================================================================
    // TYPE / INSTANTIATION
    // =================================================================================

    void replaceVariableType(VariableDeclarationStatement toReplace, ResolvedType newType) {
        gate.edit(toReplace, EditKind.BODY, false, (cu, code) -> TypeHandler.replaceVariableType(gate.ctx(cu), code, toReplace, newType));
    }

    /**
     * Retypes a local to a type the type chooser picked — {@code Map<String, Point>} as readily as {@code int} —
     * written as a tree with its imports, and its initialiser reset to the new type's fresh value.
     */
    void replaceVariableType(VariableDeclarationStatement toReplace, Type newType) {
        gate.edit(toReplace, EditKind.BODY, false, (cu, code) -> TypeHandler.replaceVariableType(gate.ctx(cu), code, toReplace, newType));
    }

    void replaceFieldType(FieldDeclaration fieldDecl, String newTypeName) {
        gate.edit(fieldDecl, EditKind.SIGNATURE, false, (cu, code) -> TypeHandler.replaceFieldType(gate.ctx(cu), code, fieldDecl, ResolvedType.named(newTypeName)));
    }

    void updateInstantiation(ClassInstanceCreation node, String newTypeName, List<ResolvedType> newParamTypes) {
        gate.edit(node, EditKind.BODY, true, (cu, code) -> InstantiationHandler.updateInstantiation(gate.ctx(cu), code, node, ResolvedType.named(newTypeName), newParamTypes));
    }

    void replaceWithInstantiation(Expression toReplace, String typeName, List<ResolvedType> paramTypes) {
        gate.insert(toReplace, EditKind.BODY, true, (cu, code) -> InstantiationHandler.replaceWithInstantiation(gate.ctx(cu), code, toReplace, ResolvedType.named(typeName), paramTypes));
    }

    /**
     * Makes a cast, a type check, a class literal, a declaration or an array creation name {@code typeText};
     * refused, with javac's reason, when that would add a compile error.
     */
    void setExpressionType(ASTNode owner, String typeText) {
        if (typeText == null || typeText.strip().equals(ExpressionFormHandler.typeText(owner))) return;
        gate.insert(owner, EditKind.BODY, false, (cu, code) -> gate.refusedWith(
                ExpressionFormHandler.setType(gate.ctx(cu), code, owner, typeText),
                "\"" + typeText.strip() + "\" is not a type that can go there."));
    }

    // =================================================================================
    // THE VALUE IN A SLOT
    // =================================================================================

    void replaceWithVariable(Expression toReplace, String variableName) {
        gate.insert(toReplace, EditKind.BODY, false, (cu, code) -> replaceNode(cu, code, toReplace, cu.getAST().newSimpleName(variableName)));
    }

    /** Replaces {@code toReplace} with {@code arrayName[0]} — the menu's Array Item. */
    void replaceWithArrayItem(Expression toReplace, String arrayName) {
        gate.insert(toReplace, EditKind.BODY, true, (cu, code) -> replaceNode(cu, code, toReplace,
                NodeCreator.createExpression(gate.ctx(cu), new ExpressionChoice.ArrayItem(arrayName), ResolvedType.UNKNOWN)));
    }

    /** Replaces {@code toReplace} with a ready-made expression snippet (e.g. a capture-source helper call). */
    void replaceWithRawExpression(Expression toReplace, String exprCode) {
        gate.edit(toReplace, EditKind.BODY, false, (cu, code) -> RawExpressionHandler.replaceWithExpression(cu, code, toReplace, exprCode));
    }

    /**
     * Replaces {@code toReplace} with a snippet that names {@code importFqn} by its simple name, adding that
     * import. Lets a picker commit {@code Precision.TIGHT} instead of the fully-qualified form the
     * import-free {@link #replaceWithRawExpression(Expression, String)} above requires.
     */
    void replaceWithRawExpression(Expression toReplace, String exprCode, String importFqn) {
        gate.edit(toReplace, EditKind.BODY, false,
                (cu, code) -> RawExpressionHandler.replaceWithExpression(cu, code, toReplace, exprCode, importFqn));
    }

    /** The same with any number of imports, applied in one rewrite. */
    void replaceWithRawExpression(Expression toReplace, String exprCode, List<String> importFqns) {
        gate.edit(toReplace, EditKind.BODY, false,
                (cu, code) -> RawExpressionHandler.replaceWithExpression(cu, code, toReplace, exprCode,
                        importFqns == null ? List.of() : importFqns));
    }

    /**
     * Replaces {@code toReplace} with a value the host's grammar wrote — its tree copied into this file's,
     * with every import it names, in one rewrite. No text is parsed on the way.
     */
    void replaceWithValue(Expression toReplace, JavaValue value) {
        if (value == null) return;
        gate.edit(toReplace, EditKind.BODY, false, (cu, code) -> {
            ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
            rewriter.replace(toReplace, value.copyInto(cu.getAST()), null);
            for (String type : value.imports()) ImportManager.addImport(cu, rewriter, type);
            return AstRewriteHelper.applyRewrite(rewriter, code);
        });
    }

    /**
     * Declares a new local variable {@code type name = <default>;} just before the statement enclosing
     * {@code toReplace}, then references it in that slot — a single atomic rewrite. Lets the user create a
     * typed variable (e.g. a {@code Direction}) inline from the Variables submenu. Falls back to a plain
     * reference when there is no enclosing block to host the declaration.
     */
    void declareVariableBeforeAndReference(Expression toReplace, ResolvedType type, String name) {
        gate.insert(toReplace, EditKind.BODY, true, (cu, code) -> {
            AST ast = cu.getAST();
            ASTRewrite rewriter = ASTRewrite.create(ast);

            Statement stmt = enclosingBlockStatement(toReplace);
            if (stmt == null) {
                rewriter.replace(toReplace, ast.newSimpleName(name), null);
                return AstRewriteHelper.applyRewrite(rewriter, code);
            }

            VariableDeclarationFragment frag = ast.newVariableDeclarationFragment();
            frag.setName(ast.newSimpleName(name));
            Expression init = NodeCreator.createDefaultInitializer(ast, type);
            if (init != null) frag.setInitializer(init);
            VariableDeclarationStatement decl = ast.newVariableDeclarationStatement(frag);
            decl.setType(ProjectAnalyzer.createSimpleTypeNode(ast, type));

            Block block = (Block) stmt.getParent();
            rewriter.getListRewrite(block, Block.STATEMENTS_PROPERTY).insertBefore(decl, stmt, null);
            rewriter.replace(toReplace, ast.newSimpleName(name), null);
            ImportManager.addImportForSimpleName(cu, rewriter, type.leafType().simpleName(), analyzer,
                    PluginHost.typesOnly());
            return AstRewriteHelper.applyRewrite(rewriter, code);
        });
    }

    /** The nearest ancestor {@link Statement} directly contained in a {@link Block}, or {@code null}. */
    private static Statement enclosingBlockStatement(ASTNode node) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof Statement s && s.getParent() instanceof Block) return s;
        }
        return null;
    }

    void replaceWithEnumConstant(Expression toReplace, String enumType, String constantName) {
        gate.insert(toReplace, EditKind.BODY, false, (cu, code) -> EnumManipulationHandler.replaceWithEnumConstant(gate.ctx(cu), code, toReplace, enumType, constantName));
    }

    /** Replaces with a field reference {@code scope.fieldName} (same {@code QualifiedName} shape as an enum constant). */
    void replaceWithFieldReference(Expression toReplace, String scope, String fieldName) {
        gate.insert(toReplace, EditKind.BODY, false, (cu, code) -> EnumManipulationHandler.replaceWithEnumConstant(gate.ctx(cu), code, toReplace, scope, fieldName));
    }

    void replaceExpression(Expression toReplace, ExpressionType type) {
        gate.insert(toReplace, EditKind.BODY, true, (cu, code) -> replaceExpression(cu, code, toReplace, type, analyzer));
    }

    /** Replaces {@code target} with the Java expression the user typed; refused when it is not one. */
    void replaceExpressionSource(Expression target, String text) {
        if (text == null || text.strip().isEmpty()) return;
        gate.edit(target, EditKind.BODY, false, (cu, code) -> gate.refusedWith(
                ExpressionFormHandler.replaceWithSource(code, target, text),
                "That is not a Java expression, so the value was left as it was."));
    }

    // =================================================================================
    // INITIALIZERS
    // =================================================================================

    /** {@code selection} is an {@link ExpressionType} or an {@link ExpressionChoice} (variable/method/…). */
    void setReturnExpression(ReturnStatement returnStmt, Object selection) {
        gate.edit(returnStmt, EditKind.BODY, true, (cu, code) -> setReturnExpression(cu, code, returnStmt, selection, analyzer));
    }

    /** {@code selection} is an {@link ExpressionType} or an {@link ExpressionChoice} (variable/method/…). */
    void setVariableInitializer(VariableDeclarationStatement varDecl, Object selection) {
        gate.insert(varDecl, EditKind.BODY, true, (cu, code) -> setVariableInitializer(gate.ctx(cu), code, varDecl, selection));
    }

    /** {@code selection} is an {@link ExpressionType} or an {@link ExpressionChoice} (variable/method/…). */
    void setFieldInitializer(FieldDeclaration fieldDecl, Object selection) {
        gate.insert(fieldDecl, EditKind.SIGNATURE, true, (cu, code) -> setFieldInitializer(gate.ctx(cu), code, fieldDecl, selection));
    }

    void setFieldInitializerToDefault(FieldDeclaration fieldDecl, ResolvedType fieldType) {
        ExpressionType defaultType = mapTypeToExpressionType(fieldType.simpleName());
        gate.edit(fieldDecl, EditKind.SIGNATURE, true, (cu, code) -> setFieldInitializer(gate.ctx(cu), code, fieldDecl, defaultType));
    }

    private static ExpressionType mapTypeToExpressionType(String uiTargetType) {
        return switch (uiTargetType) {
            case "number" -> ExpressionCatalog.NUMBER;
            case "boolean" -> ExpressionCatalog.FALSE;
            case "String" -> ExpressionCatalog.TEXT;
            case "list" -> ExpressionCatalog.LIST;
            case "enum" -> ExpressionCatalog.ENUM_CONSTANT;
            default -> ExpressionCatalog.VARIABLE;
        };
    }

    // =================================================================================
    // LITERALS / NAMES
    // =================================================================================

    void replaceLiteralValue(Expression toReplace, String newLiteralValue) {
        gate.edit(toReplace, EditKind.BODY, false, (cu, code) -> replaceLiteral(cu, code, toReplace, newLiteralValue));
    }

    void setCharacter(CharacterLiteral literal, String text) {
        if (text == null || String.valueOf(literal.charValue()).equals(text)) return;
        gate.edit(literal, EditKind.BODY, false, (cu, code) -> gate.refusedWith(
                ExpressionFormHandler.setCharacter(gate.ctx(cu), code, literal, text),
                "A character holds exactly one letter, digit or symbol."));
    }

    void setTextBlock(TextBlock block, String content) {
        if (content == null || content.equals(block.getLiteralValue())) return;
        gate.edit(block, EditKind.BODY, false, (cu, code) -> ExpressionFormHandler.setTextBlock(gate.ctx(cu), code, block, content));
    }

    /** Respells a number literal ({@code 0xFF}, {@code 1_000}); refused when the text is not one number. */
    void setNumberToken(NumberLiteral literal, String token) {
        if (token == null || token.strip().equals(literal.getToken())) return;
        gate.edit(literal, EditKind.BODY, false, (cu, code) -> gate.refusedWith(
                ExpressionFormHandler.setNumberToken(gate.ctx(cu), code, literal, token),
                "\"" + token.strip() + "\" is not a number Java can read."));
    }

    void replaceSimpleName(SimpleName toReplace, String newName) {
        gate.edit(toReplace, EditKind.BODY, false, (cu, code) -> AstRewriteHelper.renameSimpleName(cu, code, toReplace, newName));
    }

    /**
     * Renames a local variable — a declared local, a lambda's, a loop's, a {@code catch}'s or a pattern's
     * variable, a method's parameter from its own body — together with every use of it in its scope
     * ({@code LocalUses.of}). One entry point since 2026-09-27; there were five, each walking its own scope its
     * own way. {@link #replaceSimpleName} changes which variable a use names, and is not a rename.
     */
    void renameLocal(SimpleName declName, String newName) {
        if (declName == null || newName == null || newName.isBlank()
                || newName.strip().equals(declName.getIdentifier())) return;
        gate.edit(declName, EditKind.BODY, false,
                (cu, code) -> AstRewriteHelper.renameLocal(code, declName, newName.strip()));
    }

    // =================================================================================
    // OPERATORS
    // =================================================================================

    void updateAssignmentOperator(ASTNode node, String newOperatorSymbol) {
        if (!gate.canModify(node, EditKind.BODY)) return;
        CompilationUnit cu = gate.getCompilationUnit();
        String code = gate.getCurrentCode();
        String newCode = null;
        if (node instanceof Assignment assignment) {
            Assignment.Operator op = ASSIGNMENT_OPS.get(newOperatorSymbol);
            if (op != null) newCode = OperatorReplacementHandler.replaceAssignmentOperator(cu, code, assignment, op);
        } else if (node instanceof PrefixExpression prefix) {
            PrefixExpression.Operator op = PREFIX_OPS.get(newOperatorSymbol);
            if (op != null) newCode = OperatorReplacementHandler.replacePrefixOperator(cu, code, prefix, op);
        } else if (node instanceof PostfixExpression postfix) {
            PostfixExpression.Operator op = POSTFIX_OPS.get(newOperatorSymbol);
            if (op != null) newCode = OperatorReplacementHandler.replacePostfixOperator(cu, code, postfix, op);
        }
        if (newCode != null) gate.triggerUpdate(newCode, node);
    }

    void updateBinaryOperator(ASTNode node, String newOperatorSymbol) {
        if (!gate.canModify(node, EditKind.BODY)) return;
        if (node instanceof InfixExpression infix) {
            InfixExpression.Operator op = INFIX_OPS.get(newOperatorSymbol);
            if (op != null) {
                String newCode = OperatorReplacementHandler.replaceInfixOperator(gate.getCompilationUnit(),
                        gate.getCurrentCode(), infix, op);
                gate.triggerUpdate(newCode, node);
            }
        }
    }

    // =================================================================================
    // PURE TRANSFORMS — each is (cu, code, …) -> code.
    // =================================================================================

    private static String setVariableInitializer(EditContext ctx, String originalCode, VariableDeclarationStatement varDecl, Object selection) {
        return setFragmentInitializer(ctx, originalCode,
                (VariableDeclarationFragment) varDecl.fragments().getFirst(), varDecl.getType().toString(), selection);
    }

    private static String setFieldInitializer(EditContext ctx, String originalCode, FieldDeclaration fieldDecl, Object selection) {
        return setFragmentInitializer(ctx, originalCode,
                (VariableDeclarationFragment) fieldDecl.fragments().getFirst(), fieldDecl.getType().toString(), selection);
    }

    private static String setFragmentInitializer(EditContext ctx, String originalCode,
                                                 VariableDeclarationFragment fragment, String declaredType, Object selection) {
        ResolvedType contextType = ResolvedType.named(ProjectAnalyzer.unwrapCollectionType(declaredType));
        Expression newExpr = NodeCreator.createExpression(ctx, selection, contextType);
        if (newExpr == null) return originalCode;
        if (fragment.getInitializer() == null) {
            ctx.rewriter().set(fragment, VariableDeclarationFragment.INITIALIZER_PROPERTY, newExpr, null);
        } else {
            ctx.rewriter().replace(fragment.getInitializer(), newExpr, null);
        }
        return ctx.applyTo(originalCode);
    }

    private static String setReturnExpression(CompilationUnit cu, String originalCode, ReturnStatement returnStmt, Object selection, ProjectAnalyzer analyzer) {
        EditContext ctx = EditContext.of(cu, analyzer, PluginHost.typesOnly());
        ResolvedType contextType = ProjectAnalyzer.inferExpectedType(returnStmt.getExpression() != null
                ? returnStmt.getExpression() : returnStmt);
        Expression newExpr = NodeCreator.createExpression(ctx, selection, contextType);
        if (newExpr == null) return originalCode;
        if (returnStmt.getExpression() == null) {
            ctx.rewriter().set(returnStmt, ReturnStatement.EXPRESSION_PROPERTY, newExpr, null);
        } else {
            ctx.rewriter().replace(returnStmt.getExpression(), newExpr, null);
        }
        return ctx.applyTo(originalCode);
    }

    private static String replaceExpression(CompilationUnit cu, String originalCode, Expression toReplace, ExpressionType type, ProjectAnalyzer analyzer) {
        EditContext ctx = EditContext.of(cu, analyzer, PluginHost.typesOnly());
        // The resolved type, not its simple name: a lambda is seeded from the functional interface's method,
        // which only a binding knows.
        ResolvedType contextType = ProjectAnalyzer.inferExpectedType(toReplace);
        Expression newExpression = NodeCreator.createDefaultExpression(ctx, type, contextType);
        if (newExpression == null) return originalCode;
        ctx.rewriter().replace(toReplace, Precedence.wrapIfNeeded(ctx.ast(), newExpression, toReplace), null);
        return ctx.applyTo(originalCode);
    }

    private static String replaceLiteral(CompilationUnit cu, String originalCode, Expression toReplace, String newLiteralValue) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        Expression newExpression;
        if (toReplace instanceof StringLiteral) {
            StringLiteral newString = ast.newStringLiteral();
            newString.setLiteralValue(newLiteralValue);
            newExpression = newString;
        } else if (toReplace instanceof NumberLiteral) {
            newExpression = ast.newNumberLiteral(newLiteralValue);
        } else if (toReplace instanceof BooleanLiteral) {
            newExpression = ast.newBooleanLiteral(Boolean.parseBoolean(newLiteralValue));
        } else {
            return originalCode;
        }
        rewriter.replace(toReplace, newExpression, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    private static String replaceNode(CompilationUnit cu, String originalCode, ASTNode oldNode, ASTNode newNode) {
        ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
        rewriter.replace(oldNode, newNode, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }
}
