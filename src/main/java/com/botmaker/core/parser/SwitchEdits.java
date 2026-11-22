// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.parser;

import com.botmaker.core.project.LockResolver.EditKind;
import com.botmaker.java.edit.EditContext;
import com.botmaker.java.edit.SwitchCases;
import com.botmaker.java.edit.factories.InitializerFactory;
import com.botmaker.java.edit.handlers.SwitchNormalizer;
import com.botmaker.java.edit.helpers.AstRewriteHelper;
import com.botmaker.java.types.ResolvedType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BreakStatement;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchExpression;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.util.ArrayList;
import java.util.List;

/** The cases of a {@code switch}, the statement and the value. */
final class SwitchEdits {

    private final EditGate gate;

    SwitchEdits(EditGate gate) {
        this.gate = gate;
    }

    void addCaseToSwitch(SwitchStatement switchStmt) {
        gate.edit(switchStmt, EditKind.BODY, true, (cu, code) -> addCaseToSwitch(cu, code, switchStmt, List.of()));
    }

    /**
     * Appends one case per name in {@code caseLabels} (unqualified constants — enum labels are never qualified),
     * each with its own closing {@code break}, in a single rewrite so "add all remaining cases" is one undo step.
     */
    void addCasesToSwitch(SwitchStatement switchStmt, List<String> caseLabels) {
        if (caseLabels == null || caseLabels.isEmpty()) return;
        gate.edit(switchStmt, EditKind.BODY, true, (cu, code) -> addCaseToSwitch(cu, code, switchStmt, caseLabels));
    }

    /**
     * Puts every {@code switch} in the active file into the shape the editor renders — the missing
     * {@code break} on a falling-through colon case, braces around a bare arrow-rule body — and, despite the
     * name, braces around a bare if/else/loop body (see {@link SwitchNormalizer}). Called when a file is opened,
     * so the {@code break} the switch block draws as
     * fixed case chrome is backed by a real one and every branch it offers as a drop target has a block to drop
     * into. A no-op — no edit, no history entry, no {@code CodeUpdatedEvent} — when both are already true,
     * which is the normal case.
     */
    void normalizeSwitches() {
        CompilationUnit cu = gate.getCompilationUnit();
        if (cu == null) return;
        String newCode = SwitchNormalizer.normalize(cu, gate.getCurrentCode());
        // No target: this normalises every switch in the file, so there is no one block it is editing.
        if (newCode != null) gate.triggerUpdate(newCode, true, null, null);
    }

    /**
     * Adds {@code case <label> -> <value>;} to a switch value, before its {@code default}. The label is one the
     * switch does not use yet — the next enum constant, number or {@code "caseN"} — and the value is a default of
     * the switch's type, so the new case compiles as it lands. An enum whose every constant is already a case
     * has no label left, and that insert is refused by the compile check.
     */
    void addCaseToSwitchExpression(SwitchExpression switchExpr) {
        if (SwitchCases.freshLabel(switchExpr.getAST(), switchExpr) == null) {
            gate.status("This switch already has a case for every label it can take, or its subject's type is "
                    + "unknown.");
            return;
        }
        gate.insert(switchExpr, EditKind.BODY, true, (cu, code) -> {
            EditContext ctx = gate.ctx(cu);
            AST ast = cu.getAST();
            Expression label = SwitchCases.freshLabel(ast, switchExpr);
            if (label == null) return code;
            SwitchCase newCase = ast.newSwitchCase();
            newCase.setSwitchLabeledRule(true);
            newCase.expressions().add(label);
            // `case X -> value;` is an implicit yield once parsed, and JDT will not build one (setImplicit is not
            // public). The same text is an expression statement after the arrow, which is what is written here;
            // the re-parse reads it back as the case's value.
            ITypeBinding type = switchExpr.resolveTypeBinding();
            ExpressionStatement value = ast.newExpressionStatement(type == null ? ast.newNullLiteral()
                    : InitializerFactory.createDefaultInitializer(ctx, ResolvedType.of(type)));
            ListRewrite list = ctx.rewriter().getListRewrite(switchExpr, SwitchExpression.STATEMENTS_PROPERTY);
            insertBeforeDefault(list, defaultCase(switchExpr.statements()), newCase, value);
            return ctx.applyTo(code);
        });
    }

    /**
     * Removes case {@code caseIndex} of a switch value, with what it gives. The last case, or a {@code default}
     * the switch needs to cover every value, cannot go: the compile check refuses the edit that would leave a
     * switch value with nothing to give.
     */
    void removeCaseFromSwitchExpression(SwitchExpression switchExpr, int caseIndex) {
        gate.insert(switchExpr, EditKind.BODY, false, (cu, code) -> {
            ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
            ListRewrite list = rewriter.getListRewrite(switchExpr, SwitchExpression.STATEMENTS_PROPERTY);
            int seen = -1;
            boolean removing = false;
            for (Object o : switchExpr.statements()) {
                if (o instanceof SwitchCase) {
                    seen++;
                    removing = seen == caseIndex;
                }
                if (removing) list.remove((ASTNode) o, null);
            }
            return seen < caseIndex ? code : AstRewriteHelper.applyRewrite(rewriter, code);
        });
    }

