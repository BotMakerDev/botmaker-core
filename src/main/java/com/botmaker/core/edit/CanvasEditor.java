package com.botmaker.core.edit;

import com.botmaker.core.parser.CodeEditor;
import com.botmaker.core.project.ProjectState;
import com.botmaker.java.edit.BlockId;
import com.botmaker.java.edit.ExpressionChoice;
import com.botmaker.java.edit.palette.ExpressionType;
import com.botmaker.java.edit.palette.FunctionDraft;
import com.botmaker.java.edit.refactor.MethodReferences;
import com.botmaker.java.edit.refactor.SignatureMigration;
import com.botmaker.java.grammar.SourceParser;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.CompilationUnit;
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

/**
 * Runs one {@link CanvasEdit} on the {@link CodeEditor} over the open file: its ids become the nodes they name in the
 * live tree, and from there the edit is the editor's own, with its locks, its guards and its words to the user.
 */
final class CanvasEditor {

    /** An id the live tree has no node of that kind for: the client's view of the file is older than the file. */
    static final class NotInTree extends RuntimeException {
        NotInTree(String id) {
            super("Nothing here has the id " + id + ". The file changed; try again.");
        }
    }

    private final ProjectState state;
    private final CodeEditor editor;

    CanvasEditor(ProjectState state, CodeEditor editor) {
        this.state = state;
        this.editor = editor;
    }

