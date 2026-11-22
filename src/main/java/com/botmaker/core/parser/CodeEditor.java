// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.parser;

import com.botmaker.core.parser.guard.RefusalJournal;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.project.vcs.BlockDiff;
import com.botmaker.java.edit.BlockId;
import com.botmaker.java.edit.ExpressionChoice;
import com.botmaker.java.edit.UseFix;
import com.botmaker.java.edit.palette.BlockType;
import com.botmaker.java.edit.palette.EnumDraft;
import com.botmaker.java.edit.palette.ExpressionType;
import com.botmaker.java.edit.palette.FunctionDraft;
import com.botmaker.java.edit.refactor.SignatureMigration;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.index.nav.Refactor;
import com.botmaker.java.types.ProjectAnalyzer;
import com.botmaker.java.types.ResolvedType;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchExpression;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.TextBlock;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import java.lang.reflect.Type;
import java.util.List;

/**
 * The single stateful layer of the write pipeline: every public method is a per-edit API call guarded by
 * {@link EditGate#canModify} that publishes a {@code CodeUpdatedEvent} when it lands. It holds those methods and
 * nothing else; each is one call into the class that owns its kind of edit ({@link MemberEdits},
 * {@link ExpressionEdits}, {@link CallEdits}, {@link SlotEdits}, {@link StatementEdits}, {@link SwitchEdits}), which
 * runs a pure {@code (cu, code) -> code} rewrite — a stateless {@code parser/handlers/*} call, or a
 * {@code private static} transform of its own for a bespoke AST shape — through the one {@link EditGate}.
 *
 * <p><b>The gate is where read-only is enforced, and it is the only place that can be.</b> Every mutation of the
 * user's code funnels through it, so a lock checked there holds no matter which button, menu, dropdown or drag
 * reached it. That was not true before: {@code canModify()} tested for a path (`com/botmaker/sdk`) that had not
 * existed for some time, so it always returned true, and the real enforcement was "don't render the control".
 * Every UI path that forgot the check — the expression menu, the method-call dropdown, the separator "+" — was
 * therefore a hole that silently rewrote generated code and persisted it.
 *
 * <p>Not rendering the control is still right, and {@code Part E} does it: a locked block offers no affordance at
 * all. But that is a <em>UX</em> guarantee, and it lives in ~20 block classes that each have to remember. This is
 * the <em>correctness</em> guarantee, and it lives in one place. If a refusal here ever reaches the user, some
 * block forgot — the status message is deliberately user-facing rather than a silent {@code return}, so the gap
 * shows up as a visible "why can't I?" instead of a mystery no-op.
 *
 * <p>A private method here would answer {@link EditGate}'s question of who reached a refused edit with its own name;
 * put it in the class whose edit it serves.
 */
public class CodeEditor {

    private final MemberEdits members;
    private final ExpressionEdits expressions;
    private final CallEdits calls;
    private final SlotEdits slots;
    private final StatementEdits statements;
    private final SwitchEdits switches;

    public CodeEditor(ProjectConfig config, ProjectState state, EditorOutput output, ProjectAnalyzer analyzer) {
        this(config, state, output, analyzer, RefusalJournal.inCacheDir());
    }

    /** As above, writing refusals somewhere other than the cache dir — for a test that asserts on them. */
    public CodeEditor(ProjectConfig config, ProjectState state, EditorOutput output, ProjectAnalyzer analyzer,
                      RefusalJournal journal) {
        EditGate gate = new EditGate(config, state, output, analyzer, journal);
        this.members = new MemberEdits(gate, config, state, analyzer);
        this.expressions = new ExpressionEdits(gate, analyzer);
        this.calls = new CallEdits(gate);
        this.slots = new SlotEdits(gate);
        this.statements = new StatementEdits(gate, state, output);
        this.switches = new SwitchEdits(gate);
    }

    // =================================================================================
    // TYPE / INSTANTIATION
    // =================================================================================

    public void replaceVariableType(VariableDeclarationStatement toReplace, ResolvedType newType) {
        expressions.replaceVariableType(toReplace, newType);
    }

