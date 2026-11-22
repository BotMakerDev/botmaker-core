// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.parser;

import com.botmaker.core.parser.handlers.ListHandler;
import com.botmaker.core.parser.handlers.MethodHandler;
import com.botmaker.core.project.LockResolver.EditKind;
import com.botmaker.java.edit.ExpressionChoice;
import com.botmaker.java.edit.ImportManager;
import com.botmaker.java.edit.helpers.AstRewriteHelper;
import com.botmaker.java.edit.palette.ExpressionType;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.types.ResolvedType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ArrayCreation;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.util.List;

/** A call and its arguments, and the elements of a list written as one. */
final class CallEdits {

    private final EditGate gate;

    CallEdits(EditGate gate) {
        this.gate = gate;
    }

    // setImageTemplate, setImageTemplateGroup, isSoleFindArgument and windowFindOrElseThrow went on
    // 2026-09-01. Every one of them was the host spelling one plugin's API for it — `new ImageTemplate(path)`,
    // `ImageTemplateGroup.of(…)`, `Window.find(title).orElseThrow()` — and the surface that replaces them was
    // already here: setTrailingArguments takes the expressions a plugin built and puts them
    // where it says. The SDK's own TemplateEditors has claimed an ImageTemplate slot since 2026-08-27, so the
    // one caller these had left (ui/render/components/ImageTemplatePicker) was a picker PickerRegistry had
    // already stopped consulting.
    //
    // What went with them and is worth naming: the group writer also called LambdaCallHandler.seedIfReady,
    // so filling a group slot no longer seeds anything in the loop body. That question is closed rather than
    // still open — the thing it used to seed was the combination switch, which is deleted (2026-09-01), and a
    // body that starts empty is the honest state now that branching is a call the palette offers.

    /**
     * Replaces the trailing arguments of {@code call} from {@code fromIndex} with exactly {@code values}'
     * trees, adding the imports each names.
     *
     * <p>This is the host's slot-run writer (Studio's {@code HostSlotRun}), and it is deliberately <b>ignorant
     * of what the arguments mean</b>. Its predecessor took a list of template <em>paths</em> and built
     * {@code new ImageTemplate(path)} nodes itself, which is the host spelling the SDK's API for it; the
     * grammar writes each value and this puts the trees where it says. That is the same trade
     * {@link ExpressionEdits#replaceWithValue} makes for a single slot, and it is why a second plugin with a
     * multi-valued call of its own needs nothing added here.
     *
     * <p>It takes the whole desired list rather than an index, so add, remove and change are one uniform
     * rewrite instead of three argument-list surgeries — and the empty list is a legal state: the call keeps
     * its fixed arguments and the row falls back to its prompt. Arguments before {@code fromIndex} are
     * untouched.
     */
    void setTrailingArguments(MethodInvocation call, int fromIndex, List<JavaValue> values) {
        gate.edit(call, EditKind.BODY, true, (cu, code) -> {
            AST ast = cu.getAST();
            ASTRewrite rewriter = ASTRewrite.create(ast);
            ListRewrite args = rewriter.getListRewrite(call, MethodInvocation.ARGUMENTS_PROPERTY);

            List<?> existing = call.arguments();
            for (int i = fromIndex; i < existing.size(); i++) {
                args.remove((ASTNode) existing.get(i), null);
            }
            // Only the imports the values name. The old writer also added the project's Templates class here,
            // which the values a plugin hands over do not name — an import for a vocabulary this method no
            // longer knows about.
            for (JavaValue value : values) {
                if (value == null) continue;
                args.insertLast(value.copyInto(ast), null);
                for (String type : value.imports()) ImportManager.addImport(cu, rewriter, type);
            }
            return AstRewriteHelper.applyRewrite(rewriter, code);
        });
    }

    // switchLambdaVariant and replaceLambdaCallWithFacadeCall went on 2026-09-01, with the block that was
    // their only caller. Both existed to drive a dropdown that could only be written by a file holding one
    // library's vocabulary: the first switched a vision loop between its single / …Any / …All variants and
    // converted the leading argument ImageTemplate↔ImageTemplateGroup with it; the second repointed the call
    // at another SDK facade and threw the body away, since only ImageFinder's helpers took one. BodyCallBlock
    // draws a body-taking call on any receiver and offers neither: changing which method a call is on is the
    // member menus' job, served by whichever plugin owns the type.

    // setRect, setPoint, setSize and replaceWithIntCtor went on 2026-09-01, and they had already had no
    // caller since 2026-08-27: the SDK's GeometryEditors claims a Rect, Point or Size slot and writes the
    // constructor itself through SlotContext.replaceWith. They are the same mistake as the template writers
    // above, caught later only because nothing was left calling them to notice.
    //
    // setCallArguments and its ArgValue went with them. Its one caller was the "Pick all on screen" session
    // (ui/render/components/PickAllSession), deleted the same day. The atomicity it existed for is real and
    // is not lost — setTrailingArguments above replaces a run of arguments in one rewrite for exactly that
    // reason — but a batch keyed by argument index, whose values were three of one plugin's types, is not
    // something the host can offer generically. When a plugin wants it back, it wants SlotRun, which it has.

