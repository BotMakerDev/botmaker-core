// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.parser;

import com.botmaker.core.project.LockResolver.EditKind;
import com.botmaker.java.edit.EditContext;
import com.botmaker.java.edit.ExpressionChoice;
import com.botmaker.java.edit.factories.InitializerFactory;
import com.botmaker.java.edit.helpers.AstRewriteHelper;
import com.botmaker.java.edit.palette.BlockType;
import com.botmaker.java.edit.palette.ExpressionType;
import com.botmaker.java.parse.JavaLevel;
import com.botmaker.java.types.ProjectAnalyzer;
import com.botmaker.java.types.ResolvedType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

/** A value dropped into, dragged between or lifted out of expression slots, an empty slot among them. */
final class SlotEdits {

    private final EditGate gate;

    SlotEdits(EditGate gate) {
        this.gate = gate;
    }

    /**
     * Fills a slot that holds <em>nothing</em> with a pick from the expression menu — the menu counterpart of
     * {@link #fillEmptySlotFromPalette}, and the one path an empty slot did not have.
     *
     * <p>Every menu on a slot went through {@code ExpressionMenu.applySelection}, which replaces a node; with
     * no node to replace it returned silently. So a print whose argument had been dragged out could be filled
     * by a drop and by nothing else — the ⊕ beside it opened a menu whose every pick did nothing. (The
     * declaration block had worked around it with a branch of its own onto
     * {@link ExpressionEdits#setVariableInitializer}; this is that branch, generalised to the shape rather than
     * the block.)
     *
     * @param owner    the statement around the hole, as {@link #fillEmptySlotFromPalette} takes it
     * @param selection an {@link ExpressionType} or an {@link ExpressionChoice}, as the menu produces
     * @param expected  what the slot expects, for the pick that needs to know — {@link ResolvedType#UNKNOWN}
     *                  where the shape declares nothing, which is the usual answer for a print
     */
    void fillEmptySlotFromSelection(ASTNode owner, Object selection, ResolvedType expected) {
        if (owner == null || selection == null) return;
        gate.insert(owner, EditKind.BODY, true, (cu, code) -> {
            EditContext context = gate.ctx(cu);
            Expression value = NodeCreator.createExpression(context, selection,
                    expected == null ? ResolvedType.UNKNOWN : expected);
            if (value == null) return code;
            if (!placeInEmptySlot(context.rewriter(), owner, value)) return code;
            return context.applyTo(code);
        });
    }

    /**
     * Fills an expression slot with a palette block dropped onto it — the drag counterpart of picking the same
     * call from the expression menu.
     *
     * <p>The block is built as the statement it normally is and then unwrapped, rather than given a second
     * expression-shaped factory: a {@link BlockType.LibraryCall}'s statement form is only the invocation plus a
     * semicolon, so the statement builder already knows the receiver, the default overload and the import. A
     * block whose statement is not an {@code ExpressionStatement} has no expression to contribute and is left
     * alone — the drag layer refuses those before they reach here, and this is the second door.
     */
    void fillSlotFromPalette(Expression toReplace, BlockType type) {
        gate.insert(toReplace, EditKind.BODY, true, (cu, code) -> {
            EditContext context = gate.ctx(cu);
            Statement built = NodeCreator.createDefaultStatement(context, type, toReplace);
            if (!(built instanceof ExpressionStatement stmt)) return code;
            // copySubtree: the built expression is still parented to the throwaway statement, and a rewrite
            // may only place a node that belongs to no other tree.
            context.rewriter().replace(toReplace, JavaLevel.copy(cu.getAST(), stmt.getExpression()), null);
            return AstRewriteHelper.applyRewrite(context.rewriter(), code);
        });
    }

    /**
     * Moves an existing statement's value into an expression slot: the slot takes the expression, and the
     * statement it came from is removed. Dropping {@code Window.title();} onto a print's argument leaves
     * {@code print(Window.title())} and no orphan line.
     *
     * <p>A move, not a copy, because the alternative is worse in both directions: leaving the statement behind
     * duplicates a call the user dragged away, and an expression statement whose value is now consumed
     * elsewhere is exactly the dead line they were trying to get rid of.
     */
    void moveExpressionIntoSlot(Expression toReplace, ExpressionStatement source) {
        gate.edit(toReplace, EditKind.BODY, true, (cu, code) -> {
            ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
            rewriter.replace(toReplace, JavaLevel.copy(cu.getAST(), source.getExpression()), null);
            rewriter.remove(source, null);
            return AstRewriteHelper.applyRewrite(rewriter, code);
        });
    }

    /**
     * Fills an <em>empty</em> expression slot from an existing statement, which is consumed —
     * {@link #moveExpressionIntoSlot}'s counterpart for a slot that holds nothing to replace.
     *
     * <p>The empty slots are the two shapes the editor draws a {@code ⟨drop here⟩} region for: a declaration
     * with no initialiser ({@code int x;}) and a call with no argument (an empty {@code Print:}). Both were
     * drawn as decoration, refusing every drop, because every fill path here names the expression it is
     * replacing and an empty slot has none — so the one slot in the editor that visibly wants a value was the
     * one that could not take one.
     *
     * <p>Quietly does nothing when {@code owner} is not one of those shapes, or is no longer empty.
     */
    void fillEmptySlot(ASTNode owner, ExpressionStatement source) {
        if (owner == null || source == null) return;
        gate.edit(owner, EditKind.BODY, true, (cu, code) -> {
            ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
            Expression value = (Expression) JavaLevel.copy(cu.getAST(), source.getExpression());
            if (!placeInEmptySlot(rewriter, owner, value)) return code;
            // A move, not a copy — the same bargain moveExpressionIntoSlot makes: the statement's value is now
            // consumed somewhere else, so leaving it behind duplicates the call the user dragged away.
            rewriter.remove(source, null);
            return AstRewriteHelper.applyRewrite(rewriter, code);
        });
    }