    /** Retypes a local to a type the type chooser picked, its initialiser reset to the new type's fresh value. */
    public void replaceVariableType(VariableDeclarationStatement toReplace, Type newType) {
        expressions.replaceVariableType(toReplace, newType);
    }

    public void replaceFieldType(FieldDeclaration fieldDecl, String newTypeName) {
        expressions.replaceFieldType(fieldDecl, newTypeName);
    }

    public void updateInstantiation(ClassInstanceCreation node, String newTypeName, List<ResolvedType> newParamTypes) {
        expressions.updateInstantiation(node, newTypeName, newParamTypes);
    }

    public void replaceWithInstantiation(Expression toReplace, String typeName, List<ResolvedType> paramTypes) {
        expressions.replaceWithInstantiation(toReplace, typeName, paramTypes);
    }

    public void replaceWithVariable(Expression toReplace, String variableName) {
        expressions.replaceWithVariable(toReplace, variableName);
    }

    /** Replaces {@code toReplace} with {@code arrayName[0]} — the menu's Array Item. */
    public void replaceWithArrayItem(Expression toReplace, String arrayName) {
        expressions.replaceWithArrayItem(toReplace, arrayName);
    }

    /** Replaces {@code toReplace} with a ready-made expression snippet (e.g. a capture-source helper call). */
    public void replaceWithRawExpression(Expression toReplace, String exprCode) {
        expressions.replaceWithRawExpression(toReplace, exprCode);
    }

    /** The same for a snippet that names {@code importFqn} by its simple name, adding that import. */
    public void replaceWithRawExpression(Expression toReplace, String exprCode, String importFqn) {
        expressions.replaceWithRawExpression(toReplace, exprCode, importFqn);
    }

    /** The same with any number of imports, applied in one rewrite. */
    public void replaceWithRawExpression(Expression toReplace, String exprCode, List<String> importFqns) {
        expressions.replaceWithRawExpression(toReplace, exprCode, importFqns);
    }

    /** Replaces {@code toReplace} with a value the host's grammar wrote, with every import it names. */
    public void replaceWithValue(Expression toReplace, JavaValue value) {
        expressions.replaceWithValue(toReplace, value);
    }

    /** Declares {@code type name = <default>;} before the statement around {@code toReplace} and reads it there. */
    public void declareVariableBeforeAndReference(Expression toReplace, ResolvedType type, String name) {
        expressions.declareVariableBeforeAndReference(toReplace, type, name);
    }

    // =================================================================================
    // CALLS
    // =================================================================================

    /** Replaces the arguments of {@code call} from {@code fromIndex} on with exactly {@code values}. */
    public void setTrailingArguments(MethodInvocation call, int fromIndex, List<JavaValue> values) {
        calls.setTrailingArguments(call, fromIndex, values);
    }

    public void updateMethodInvocation(MethodInvocation mi, String newScope, String newMethodName, List<ResolvedType> newParamTypes) {
        calls.updateMethodInvocation(mi, newScope, newMethodName, newParamTypes);
    }

    public void addArgumentToMethodInvocation(MethodInvocation mi, ExpressionType type) {
        calls.addArgumentToMethodInvocation(mi, type);
    }

    public void addArgumentToMethodInvocation(MethodInvocation mi, Expression expr) {
        calls.addArgumentToMethodInvocation(mi, expr);
    }

    public void addStringArgumentToMethodInvocation(MethodInvocation mi, String text) {
        calls.addStringArgumentToMethodInvocation(mi, text);
    }

    /** Appends one more argument of {@code elementType} to {@code mi} — the {@code ＋} a varargs slot renders. */
    public void addVarargsArgument(MethodInvocation mi, ResolvedType elementType) {
        calls.addVarargsArgument(mi, elementType);
    }

    /** Drops the argument at {@code index} from {@code mi} — the {@code ✕} on a varargs argument. */
    public void deleteArgumentFromMethodInvocation(MethodInvocation mi, int index) {
        calls.deleteArgumentFromMethodInvocation(mi, index);
    }

    public void replaceWithMethodCall(Expression toReplace, ExpressionChoice.Method choice) {
        calls.replaceWithMethodCall(toReplace, choice);
    }