    void updateMethodInvocation(MethodInvocation mi, String newScope, String newMethodName, List<ResolvedType> newParamTypes) {
        gate.edit(mi, EditKind.BODY, true, (cu, code) -> MethodHandler.updateMethodInvocation(gate.ctx(cu), code, mi, newScope, newMethodName, newParamTypes));
    }

    void addArgumentToMethodInvocation(MethodInvocation mi, ExpressionType type) {
        gate.insert(mi, EditKind.BODY, true, (cu, code) -> MethodHandler.addArgumentToMethodInvocation(gate.ctx(cu), code, mi, type));
    }

    void addArgumentToMethodInvocation(MethodInvocation mi, Expression expr) {
        gate.edit(mi, EditKind.BODY, false, (cu, code) -> MethodHandler.addArgumentToMethodInvocation(cu, code, mi, expr));
    }

    void addStringArgumentToMethodInvocation(MethodInvocation mi, String text) {
        gate.edit(mi, EditKind.BODY, false, (cu, code) -> {
            StringLiteral newArg = cu.getAST().newStringLiteral();
            newArg.setLiteralValue(text);
            return MethodHandler.addArgumentToMethodInvocation(cu, code, mi, newArg);
        });
    }

    /**
     * Appends one more argument of {@code elementType} to {@code mi} — the writer behind the {@code ＋} a
     * varargs slot renders. The new argument is the type's default initializer, so it lands as an editable
     * slot rather than a compile error.
     */
    void addVarargsArgument(MethodInvocation mi, ResolvedType elementType) {
        gate.insert(mi, EditKind.BODY, true, (cu, code) ->
                MethodHandler.addVarargsArgument(gate.ctx(cu), code, mi, elementType));
    }

    void deleteArgumentFromMethodInvocation(MethodInvocation mi, int index) {
        gate.edit(mi, EditKind.BODY, false, (cu, code) -> MethodHandler.deleteArgument(cu, code, mi, index));
    }

    void replaceWithMethodCall(Expression toReplace, ExpressionChoice.Method choice) {
        // If we're inside an ArrayCreation (new int[]{...}) replace the whole creation, not just the initializer.
        ASTNode targetNode = toReplace;
        if (toReplace instanceof ArrayInitializer && toReplace.getParent() instanceof ArrayCreation) {
            targetNode = toReplace.getParent();
        }
        Expression target = (Expression) targetNode;
        gate.insert(target, EditKind.BODY, false, (cu, code) -> MethodHandler.replaceWithMethodCall(gate.ctx(cu), code, target, choice));
    }

    void addMethodCallStatement(String bodyId, ExpressionChoice.Method choice, int index) {
        ASTNode targetBody = gate.nodeAt(bodyId);
        if (targetBody == null) return;
        gate.insert(targetBody, EditKind.BODY, false, (cu, code) -> MethodHandler.addMethodCallStatement(gate.ctx(cu), code, targetBody, choice, index));
    }

    // =================================================================================
    // LISTS
    // =================================================================================

    void addElementToList(ASTNode listNode, ExpressionType type, int insertIndex) {
        gate.insert(listNode, EditKind.BODY, true, (cu, code) -> ListHandler.addElementToList(gate.ctx(cu), code, listNode, type, insertIndex));
    }

    /**
     * Inserts an element built from an expression-menu {@code selection} (an {@link ExpressionType} or an
     * {@link ExpressionChoice}) at {@code insertIndex}. Powers the type-aware list "+" menu.
     * {@code elementType} is the list's inferred element type, used to build sensible default arguments.
     */
    void insertIntoList(ASTNode listNode, int insertIndex, Object selection, ResolvedType elementType) {
        gate.insert(listNode, EditKind.BODY, true, (cu, code) -> ListHandler.insertChoiceIntoList(gate.ctx(cu), code, listNode, insertIndex, selection, elementType));
    }

    /** Moves the list element at {@code fromIndex} to {@code toIndex} (used by the per-row up/down buttons). */
    void moveListElement(ASTNode listNode, int fromIndex, int toIndex) {
        gate.edit(listNode, EditKind.BODY, false, (cu, code) -> ListHandler.moveElement(cu, code, listNode, fromIndex, toIndex));
    }

    /**
     * Adds an element of {@code elementType} to the list, seeded by whichever plugin owns that type.
     *
     * <p>{@code addImageTemplateToList} stood here until 2026-09-01 and built {@code new ImageTemplate("")}
     * itself. What a fresh value of a plugin's type looks like is that plugin's sentence to write, and it
     * writes it as a {@code SourceSeed}; the host's job is only to put the element in the list.
     */
    void addSeededElementToList(ASTNode listNode, int insertIndex, ResolvedType elementType) {
        gate.insert(listNode, EditKind.BODY, true,
                (cu, code) -> ListHandler.addSeededElement(gate.ctx(cu), code, listNode, insertIndex, elementType));
    }

    void deleteElementFromList(ASTNode listNode, int elementIndex) {
        gate.edit(listNode, EditKind.BODY, false, (cu, code) -> ListHandler.deleteElementFromList(cu, code, listNode, elementIndex));
    }
}
