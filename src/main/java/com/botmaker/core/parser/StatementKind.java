package com.botmaker.core.parser;

import com.botmaker.core.plugin.PluginHost;
import com.botmaker.java.edit.handlers.BranchChainHandler;
import com.botmaker.java.edit.handlers.LambdaCallHandler;
import org.eclipse.jdt.core.dom.*;

import java.util.Optional;

/**
 * What a statement is, as the editor draws it and the assistant's {@code read_tree} names it (2026-11-05, plan phase
 * 9a): Studio's block converter picks a statement's block by this, and the core's tree reads its kind by this, so the
 * two cannot drift. {@link #id} is the spelling {@code read_tree} gives, the block's name without "Block".
 *
 * <p>{@link #SOURCE} is a statement drawn as the Java it is, editable as text; {@link #drawnAsSource} says why.
 */
public enum StatementKind {
    BODY("Body"),
    DECLARE_ENUM("DeclareEnum"),
    VARIABLE_DECLARATION("VariableDeclaration"),
    IF("If"),
    WHILE("While"),
    FOR("For"),
    CLASSIC_FOR("ClassicFor"),
    DO_WHILE("DoWhile"),
    TRY("Try"),
    THROW("Throw"),
    SYNCHRONIZED("Synchronized"),
    ASSERT("Assert"),
    LABELED("Labeled"),
    YIELD("Yield"),
    CONSTRUCTOR_CALL("ConstructorCall"),
    SWITCH("Switch"),
    BREAK("Break"),
    CONTINUE("Continue"),
    RETURN("Return"),
    PRINT("Print"),
    ASSIGNMENT("Assignment"),
    BRANCH_CHAIN("BranchChain"),
    BODY_CALL("BodyCall"),
    EXTERNAL_CALL("ExternalCall"),
    PROJECT_CALL("ProjectCall"),
    EXPRESSION_STATEMENT("ExpressionStatement"),
    COMMENT("Comment"),
    SOURCE("SourceStatement");

    private final String id;

    StatementKind(String id) {
        this.id = id;
    }

    /** The kind as {@code read_tree} spells it. */
    public String id() {
        return id;
    }

    /** What {@code line} of a body is: a statement's kind, or {@link #COMMENT}. */
    public static StatementKind of(ASTNode line) {
        return switch (line) {
            case Comment ignored -> COMMENT;
            case Statement statement -> shapeReason(statement).isPresent() ? SOURCE : modelled(statement);
            default -> SOURCE;
        };
    }

    /**
     * Why {@code statement} is drawn as its own source text, or empty when it has a block of its own: several
     * variables in one declaration, a local class, a body without braces, or a kind no block draws.
     */
    public static Optional<String> drawnAsSource(Statement statement) {
        Optional<String> shape = shapeReason(statement);
        if (shape.isPresent()) return shape;
        if (modelled(statement) == SOURCE) return Optional.of("This statement has no block of its own yet.");
        return Optional.empty();
    }

    /** Why a statement whose kind has a block is still drawn as source, from its shape alone. */
    private static Optional<String> shapeReason(Statement statement) {
        if (statement instanceof TypeDeclarationStatement type && !(type.getDeclaration() instanceof EnumDeclaration)) {
            // A local class, record or interface: a type's members are not statements, and no block draws them.
            return Optional.of("A class declared inside a method is shown as written.");
        }
        if (statement instanceof VariableDeclarationStatement variables && variables.fragments().size() > 1) {
            // The declare block draws one name and one value. `int a = 1, b;` drew as `a = 1` and hid `b`, which
            // an edit to the block then rewrote from what it showed.
            return Optional.of("It declares several variables at once; the variable block holds one.");
        }
        // A body that is one bare statement is braced when the file opens (SwitchNormalizer); a file that is not
        // normalised — locked, read-only — still arrives with one, and drawing the block without its body would
        // hide that statement. It is drawn as written instead.
        if (hasBareBody(statement)) {
            return Optional.of("Its body is a single statement without braces, and this file is not rewritten to add them.");
        }
        return Optional.empty();
    }

    /** Whose call {@code call} is drawn as: a plugin's facade, a library's or the JDK's, or the bot's own. */
    public static StatementKind ofCall(MethodInvocation call) {
        String scope = call.getExpression() != null ? call.getExpression().toString() : "";
        if (PluginHost.isFacadeClass(scope)) return EXTERNAL_CALL;
        return CallOwner.of(call.resolveMethodBinding()) == CallOwner.PROJECT ? PROJECT_CALL : EXTERNAL_CALL;
    }

    /** The kind by what {@code statement} is; one that cannot be read (a binding that throws) is drawn as source. */
    private static StatementKind modelled(Statement statement) {
        try {
            return byNode(statement);
        } catch (RuntimeException unreadable) {
            return SOURCE;
        }
    }

    private static StatementKind byNode(Statement statement) {
        return switch (statement) {
            case Block ignored -> BODY;
            case TypeDeclarationStatement ignored -> DECLARE_ENUM;
            case VariableDeclarationStatement ignored -> VARIABLE_DECLARATION;
            case IfStatement ignored -> IF;
            case WhileStatement ignored -> WHILE;
            case EnhancedForStatement ignored -> FOR;
            case ForStatement ignored -> CLASSIC_FOR;
            case DoStatement ignored -> DO_WHILE;
            case TryStatement ignored -> TRY;
            case ThrowStatement ignored -> THROW;
            case SynchronizedStatement ignored -> SYNCHRONIZED;
            case AssertStatement ignored -> ASSERT;
            case LabeledStatement ignored -> LABELED;
            case YieldStatement ignored -> YIELD;
            case ConstructorInvocation ignored -> CONSTRUCTOR_CALL;
            case SuperConstructorInvocation ignored -> CONSTRUCTOR_CALL;
            case SwitchStatement ignored -> SWITCH;
            case BreakStatement ignored -> BREAK;
            case ContinueStatement ignored -> CONTINUE;
            case ReturnStatement ignored -> RETURN;
            case ExpressionStatement expression -> ofExpression(expression.getExpression());
            default -> SOURCE;
        };
    }

    private static StatementKind ofExpression(Expression expression) {
        if (SlotVacancy.isPrintStatement(expression)) return PRINT;
        if (expression instanceof Assignment || expression instanceof PostfixExpression
                || expression instanceof PrefixExpression) {
            return ASSIGNMENT;
        }
        if (expression instanceof MethodInvocation call) {
            // A branch chain first: every arm below would draw only its outermost link.
            if (BranchChainHandler.isBranchChain(call)) return BRANCH_CHAIN;
            if (LambdaCallHandler.isLambdaCall(call)) return BODY_CALL;
            return ofCall(call);
        }
        // Any other expression run for its effect — `new Worker(queue);`, `super.stop();`.
        return EXPRESSION_STATEMENT;
    }

    /** Whether {@code statement} is an if or a loop with a one-statement body (or else) written without braces. */
    private static boolean hasBareBody(Statement statement) {
        return switch (statement) {
            case IfStatement i -> !(i.getThenStatement() instanceof Block)
                    || (i.getElseStatement() != null && !(i.getElseStatement() instanceof Block)
                    && !(i.getElseStatement() instanceof IfStatement));
            case WhileStatement w -> !(w.getBody() instanceof Block);
            case DoStatement d -> !(d.getBody() instanceof Block);
            case ForStatement f -> !(f.getBody() instanceof Block);
            case EnhancedForStatement f -> !(f.getBody() instanceof Block);
            default -> false;
        };
    }
}