    /** {@code bodyId}, here and below, is the body's {@link BlockId}. */
    public void addMethodCallStatement(String bodyId, ExpressionChoice.Method choice, int index) {
        calls.addMethodCallStatement(bodyId, choice, index);
    }

    // =================================================================================
    // METHODS
    // =================================================================================

    public void addConstructorToClass(TypeDeclaration typeDecl) {
        members.addConstructorToClass(typeDecl);
    }

    public void addMethodToClass(TypeDeclaration typeDecl, String methodName, String returnType, int index) {
        members.addMethodToClass(typeDecl, methodName, returnType, index);
    }

    /** Adds a function the user described in the Add Function dialog. See {@link FunctionDraft}. */
    public void addFunctionToClass(TypeDeclaration typeDecl, FunctionDraft draft, int index) {
        members.addFunctionToClass(typeDecl, draft, index);
    }

    /** Applies a whole edited signature — name, return type, parameters — as one edit, to the declaration alone. */
    public void applyFunctionSignature(MethodDeclaration method, FunctionDraft draft) {
        members.applyFunctionSignature(method, draft);
    }

    /** The same change, carried to every call in the project: all of them, or nothing. */
    public void applyFunctionSignature(MethodDeclaration method, FunctionDraft draft,
                                       SignatureMigration.Plan plan) {
        members.applyFunctionSignature(method, draft, plan);
    }

    /** Removes the declaration, and only it: whether anything still calls it was the caller's to ask. */
    public void deleteMethod(MethodDeclaration method) {
        members.deleteMethod(method);
    }

    /**
     * Writes a {@link Refactor} plan — a rename, a fix a refusal offered — as <b>one step</b>, every file it
     * rewrites in the same history entry. A plan that moves a file is refused here.
     *
     * @param target the node the gesture was on, for the lock and the refusal journal
     * @return whether it was written
     */
    public boolean applyRefactor(Refactor.Planned plan, ASTNode target) {
        return members.applyRefactor(plan, target);
    }

    /** Puts the function {@code signature} ({@link BlockDiff#signature}) back as {@code versionSource} has it. */
    public void replaceMethod(String versionSource, String signature) {
        members.replaceMethod(versionSource, signature);
    }

    /** The open file replaced whole by {@code source} — <i>Restore this file</i>, one ↶. */
    public void replaceFile(String source) {
        members.replaceFile(source);
    }

    /** Renames one input, in the declaration and in the body that reads it. */
    public void renameMethodParameter(MethodDeclaration method, int index, String newName) {
        members.renameMethodParameter(method, index, newName);
    }

    public void moveBodyDeclaration(BodyDeclaration decl, TypeDeclaration targetType, int index) {
        members.moveBodyDeclaration(decl, targetType, index);
    }

    /** {@code selection} is an {@link ExpressionType} or an {@link ExpressionChoice} (variable/method/…). */
    public void setReturnExpression(ReturnStatement returnStmt, Object selection) {
        expressions.setReturnExpression(returnStmt, selection);
    }

    // =================================================================================
    // ENUMS / ANNOTATIONS
    // =================================================================================

    public void replaceWithEnumConstant(Expression toReplace, String enumType, String constantName) {
        expressions.replaceWithEnumConstant(toReplace, enumType, constantName);
    }

    /** Replaces with a field reference {@code scope.fieldName} (same {@code QualifiedName} shape as an enum constant). */
    public void replaceWithFieldReference(Expression toReplace, String scope, String fieldName) {
        expressions.replaceWithFieldReference(toReplace, scope, fieldName);
    }

    public void addEnumConstant(EnumDeclaration enumNode, String constantName) {
        members.addEnumConstant(enumNode, constantName);
    }

    public void deleteEnumConstant(EnumDeclaration enumNode, int index) {
        members.deleteEnumConstant(enumNode, index);
    }

    public void addEnumToClass(TypeDeclaration typeDecl, String enumName, int index) {
        members.addEnumToClass(typeDecl, enumName, index);
    }