    void moveSwitchCase(SwitchCase caseNode, boolean moveUp) {
        gate.edit(caseNode, EditKind.BODY, false, (cu, code) -> moveSwitchCase(cu, code, caseNode, moveUp));
    }

    /**
     * Appends cases to {@code switchStmt}, each closed by its own {@code break} — the break is not optional, see
     * {@code SwitchNormalizer}. With {@code caseLabels} empty a single case is added labelled with the next free
     * ordinal, which is the "+ Add Case" button's behaviour; otherwise one case per given constant name.
     */
    private static String addCaseToSwitch(CompilationUnit cu, String originalCode, SwitchStatement switchStmt,
                                          List<String> caseLabels) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        ListRewrite listRewrite = rewriter.getListRewrite(switchStmt, SwitchStatement.STATEMENTS_PROPERTY);

        List<Expression> labels = new ArrayList<>();
        if (caseLabels.isEmpty()) {
            int count = 0;
            for (Object o : switchStmt.statements()) {
                if (o instanceof SwitchCase) count++;
            }
            labels.add(ast.newNumberLiteral(String.valueOf(count)));
        } else {
            for (String name : caseLabels) labels.add(ast.newSimpleName(name));
        }

        SwitchCase defaultCase = defaultCase(switchStmt.statements());
        for (Expression label : labels) {
            SwitchCase newCase = ast.newSwitchCase();
            newCase.expressions().add(label);
            BreakStatement newBreak = ast.newBreakStatement();
            insertBeforeDefault(listRewrite, defaultCase, newCase, newBreak);
        }
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    /** The {@code default} among a switch's statements, or {@code null} when it has none. */
    private static SwitchCase defaultCase(List<?> statements) {
        for (Object o : statements) {
            if (o instanceof SwitchCase sc && sc.isDefault()) return sc;
        }
        return null;
    }

    /**
     * Writes {@code newCase} and its {@code body} before {@code defaultCase}, or last when there is none. New cases
     * go before {@code default:}, not after it. Java allows either, but a default that isn't last reads as a
     * mistake — and the seeded switch now ships with one, so appending would put every case after it.
     */
    private static void insertBeforeDefault(ListRewrite list, SwitchCase defaultCase, SwitchCase newCase,
                                            Statement body) {
        if (defaultCase != null) {
            list.insertBefore(newCase, defaultCase, null);
            list.insertBefore(body, defaultCase, null);
        } else {
            list.insertLast(newCase, null);
            list.insertLast(body, null);
        }
    }

    private static String moveSwitchCase(CompilationUnit cu, String originalCode, SwitchCase caseNode, boolean moveUp) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        SwitchStatement parent = (SwitchStatement) caseNode.getParent();
        List<Statement> statements = parent.statements();

        List<List<Statement>> chunks = new ArrayList<>();
        List<Statement> currentChunk = null;
        for (Statement stmt : statements) {
            if (stmt instanceof SwitchCase) {
                if (currentChunk != null) chunks.add(currentChunk);
                currentChunk = new ArrayList<>();
            }
            if (currentChunk != null) currentChunk.add(stmt);
        }
        if (currentChunk != null) chunks.add(currentChunk);

        int targetIndex = -1;
        for (int i = 0; i < chunks.size(); i++) {
            if (!chunks.get(i).isEmpty() && chunks.get(i).getFirst() == caseNode) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex == -1) return originalCode;

        int neighborIndex = moveUp ? targetIndex - 1 : targetIndex + 1;
        if (neighborIndex < 0 || neighborIndex >= chunks.size()) return originalCode;

        List<Statement> targetChunk = chunks.get(targetIndex);
        List<Statement> neighborChunk = chunks.get(neighborIndex);
        ListRewrite listRewrite = rewriter.getListRewrite(parent, SwitchStatement.STATEMENTS_PROPERTY);

        if (moveUp) {
            ASTNode insertPoint = neighborChunk.getFirst();
            for (Statement stmt : targetChunk) {
                ASTNode moveTarget = rewriter.createMoveTarget(stmt);
                listRewrite.insertBefore(moveTarget, insertPoint, null);
            }
        } else {
            ASTNode insertPoint = targetChunk.getFirst();
            for (Statement stmt : neighborChunk) {
                ASTNode moveTarget = rewriter.createMoveTarget(stmt);
                listRewrite.insertBefore(moveTarget, insertPoint, null);
            }
        }
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }
}
