package com.botmaker.core.edit;

import com.botmaker.java.edit.ExpressionChoice;
import com.botmaker.java.edit.UseFix;
import com.botmaker.java.edit.palette.BlockType;
import com.botmaker.java.edit.palette.EnumDraft;
import com.botmaker.java.edit.palette.ExpressionType;
import com.botmaker.java.edit.palette.FunctionDraft;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.index.nav.Refactor;
import com.botmaker.java.types.ResolvedType;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An edit the user made by hand on the canvas: every gesture beyond the five {@link Edit}s an assistant shares with it.
 * A node is named by its {@code BlockId}, as in every edit, and the core reads that id against the tree on screen.
 *
 * <p><b>By hand only.</b> Some of these carry Java the user typed or brought back ({@link Paste},
 * {@link SetStatementSource}, {@link RestoreFile}), which {@link Edit} otherwise never does, so a request under
 * {@link EditRequest.Guard#EVERY_EDIT} is refused outright. An assistant writes no Java.
 *
 * <p>On the wire each is named by its class, first letter small; {@code CoreJson} registers them.
 */
public sealed interface CanvasEdit extends Edit {

    /** What a menu on a slot gives: a plain expression, or the richer pick of a method, a constant, a variable. */
    record Pick(ExpressionType expression, ExpressionChoice choice) {
        /** A menu's selection, which is one of the two; {@code null} stays no pick. */
        public static Pick of(Object selection) {
            return switch (selection) {
                case null -> new Pick(null, null);
                case ExpressionType type -> new Pick(type, null);
                case ExpressionChoice picked -> new Pick(null, picked);
                default -> throw new IllegalArgumentException("not a menu's selection: " + selection);
            };
        }

        /** The selection, as a menu gave it. */
        public Object value() {
            return expression != null ? expression : choice;
        }
    }

    // Values and names in a slot.
    record SetLiteral(String id, String text) implements CanvasEdit {}
    record SetCharacter(String id, String text) implements CanvasEdit {}
    record SetNumberToken(String id, String text) implements CanvasEdit {}
    record SetTextBlock(String id, String text) implements CanvasEdit {}
    record SetExpressionSource(String id, String text) implements CanvasEdit {}
    record SetSimpleName(String id, String name) implements CanvasEdit {}
    record SetValue(String id, JavaValue value) implements CanvasEdit {}
    record SetExpressionType(String id, String text) implements CanvasEdit {}
    record SetAssignmentOperator(String id, String symbol) implements CanvasEdit {}
    record SetBinaryOperator(String id, String symbol) implements CanvasEdit {}
    /** The slot's expression replaced by what a menu picked. */
    record Replace(String id, Pick pick) implements CanvasEdit {}
    record FillEmptySlot(String ownerId, Pick pick, ResolvedType expected) implements CanvasEdit {}
    record RenameLocal(String nameId, String name) implements CanvasEdit {}

    // Calls and instantiations.
    record SetCall(String id, String scope, String method, List<ResolvedType> parameterTypes) implements CanvasEdit {}
    record SetInstantiation(String id, String typeName, List<ResolvedType> parameterTypes) implements CanvasEdit {}
    record AddStringArgument(String callId, String text) implements CanvasEdit {}
    record AddVarargsArgument(String callId, ResolvedType elementType) implements CanvasEdit {}
    record DeleteArgument(String callId, int index) implements CanvasEdit {}
    record SetTrailingArguments(String callId, int from, List<JavaValue> values) implements CanvasEdit {}

    // Lists.
    record MoveListElement(String listId, int from, int to) implements CanvasEdit {}
    record AddSeededListElement(String listId, int index, ResolvedType elementType) implements CanvasEdit {}
    record InsertIntoList(String listId, int index, Pick pick, ResolvedType elementType) implements CanvasEdit {}
    record DeleteListElement(String listId, int index) implements CanvasEdit {}

    // Statements with parts of their own.
    record AddBranchLink(String chainId, int after) implements CanvasEdit {}
    record RemoveBranchLink(String chainId, int index) implements CanvasEdit {}
    record AddElse(String ifId) implements CanvasEdit {}
    record DeleteElse(String ifId) implements CanvasEdit {}
    record ConvertElseToElseIf(String ifId) implements CanvasEdit {}
    record AddCase(String switchId) implements CanvasEdit {}
    record AddCases(String switchId, List<String> labels) implements CanvasEdit {}
    record MoveCase(String caseId, boolean up) implements CanvasEdit {}
    record AddSwitchExpressionCase(String switchId) implements CanvasEdit {}
    record RemoveSwitchExpressionCase(String switchId, int index) implements CanvasEdit {}
    record NormalizeSwitches() implements CanvasEdit {}
    record AddCatch(String tryId) implements CanvasEdit {}
    record DeleteCatch(String tryId, int index) implements CanvasEdit {}
    record SetCatchType(String catchId, String text) implements CanvasEdit {}
    record AddFinally(String tryId) implements CanvasEdit {}
    record DeleteFinally(String tryId) implements CanvasEdit {}
    record SetReturn(String returnId, Pick pick) implements CanvasEdit {}

    // Variables.
    record DeclareLocal(String bodyId, int index, String name, Type type) implements CanvasEdit {}
    record SetVariableType(String id, Type type) implements CanvasEdit {}
    record SetVariableInitializer(String id, Pick pick) implements CanvasEdit {}
    record DeleteVariable(String id, UseFix fix) implements CanvasEdit {}
    record SetFieldType(String id, String typeName) implements CanvasEdit {}
    record SetFieldDefault(String id, ResolvedType type) implements CanvasEdit {}

    // Lines of a body.
    /** A block picked from a menu, which can carry more than a palette id says: the method it calls, the variable it sets. */
    record InsertBlock(String bodyId, int index, BlockType block) implements CanvasEdit {}
    record AddEnumStatement(String bodyId, int index, EnumDraft draft) implements CanvasEdit {}
    /** A statement the host built from a declared call, with the classes it names. */
    record InsertStatement(String bodyId, int index, String source, List<String> imports) implements CanvasEdit {}
    record Paste(String bodyId, int index, String source) implements CanvasEdit {}
    record SetStatementSource(String id, String source) implements CanvasEdit {}
    record UpdateComment(String id, String text) implements CanvasEdit {}
    record DeleteComment(String id) implements CanvasEdit {}

    // Drops: what a drag carried, into where it landed.
    record FillSlotFromBlock(String slotId, BlockType block) implements CanvasEdit {}
    record FillEmptySlotFromBlock(String ownerId, BlockType block) implements CanvasEdit {}
    record MoveBetweenSlots(String slotId, String sourceId) implements CanvasEdit {}
    record FillEmptySlotFromExpression(String ownerId, String sourceId) implements CanvasEdit {}
    record MoveLineIntoSlot(String slotId, String lineId) implements CanvasEdit {}
    record FillEmptySlotFromLine(String ownerId, String lineId) implements CanvasEdit {}
    record MoveExpressionToLine(String sourceId, String bodyId, int index) implements CanvasEdit {}
    record MoveMember(String memberId, String typeId, int index) implements CanvasEdit {}

    // Members of a class.
    record AddMethod(String typeId, String name, String returnType, int index) implements CanvasEdit {}
    record AddFunction(String typeId, FunctionDraft draft, int index) implements CanvasEdit {}
    record DeleteMethod(String id) implements CanvasEdit {}
    record RenameParameter(String methodId, int index, String name) implements CanvasEdit {}
    /** A signature changed from {@code before} to {@code after}, and with it every call the core finds in the project. */
    record SetSignature(String methodId, FunctionDraft before, FunctionDraft after) implements CanvasEdit {}
    record RemoveAnnotation(String ownerId, String annotationId) implements CanvasEdit {}
    record AddEnum(String typeId, String name, int index) implements CanvasEdit {}
    record AddEnumDraft(String typeId, EnumDraft draft, int index) implements CanvasEdit {}
    record DeleteEnum(String id) implements CanvasEdit {}
    record AddEnumConstant(String enumId, String name) implements CanvasEdit {}
    record DeleteEnumConstant(String enumId, int index) implements CanvasEdit {}

    // Whole files, as the user approved them.
    /**
     * A refactor the user approved: every file it rewrites, whole, by its absolute path.
     *
     * @param targetId the node the gesture was on, for the lock and the refusal journal
     */
    record ApplyRefactor(String targetId, String summary, Map<String, String> rewrites, Map<String, String> moves)
            implements CanvasEdit {
        public static ApplyRefactor of(String targetId, Refactor.Planned plan) {
            Map<String, String> rewrites = new LinkedHashMap<>();
            plan.rewrites().forEach((file, text) -> rewrites.put(file.toString(), text));
            Map<String, String> moves = new LinkedHashMap<>();
            plan.moves().forEach((from, to) -> moves.put(from.toString(), to.toString()));
            return new ApplyRefactor(targetId, plan.summary(), rewrites, moves);
        }

        Refactor.Planned plan() {
            Map<Path, String> texts = new LinkedHashMap<>();
            rewrites.forEach((file, text) -> texts.put(Path.of(file), text));
            Map<Path, Path> moved = new LinkedHashMap<>();
            moves.forEach((from, to) -> moved.put(Path.of(from), Path.of(to)));
            return new Refactor.Planned(summary, texts, moved);
        }
    }
    /** A method put back as a version has it. */
    record RestoreMethod(String source, String signature) implements CanvasEdit {}
    /** The open file put back as a version has it. */
    record RestoreFile(String source) implements CanvasEdit {}
}