    /** Adds the enum the Define Enum dialog described as a member of {@code typeDecl} — the class's Add enum. */
    public void addEnumToClass(TypeDeclaration typeDecl, EnumDraft draft, int index) {
        members.addEnumToClass(typeDecl, draft, index);
    }

    public void deleteEnumFromClass(EnumDeclaration enumDecl) {
        members.deleteEnumFromClass(enumDecl);
    }

    /** Removes {@code annotation} from the declaration it sits on — the ✕ on an annotation pill. */
    public void removeAnnotation(BodyDeclaration owner, Annotation annotation) {
        members.removeAnnotation(owner, annotation);
    }

    // =================================================================================
    // LISTS
    // =================================================================================

    public void addElementToList(ASTNode listNode, ExpressionType type, int insertIndex) {
        calls.addElementToList(listNode, type, insertIndex);
    }

    /** Inserts an element built from {@code selection}, an {@link ExpressionType} or an {@link ExpressionChoice}. */
    public void insertIntoList(ASTNode listNode, int insertIndex, Object selection, ResolvedType elementType) {
        calls.insertIntoList(listNode, insertIndex, selection, elementType);
    }

    /** Moves the list element at {@code fromIndex} to {@code toIndex} (used by the per-row up/down buttons). */
    public void moveListElement(ASTNode listNode, int fromIndex, int toIndex) {
        calls.moveListElement(listNode, fromIndex, toIndex);
    }

    /** Adds an element of {@code elementType} to the list, seeded by whichever plugin owns that type. */
    public void addSeededElementToList(ASTNode listNode, int insertIndex, ResolvedType elementType) {
        calls.addSeededElementToList(listNode, insertIndex, elementType);
    }

    public void deleteElementFromList(ASTNode listNode, int elementIndex) {
        calls.deleteElementFromList(listNode, elementIndex);
    }

    // =================================================================================
    // INITIALIZERS / EXPRESSIONS
    // =================================================================================

    /** {@code selection} is an {@link ExpressionType} or an {@link ExpressionChoice} (variable/method/…). */
    public void setVariableInitializer(VariableDeclarationStatement varDecl, Object selection) {
        expressions.setVariableInitializer(varDecl, selection);
    }

    /** {@code selection} is an {@link ExpressionType} or an {@link ExpressionChoice} (variable/method/…). */
    public void setFieldInitializer(FieldDeclaration fieldDecl, Object selection) {
        expressions.setFieldInitializer(fieldDecl, selection);
    }

    public void setFieldInitializerToDefault(FieldDeclaration fieldDecl, ResolvedType fieldType) {
        expressions.setFieldInitializerToDefault(fieldDecl, fieldType);
    }

    public void replaceExpression(Expression toReplace, ExpressionType type) {
        expressions.replaceExpression(toReplace, type);
    }

    public void replaceLiteralValue(Expression toReplace, String newLiteralValue) {
        expressions.replaceLiteralValue(toReplace, newLiteralValue);
    }

    /** Changes which variable a use names; {@link #renameLocal} renames one. */
    public void replaceSimpleName(SimpleName toReplace, String newName) {
        expressions.replaceSimpleName(toReplace, newName);
    }

    /** Renames a local variable or a parameter, from its declaration, together with every use in its scope. */
    public void renameLocal(SimpleName declName, String newName) {
        expressions.renameLocal(declName, newName);
    }

    /**
     * Makes a cast, a type check, a class literal, a declaration or an array creation name {@code typeText};
     * refused, with javac's reason, when that would add a compile error.
     */
    public void setExpressionType(ASTNode owner, String typeText) {
        expressions.setExpressionType(owner, typeText);
    }

    public void setCharacter(CharacterLiteral literal, String text) {
        expressions.setCharacter(literal, text);
    }

    public void setTextBlock(TextBlock block, String content) {
        expressions.setTextBlock(block, content);
    }

    /** Respells a number literal ({@code 0xFF}, {@code 1_000}); refused when the text is not one number. */
    public void setNumberToken(NumberLiteral literal, String token) {
        expressions.setNumberToken(literal, token);
    }