    /** Runs {@code edit}; false when the edit itself says it wrote nothing. A plain no-op is judged by what was written. */
    boolean run(CanvasEdit edit) {
        switch (edit) {
            case CanvasEdit.SetLiteral e -> editor.replaceLiteralValue(at(e.id(), Expression.class), e.text());
            case CanvasEdit.SetCharacter e -> editor.setCharacter(at(e.id(), CharacterLiteral.class), e.text());
            case CanvasEdit.SetNumberToken e -> editor.setNumberToken(at(e.id(), NumberLiteral.class), e.text());
            case CanvasEdit.SetTextBlock e -> editor.setTextBlock(at(e.id(), TextBlock.class), e.text());
            case CanvasEdit.SetExpressionSource e -> editor.replaceExpressionSource(at(e.id(), Expression.class), e.text());
            case CanvasEdit.SetSimpleName e -> editor.replaceSimpleName(at(e.id(), SimpleName.class), e.name());
            case CanvasEdit.SetValue e -> editor.replaceWithValue(at(e.id(), Expression.class), e.value());
            case CanvasEdit.SetExpressionType e -> editor.setExpressionType(at(e.id(), ASTNode.class), e.text());
            case CanvasEdit.SetAssignmentOperator e -> editor.updateAssignmentOperator(at(e.id(), ASTNode.class), e.symbol());
            case CanvasEdit.SetBinaryOperator e -> editor.updateBinaryOperator(at(e.id(), ASTNode.class), e.symbol());
            case CanvasEdit.Replace e -> replace(at(e.id(), Expression.class), e.pick());
            case CanvasEdit.FillEmptySlot e ->
                    editor.fillEmptySlotFromSelection(at(e.ownerId(), ASTNode.class), e.pick().value(), e.expected());
            case CanvasEdit.RenameLocal e -> editor.renameLocal(at(e.nameId(), SimpleName.class), e.name());

            case CanvasEdit.SetCall e ->
                    editor.updateMethodInvocation(at(e.id(), MethodInvocation.class), e.scope(), e.method(), e.parameterTypes());
            case CanvasEdit.SetInstantiation e ->
                    editor.updateInstantiation(at(e.id(), ClassInstanceCreation.class), e.typeName(), e.parameterTypes());
            case CanvasEdit.AddStringArgument e ->
                    editor.addStringArgumentToMethodInvocation(at(e.callId(), MethodInvocation.class), e.text());
            case CanvasEdit.AddVarargsArgument e ->
                    editor.addVarargsArgument(at(e.callId(), MethodInvocation.class), e.elementType());
            case CanvasEdit.DeleteArgument e ->
                    editor.deleteArgumentFromMethodInvocation(at(e.callId(), MethodInvocation.class), e.index());
            case CanvasEdit.SetTrailingArguments e ->
                    editor.setTrailingArguments(at(e.callId(), MethodInvocation.class), e.from(), e.values());

            case CanvasEdit.MoveListElement e -> editor.moveListElement(at(e.listId(), ASTNode.class), e.from(), e.to());
            case CanvasEdit.AddSeededListElement e ->
                    editor.addSeededElementToList(at(e.listId(), ASTNode.class), e.index(), e.elementType());
            case CanvasEdit.InsertIntoList e ->
                    editor.insertIntoList(at(e.listId(), ASTNode.class), e.index(), e.pick().value(), e.elementType());
            case CanvasEdit.DeleteListElement e -> editor.deleteElementFromList(at(e.listId(), ASTNode.class), e.index());

            case CanvasEdit.AddBranchLink e -> editor.addBranchLink(at(e.chainId(), Statement.class), e.after());
            case CanvasEdit.RemoveBranchLink e -> editor.removeBranchLink(at(e.chainId(), Statement.class), e.index());
            case CanvasEdit.AddElse e -> editor.addElseToIfStatement(at(e.ifId(), IfStatement.class));
            case CanvasEdit.DeleteElse e -> editor.deleteElseFromIfStatement(at(e.ifId(), IfStatement.class));
            case CanvasEdit.ConvertElseToElseIf e -> editor.convertElseToElseIf(at(e.ifId(), IfStatement.class));
            case CanvasEdit.AddCase e -> editor.addCaseToSwitch(at(e.switchId(), SwitchStatement.class));
            case CanvasEdit.AddCases e -> editor.addCasesToSwitch(at(e.switchId(), SwitchStatement.class), e.labels());
            case CanvasEdit.MoveCase e -> editor.moveSwitchCase(at(e.caseId(), SwitchCase.class), e.up());
            case CanvasEdit.AddSwitchExpressionCase e ->
                    editor.addCaseToSwitchExpression(at(e.switchId(), SwitchExpression.class));
            case CanvasEdit.RemoveSwitchExpressionCase e ->
                    editor.removeCaseFromSwitchExpression(at(e.switchId(), SwitchExpression.class), e.index());
            case CanvasEdit.NormalizeSwitches ignored -> editor.normalizeSwitches();
            case CanvasEdit.AddCatch e -> editor.addCatchClause(at(e.tryId(), TryStatement.class));
            case CanvasEdit.DeleteCatch e -> editor.deleteCatchClause(at(e.tryId(), TryStatement.class), e.index());
            case CanvasEdit.SetCatchType e -> editor.setCatchType(at(e.catchId(), CatchClause.class), e.text());
            case CanvasEdit.AddFinally e -> editor.addFinallyClause(at(e.tryId(), TryStatement.class));
            case CanvasEdit.DeleteFinally e -> editor.deleteFinallyClause(at(e.tryId(), TryStatement.class));
            case CanvasEdit.SetReturn e -> editor.setReturnExpression(at(e.returnId(), ReturnStatement.class), e.pick().value());

            case CanvasEdit.DeclareLocal e -> editor.declareLocal(e.bodyId(), e.index(), e.name(), e.type());
            case CanvasEdit.SetVariableType e ->
                    editor.replaceVariableType(at(e.id(), VariableDeclarationStatement.class), e.type());
            case CanvasEdit.SetVariableInitializer e ->
                    editor.setVariableInitializer(at(e.id(), VariableDeclarationStatement.class), e.pick().value());
            case CanvasEdit.DeleteVariable e -> editor.deleteVariable(at(e.id(), VariableDeclarationStatement.class), e.fix());
            case CanvasEdit.SetFieldType e -> editor.replaceFieldType(at(e.id(), FieldDeclaration.class), e.typeName());
            case CanvasEdit.SetFieldDefault e -> editor.setFieldInitializerToDefault(at(e.id(), FieldDeclaration.class), e.type());

            case CanvasEdit.InsertBlock e -> throw new IllegalStateException("an insert lands through CoreEditor: " + e);
            case CanvasEdit.AddEnumStatement e -> editor.addEnumStatement(e.bodyId(), e.index(), e.draft());
            case CanvasEdit.InsertStatement e -> insertStatement(e);
            case CanvasEdit.Paste e -> editor.pasteCode(e.bodyId(), e.index(), e.source());
            case CanvasEdit.SetStatementSource e -> editor.replaceStatementSource(at(e.id(), Statement.class), e.source());
            case CanvasEdit.UpdateComment e -> editor.updateComment(comment(e.id()), e.text());
            case CanvasEdit.DeleteComment e -> editor.deleteComment(comment(e.id()));

            case CanvasEdit.FillSlotFromBlock e -> editor.fillSlotFromPalette(at(e.slotId(), Expression.class), e.block());
            case CanvasEdit.FillEmptySlotFromBlock e -> editor.fillEmptySlotFromPalette(at(e.ownerId(), ASTNode.class), e.block());
            case CanvasEdit.MoveBetweenSlots e ->
                    editor.moveExpressionBetweenSlots(at(e.slotId(), Expression.class), at(e.sourceId(), Expression.class));
            case CanvasEdit.FillEmptySlotFromExpression e ->
                    editor.fillEmptySlotFromExpression(at(e.ownerId(), ASTNode.class), at(e.sourceId(), Expression.class));
            case CanvasEdit.MoveLineIntoSlot e ->
                    editor.moveExpressionIntoSlot(at(e.slotId(), Expression.class), at(e.lineId(), ExpressionStatement.class));
            case CanvasEdit.FillEmptySlotFromLine e ->
                    editor.fillEmptySlot(at(e.ownerId(), ASTNode.class), at(e.lineId(), ExpressionStatement.class));
            case CanvasEdit.MoveExpressionToLine e ->
                    editor.moveExpressionToStatement(at(e.sourceId(), Expression.class), e.bodyId(), e.index());
            case CanvasEdit.MoveMember e ->
                    editor.moveBodyDeclaration(at(e.memberId(), BodyDeclaration.class), at(e.typeId(), TypeDeclaration.class), e.index());

            case CanvasEdit.AddMethod e ->
                    editor.addMethodToClass(at(e.typeId(), TypeDeclaration.class), e.name(), e.returnType(), e.index());
            case CanvasEdit.AddFunction e -> editor.addFunctionToClass(at(e.typeId(), TypeDeclaration.class), e.draft(), e.index());
            case CanvasEdit.DeleteMethod e -> editor.deleteMethod(at(e.id(), MethodDeclaration.class));
            case CanvasEdit.RenameParameter e ->
                    editor.renameMethodParameter(at(e.methodId(), MethodDeclaration.class), e.index(), e.name());
            case CanvasEdit.SetSignature e -> setSignature(at(e.methodId(), MethodDeclaration.class), e.before(), e.after());
            case CanvasEdit.RemoveAnnotation e ->
                    editor.removeAnnotation(at(e.ownerId(), BodyDeclaration.class), at(e.annotationId(), Annotation.class));
            case CanvasEdit.AddEnum e -> editor.addEnumToClass(at(e.typeId(), TypeDeclaration.class), e.name(), e.index());
            case CanvasEdit.AddEnumDraft e -> editor.addEnumToClass(at(e.typeId(), TypeDeclaration.class), e.draft(), e.index());
            case CanvasEdit.DeleteEnum e -> editor.deleteEnumFromClass(at(e.id(), EnumDeclaration.class));
            case CanvasEdit.AddEnumConstant e -> editor.addEnumConstant(at(e.enumId(), EnumDeclaration.class), e.name());
            case CanvasEdit.DeleteEnumConstant e -> editor.deleteEnumConstant(at(e.enumId(), EnumDeclaration.class), e.index());

            case CanvasEdit.ApplyRefactor e -> {
                return editor.applyRefactor(e.plan(), at(e.targetId(), ASTNode.class));
            }
            case CanvasEdit.RestoreMethod e -> editor.replaceMethod(e.source(), e.signature());
            case CanvasEdit.RestoreFile e -> editor.replaceFile(e.source());
        }
        return true;
    }