    /**
     * Moves a value from one expression slot into another: the target takes it, and the slot it came from is
     * vacated per {@link SlotVacancy}. The drag-out counterpart of {@link #moveExpressionIntoSlot}, which moves
     * a whole line instead.
     */
    void moveExpressionBetweenSlots(Expression toReplace, Expression source) {
        if (toReplace == null || source == null || toReplace == source) return;
        gate.edit(toReplace, EditKind.BODY, true, (cu, code) -> {
            EditContext context = gate.ctx(cu);
            Expression moved = (Expression) JavaLevel.copy(cu.getAST(), source);
            vacateSlot(context, source);
            context.rewriter().replace(toReplace, moved, null);
            return AstRewriteHelper.applyRewrite(context.rewriter(), code);
        });
    }

    /** {@link #moveExpressionBetweenSlots} onto a slot that holds nothing — see {@link #fillEmptySlot}. */
    void fillEmptySlotFromExpression(ASTNode owner, Expression source) {
        if (owner == null || source == null) return;
        gate.edit(owner, EditKind.BODY, true, (cu, code) -> {
            EditContext context = gate.ctx(cu);
            Expression moved = (Expression) JavaLevel.copy(cu.getAST(), source);
            if (!placeInEmptySlot(context.rewriter(), owner, moved)) return code;
            vacateSlot(context, source);
            return AstRewriteHelper.applyRewrite(context.rewriter(), code);
        });
    }

    /**
     * Lifts a value out of its slot and drops it into the body {@code bodyId} names as a line of its own — the
     * reverse of {@link #moveExpressionIntoSlot}, and the reason an {@code if} condition is no longer a one-way
     * door.
     *
     * <p>The caller has already checked {@link SlotVacancy#canStandAlone}; this is the second door, since an
     * expression that is not a statement form would be written as source that does not compile.
     */
    void moveExpressionToStatement(Expression source, String bodyId, int targetIndex) {
        ASTNode targetBody = gate.nodeAt(bodyId);
        if (source == null || targetBody == null || !SlotVacancy.canStandAlone(source)) return;
        gate.edit(targetBody, EditKind.BODY, false, (cu, code) -> {
            EditContext context = gate.ctx(cu);
            AST ast = cu.getAST();
            ExpressionStatement lifted =
                    ast.newExpressionStatement((Expression) JavaLevel.copy(ast, source));
            vacateSlot(context, source);
            ListRewrite targetList = AstRewriteHelper.getListRewriteForBody(context.rewriter(), targetBody);
            StatementEdits.insertIntoList(targetList, targetBody, lifted, targetIndex, code);
            return AstRewriteHelper.applyRewrite(context.rewriter(), code);
        });
    }

    /**
     * Records what {@code source}'s slot becomes once its value has been moved elsewhere: removed outright
     * where a hole is legal, and otherwise filled with the default value for the type the slot expects — the
     * same node a retype writes, so the result is always a slot the user can click or drop onto again.
     *
     * @see SlotVacancy#mayBeEmpty
     */
    private static void vacateSlot(EditContext context, Expression source) {
        if (SlotVacancy.mayBeEmpty(source)) {
            context.rewriter().remove(source, null);
            return;
        }
        ResolvedType expected = ProjectAnalyzer.inferExpectedType(source);
        Expression backfill = InitializerFactory.createDefaultInitializer(context, expected);
        context.rewriter().replace(source, backfill, null);
    }

    /** Fills an empty expression slot with a palette block, the drag counterpart of picking it from the menu. */
    void fillEmptySlotFromPalette(ASTNode owner, BlockType type) {
        if (owner == null || type == null) return;
        gate.insert(owner, EditKind.BODY, true, (cu, code) -> {
            EditContext context = gate.ctx(cu);
            Statement built = NodeCreator.createDefaultStatement(context, type, owner);
            if (!(built instanceof ExpressionStatement stmt)) return code;
            Expression value = (Expression) JavaLevel.copy(cu.getAST(), stmt.getExpression());
            if (!placeInEmptySlot(context.rewriter(), owner, value)) return code;
            return AstRewriteHelper.applyRewrite(context.rewriter(), code);
        });
    }

    /** Where the hole is, given the statement around it. False when there isn't one — the drop is then a no-op. */
    private static boolean placeInEmptySlot(ASTRewrite rewriter, ASTNode owner, Expression value) {
        if (owner instanceof VariableDeclarationStatement decl && decl.fragments().size() == 1
                && decl.fragments().getFirst() instanceof VariableDeclarationFragment fragment) {
            if (fragment.getInitializer() != null) return false;
            rewriter.set(fragment, VariableDeclarationFragment.INITIALIZER_PROPERTY, value, null);
            return true;
        }
        if (owner instanceof ExpressionStatement stmt && stmt.getExpression() instanceof MethodInvocation call
                && call.arguments().isEmpty()) {
            rewriter.getListRewrite(call, MethodInvocation.ARGUMENTS_PROPERTY).insertLast(value, null);
            return true;
        }
        return false;
    }
}