    /** Replaces {@code target} with the Java expression the user typed; refused when it is not one. */
    public void replaceExpressionSource(Expression target, String text) {
        expressions.replaceExpressionSource(target, text);
    }

    public void updateAssignmentOperator(ASTNode node, String newOperatorSymbol) {
        expressions.updateAssignmentOperator(node, newOperatorSymbol);
    }

    public void updateBinaryOperator(ASTNode node, String newOperatorSymbol) {
        expressions.updateBinaryOperator(node, newOperatorSymbol);
    }

    // =================================================================================
    // SLOTS
    // =================================================================================

    /**
     * Fills a slot that holds <em>nothing</em> with a pick from the expression menu.
     *
     * @param owner    the statement around the hole, as {@link #fillEmptySlotFromPalette} takes it
     * @param selection an {@link ExpressionType} or an {@link ExpressionChoice}, as the menu produces
     * @param expected  what the slot expects, for the pick that needs to know — {@link ResolvedType#UNKNOWN}
     *                  where the shape declares nothing, which is the usual answer for a print
     */
    public void fillEmptySlotFromSelection(ASTNode owner, Object selection, ResolvedType expected) {
        slots.fillEmptySlotFromSelection(owner, selection, expected);
    }

    /** Fills an expression slot with a palette block dropped onto it. */
    public void fillSlotFromPalette(Expression toReplace, BlockType type) {
        slots.fillSlotFromPalette(toReplace, type);
    }

    /** Moves a statement's value into an expression slot; the statement it came from is removed. */
    public void moveExpressionIntoSlot(Expression toReplace, ExpressionStatement source) {
        slots.moveExpressionIntoSlot(toReplace, source);
    }

    /** {@link #moveExpressionIntoSlot} for a slot that holds nothing; does nothing when it holds something. */
    public void fillEmptySlot(ASTNode owner, ExpressionStatement source) {
        slots.fillEmptySlot(owner, source);
    }

    /** Moves a value from one expression slot into another; the first is vacated per {@link SlotVacancy}. */
    public void moveExpressionBetweenSlots(Expression toReplace, Expression source) {
        slots.moveExpressionBetweenSlots(toReplace, source);
    }

    /** {@link #moveExpressionBetweenSlots} onto a slot that holds nothing — see {@link #fillEmptySlot}. */
    public void fillEmptySlotFromExpression(ASTNode owner, Expression source) {
        slots.fillEmptySlotFromExpression(owner, source);
    }

    /** Lifts a value out of its slot and drops it into a body as a line of its own. */
    public void moveExpressionToStatement(Expression source, String bodyId, int targetIndex) {
        slots.moveExpressionToStatement(source, bodyId, targetIndex);
    }

    /** Fills an empty expression slot with a palette block, the drag counterpart of picking it from the menu. */
    public void fillEmptySlotFromPalette(ASTNode owner, BlockType type) {
        slots.fillEmptySlotFromPalette(owner, type);
    }

    // =================================================================================
    // STATEMENTS / FLOW
    // =================================================================================

    /** Inserts a declare-a-variable statement as the first statement of {@code method}'s body. */
    public void addLocalVariable(MethodDeclaration method, BlockType.VarDecl decl) {
        statements.addLocalVariable(method, decl);
    }

    public void addStatement(String bodyId, BlockType type, int index) {
        statements.addStatement(bodyId, type, index);
    }

    /** Inserts the enum the Define Enum dialog described. */
    public void addEnumStatement(String bodyId, int index, EnumDraft draft) {
        statements.addEnumStatement(bodyId, index, draft);
    }

    /** Inserts {@code <type> <name> = <fresh value>;}, for any type. */
    public void declareLocal(String bodyId, int index, String name, Type type) {
        statements.declareLocal(bodyId, index, name, type);
    }

    public void deleteStatement(Statement toDelete) {
        statements.deleteStatement(toDelete);
    }

    /** Deletes a variable <em>and settles its uses</em> as {@code fix} says, in one write. */
    public void deleteVariable(VariableDeclarationStatement decl, UseFix fix) {
        statements.deleteVariable(decl, fix);
    }