    /** What a menu's pick does to the expression in a slot. */
    private void replace(Expression slot, CanvasEdit.Pick pick) {
        switch (pick.value()) {
            case ExpressionType type -> editor.replaceExpression(slot, type);
            case ExpressionChoice.Method method -> editor.replaceWithMethodCall(slot, method);
            case ExpressionChoice.Constructor made -> editor.replaceWithInstantiation(slot, made.typeName(), made.paramTypes());
            case ExpressionChoice.EnumConstant constant ->
                    editor.replaceWithEnumConstant(slot, constant.typeName(), constant.constantName());
            case ExpressionChoice.Variable variable -> editor.replaceWithVariable(slot, variable.variableName());
            case ExpressionChoice.Field field -> editor.replaceWithFieldReference(slot, field.scope(), field.fieldName());
            case ExpressionChoice.NewVariable fresh ->
                    editor.declareVariableBeforeAndReference(slot, fresh.type(), fresh.name());
            case ExpressionChoice.RawExpression raw -> editor.replaceWithRawExpression(slot, raw.code());
            case ExpressionChoice.ArrayItem item -> editor.replaceWithArrayItem(slot, item.arrayName());
            case null, default -> { /* no pick */ }
        }
    }

    /** The statement as source, parsed at the bot's own level: its text is all that crosses. */
    private void insertStatement(CanvasEdit.InsertStatement edit) {
        Block parsed = SourceParser.statements(state.level(), edit.source());
        if (parsed == null || parsed.statements().size() != 1) {
            throw new IllegalArgumentException("Not one statement: " + edit.source());
        }
        editor.insertStatement(edit.bodyId(), edit.index(), (Statement) parsed.statements().getFirst(), edit.imports());
    }

    /** The core reads the project for the calls itself, so the plan is the file's as it stands, not as a client saw it. */
    private void setSignature(MethodDeclaration method, FunctionDraft before, FunctionDraft after) {
        MethodReferences.Result references = MethodReferences.find(state, method);
        if (references.isRefusal()) throw new IllegalArgumentException(references.refusal());
        editor.applyFunctionSignature(method, after, SignatureMigration.of(before, after, method, references.calls()));
    }

    private <T extends ASTNode> T at(String id, Class<T> kind) {
        return BlockId.find(state.getCompilationUnit().orElseThrow(() -> new NotInTree(id)), id)
                .filter(kind::isInstance).map(kind::cast).orElseThrow(() -> new NotInTree(id));
    }

    /** A comment has no path in the tree; its id is where it starts and how long it is, as {@link BlockId} says. */
    private Comment comment(String id) {
        CompilationUnit unit = state.getCompilationUnit().orElseThrow(() -> new NotInTree(id));
        for (Object each : unit.getCommentList()) {
            if (each instanceof Comment comment && BlockId.of(comment).equals(id)) return comment;
        }
        throw new NotInTree(id);
    }
}