    public void pasteCode(String bodyId, int index, String codeToPaste) {
        statements.pasteCode(bodyId, index, codeToPaste);
    }

    /** Inserts a statement the host built as a tree, with exactly {@code imports}. One edit, so one undo step. */
    public void insertStatement(String bodyId, int index, Statement statement, List<String> imports) {
        statements.insertStatement(bodyId, index, statement, imports);
    }

    /** Moves a statement; both bodies must permit it, since a move takes code out of one of them. */
    public void moveStatement(String statementId, String sourceBodyId, String targetBodyId, int targetIndex) {
        statements.moveStatement(statementId, sourceBodyId, targetBodyId, targetIndex);
    }

    /** Replaces {@code statement} with Java the user typed; refused when it is not Java statements. */
    public void replaceStatementSource(Statement statement, String newSource) {
        statements.replaceStatementSource(statement, newSource);
    }

    public void deleteElseFromIfStatement(IfStatement ifStmt) {
        statements.deleteElseFromIfStatement(ifStmt);
    }

    public void convertElseToElseIf(IfStatement ifStmt) {
        statements.convertElseToElseIf(ifStmt);
    }

    public void addElseToIfStatement(IfStatement ifStmt) {
        statements.addElseToIfStatement(ifStmt);
    }

    /** Inserts another branch into a chain, after the link at {@code afterIndex}. */
    public void addBranchLink(Statement chain, int afterIndex) {
        statements.addBranchLink(chain, afterIndex);
    }

    /** Removes one branch from a chain, closing it over the gap. Refused for the last remaining link. */
    public void removeBranchLink(Statement chain, int index) {
        statements.removeBranchLink(chain, index);
    }

    public void addCatchClause(TryStatement tryStmt) {
        statements.addCatchClause(tryStmt);
    }

    /** Removes a {@code catch}; refused, with a status line, when it is all that keeps the {@code try} legal. */
    public void deleteCatchClause(TryStatement tryStmt, int index) {
        statements.deleteCatchClause(tryStmt, index);
    }

    public void addFinallyClause(TryStatement tryStmt) {
        statements.addFinallyClause(tryStmt);
    }

    public void deleteFinallyClause(TryStatement tryStmt) {
        statements.deleteFinallyClause(tryStmt);
    }

    /** Makes {@code clause} catch {@code typeText} ({@code IOException}, or {@code A | B}), compiled first. */
    public void setCatchType(CatchClause clause, String typeText) {
        statements.setCatchType(clause, typeText);
    }

    // =================================================================================
    // SWITCH
    // =================================================================================

    public void addCaseToSwitch(SwitchStatement switchStmt) {
        switches.addCaseToSwitch(switchStmt);
    }

    /** Appends one case per name in {@code caseLabels}, each with its own {@code break}, as one undo step. */
    public void addCasesToSwitch(SwitchStatement switchStmt, List<String> caseLabels) {
        switches.addCasesToSwitch(switchStmt, caseLabels);
    }

    /**
     * Puts every {@code switch} in the active file into the shape the editor renders, and braces around a bare
     * if/else/loop body. A no-op, with no history entry, when all of it already is.
     */
    public void normalizeSwitches() {
        switches.normalizeSwitches();
    }

    /** Adds a case with a fresh label and a default value to a switch value, before its {@code default}. */
    public void addCaseToSwitchExpression(SwitchExpression switchExpr) {
        switches.addCaseToSwitchExpression(switchExpr);
    }

    /** Removes case {@code caseIndex} of a switch value, with what it gives. */
    public void removeCaseFromSwitchExpression(SwitchExpression switchExpr, int caseIndex) {
        switches.removeCaseFromSwitchExpression(switchExpr, caseIndex);
    }

    public void moveSwitchCase(SwitchCase caseNode, boolean moveUp) {
        switches.moveSwitchCase(caseNode, moveUp);
    }

    // =================================================================================
    // COMMENTS
    // =================================================================================

    public void updateComment(Comment commentNode, String newText) {
        statements.updateComment(commentNode, newText);
    }

    public void deleteComment(Comment commentNode) {
        statements.deleteComment(commentNode);
    }
}
