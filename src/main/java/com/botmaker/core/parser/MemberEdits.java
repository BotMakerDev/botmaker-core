// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.parser;

import com.botmaker.core.events.FileEdit;
import com.botmaker.core.parser.handlers.MethodHandler;
import com.botmaker.core.parser.handlers.RestoreHandler;
import com.botmaker.core.parser.refactor.CallMigrator;
import com.botmaker.core.parser.refactor.ReviewMarker;
import com.botmaker.core.project.LockResolver.EditKind;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectFile;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.project.vcs.BlockDiff;
import com.botmaker.java.edit.EditContext;
import com.botmaker.java.edit.handlers.EnumManipulationHandler;
import com.botmaker.java.edit.palette.EnumDraft;
import com.botmaker.java.edit.palette.FunctionDraft;
import com.botmaker.java.edit.refactor.ReviewMarks;
import com.botmaker.java.edit.refactor.SignatureMigration;
import com.botmaker.java.grammar.BotAnnotation;
import com.botmaker.java.index.nav.Refactor;
import com.botmaker.java.types.ProjectAnalyzer;
import com.botmaker.java.types.ResolvedType;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A class's members: its methods and enums, what is written on them, and a change that reaches other files. */
final class MemberEdits {

    private final EditGate gate;
    private final ProjectConfig config;
    private final ProjectState state;
    private final ProjectAnalyzer analyzer;

    MemberEdits(EditGate gate, ProjectConfig config, ProjectState state, ProjectAnalyzer analyzer) {
        this.gate = gate;
        this.config = config;
        this.state = state;
        this.analyzer = analyzer;
    }

    // =================================================================================
    // METHODS
    // =================================================================================

    void addConstructorToClass(TypeDeclaration typeDecl) {
        gate.edit(typeDecl, EditKind.SIGNATURE, true, (cu, code) -> MethodHandler.addConstructorToClass(cu, code, typeDecl));
    }

    void addMethodToClass(TypeDeclaration typeDecl, String methodName, String returnType, int index) {
        gate.insert(typeDecl, EditKind.SIGNATURE, true, (cu, code) -> MethodHandler.addMethodToClass(cu, code, typeDecl, methodName, ResolvedType.named(returnType), index));
    }

    /** Adds a function the user described in the Add Function dialog. See {@link FunctionDraft}. */
    void addFunctionToClass(TypeDeclaration typeDecl, FunctionDraft draft, int index) {
        gate.insert(typeDecl, EditKind.SIGNATURE, true,
                (cu, code) -> MethodHandler.addFunctionToClass(gate.ctx(cu), code, typeDecl, draft, index));
    }

    /**
     * Applies a whole edited signature — name, return type, parameters — as one edit. See
     * {@link MethodHandler#applyFunctionSignature}: the point is that the five separate header controls it
     * replaces each re-wrote the file on their own, so a half-applied signature was reachable from the UI.
     */
    void applyFunctionSignature(MethodDeclaration method, FunctionDraft draft) {
        gate.edit(method, EditKind.SIGNATURE, false,
                (cu, code) -> MethodHandler.applyFunctionSignature(gate.ctx(cu), code, method, draft));
    }

    /**
     * The same change, carried to every call in the project — the plan the user approved in
     * {@code SignatureMigrationDialog}.
     *
     * <p>Written in one order for one reason: <b>the other files are rewritten in memory first</b>, and only
     * once every one of them has produced source that parses does the declaration's own file go through the
     * guarded write. A rename that lands in the declaration and then fails in the third file leaves a project
     * that does not build, with no single undo that puts it back — so a failure anywhere is a failure
     * everywhere, before anything is published. The declaration's file goes last and takes its own local calls
     * with it in the same rewrite ({@link MethodHandler#applyFunctionSignature}).
     */
    void applyFunctionSignature(MethodDeclaration method, FunctionDraft draft,
                                SignatureMigration.Plan plan) {
        if (plan == null || plan.isEmpty()) {
            applyFunctionSignature(method, draft);
            return;
        }
        if (!gate.canModify(method, EditKind.SIGNATURE)) return;
        CompilationUnit cu = gate.getCompilationUnit();
        if (cu == null) return;

        // Before a single character moves: the snapshot is the only undo for the *other* files that survives
        // the session. What the plan guessed is marked @Refactor where the bot compiles it.
        if (touchesOtherFiles(plan, cu)) {
            ReviewMarker.snapshot(config, "Before changing \"" + method.getName().getIdentifier() + "\"");
        }
        boolean marker = ReviewMarker.available(state);

        List<CallMigrator.Rewritten> others =
                CallMigrator.rewriteOthers(plan, cu, analyzer, state, config, marker);
        if (others == null) {
            gate.status("That change couldn't be written to every file that calls it, so nothing was changed.");
            return;
        }

        String newCode = MethodHandler.applyFunctionSignature(gate.ctx(cu), gate.getCurrentCode(), method, draft, plan,
                marker);
        if (newCode == null) return;
        // Read before the commit below overwrites them: this is what ↶ has to put back in those files.
        List<FileEdit> alsoChanged = others.stream()
                .map(each -> new FileEdit(each.file(), each.oldSource(), each.newSource()))
                .toList();
        if (!gate.triggerUpdate(newCode, false, method, EditKind.SIGNATURE, alsoChanged)) return;
        try {
            commit(others);
        } catch (IOException e) {
            gate.status("The function changed, but one of the files calling it couldn't be saved: "
                    + e.getMessage());
        }
    }

    /**
     * Whether the plan rewrites anything outside the file being edited — the one condition that earns a
     * Project History snapshot. The editor's own ↶ already puts the active file back and dies with the
     * session; a file the user never opened has no other way home.
     */
    private static boolean touchesOtherFiles(SignatureMigration.Plan plan, CompilationUnit active) {
        return plan.calls().stream().anyMatch(change -> change.site().unit() != active);
    }

    /**
     * Removes the declaration, and only it.
     *
     * <p>Deliberately unguarded, and deliberately not the thing a delete button calls: the guard — "is anything
     * still calling this?" — needs a project scan and a window to refuse into, so it lives at the UI edge in
     * {@code ui/app/SignatureEdits.delete}. This is what that calls once it has an answer.
     */
    void deleteMethod(MethodDeclaration method) {
        gate.edit(method, EditKind.SIGNATURE, false, (cu, code) -> MethodHandler.deleteMethodFromClass(cu, code, method));
    }

    /**
     * Writes a {@link Refactor} plan — a rename, a fix a refusal offered — as <b>one step</b>: the open file
     * through the guarded write, every other file it rewrites riding along in the same history entry, so ↶
     * puts all of them back.
     *
     * <p>The plan was compiled as the whole bot before it got here ({@code BotIndex.with}), so this only
     * carries it out, and in the order {@link #applyFunctionSignature} uses for the same reason: the other
     * files are held in memory until the open one has been published. A plan that moves a file is the
     * explorer's to write, not the canvas's, and is refused here.
     *
     * @param target the node the gesture was on, for the lock and the refusal journal
     * @return whether it was written
     */
    boolean applyRefactor(Refactor.Planned plan, ASTNode target) {
        if (plan == null || !plan.moves().isEmpty()) return false;
        if (!gate.canModify(target, EditKind.SIGNATURE)) return false;
        ProjectFile active = state.getActiveFile();
        Path activePath = active == null || active.getPath() == null ? null
                : active.getPath().toAbsolutePath().normalize();

        List<CallMigrator.Rewritten> others = new ArrayList<>();
        for (Map.Entry<Path, String> rewrite : plan.rewrites().entrySet()) {
            if (rewrite.getKey().equals(activePath)) continue;
            String before = state.getFile(rewrite.getKey()).map(ProjectFile::getContent)
                    .orElseGet(() -> read(rewrite.getKey()));
            others.add(new CallMigrator.Rewritten(rewrite.getKey(), before, rewrite.getValue()));
        }
        if (!others.isEmpty()) ReviewMarker.snapshot(config, "Before: " + plan.summary());

        List<FileEdit> alsoChanged = others.stream()
                .map(each -> new FileEdit(each.file(), each.oldSource(), each.newSource()))
                .toList();
        String newCode = plan.rewrites().getOrDefault(activePath, gate.getCurrentCode());
        if (!gate.triggerUpdate(newCode, false, target, EditKind.SIGNATURE, alsoChanged)) return false;
        try {
            commit(others);
        } catch (IOException e) {
            gate.status("The change was made here, but " + e.getMessage() + " couldn't be saved.");
            return false;
        }
        gate.status(plan.summary() + ".");
        return true;
    }

    /**
     * Writes the rewritten files to disk and updates the editor's copy of each.
     *
     * <p>The cached AST is dropped rather than re-parsed: {@code ProjectAnalyzer} re-parses a file whose AST is
     * missing and skips one that has any, so leaving yesterday's tree in place would keep every suggestion in
     * Studio reading the pre-migration signature.
     */
    private void commit(List<CallMigrator.Rewritten> files) throws IOException {
        for (CallMigrator.Rewritten each : files) {
            Files.writeString(each.file(), each.newSource());
            state.getFile(each.file()).ifPresent(file -> {
                file.setContent(each.newSource());
                file.setAst(null);
            });
        }
    }

    /** A file the editor does not hold, as it is on disk — empty when unreadable, which the rewrite replaces. */
    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Puts the function {@code signature} ({@link BlockDiff#signature}) back as
     * {@code versionSource} has it — over the live one, or where it used to sit when the file no longer has it
     * ({@code docs/refactor/39-versions.md} §6). One ↶ like any edit, and an {@link EditGate#edit}, not an
     * {@link EditGate#insert}: a user's own code coming back lands even when it no longer compiles, with its errors
     * in the Errors tab, as the same code typed by hand would.
     */
    void replaceMethod(String versionSource, String signature) {
        CompilationUnit cu = gate.getCompilationUnit();
        if (cu == null) return;
        MethodDeclaration live = BlockDiff.methods(cu).get(signature);
        ASTNode target = live != null ? live : cu.types().isEmpty() ? cu : (ASTNode) cu.types().getFirst();
        gate.edit(target, EditKind.SIGNATURE, false,
                (unit, code) -> RestoreHandler.replaceMethod(unit, code, versionSource, signature));
    }

    /** The open file replaced whole by {@code source} — <i>Restore this file</i>, one ↶. */
    void replaceFile(String source) {
        CompilationUnit cu = gate.getCompilationUnit();
        ASTNode target = cu == null || cu.types().isEmpty() ? cu : (ASTNode) cu.types().getFirst();
        if (target == null || !gate.canModify(target, EditKind.SIGNATURE)) return;
        gate.triggerUpdate(source, false, target, EditKind.SIGNATURE);
    }

    /**
     * Renames one input, in the declaration and in the body that reads it.
     *
     * <p>The one parameter edit that is <em>not</em> a migration: arguments are matched by position, so no call
     * anywhere mentions the name. The type and the count are a different matter — those go through
     * {@code SignatureEdits}.
     */
    void renameMethodParameter(MethodDeclaration method, int index, String newName) {
        gate.edit(method, EditKind.SIGNATURE, false, (cu, code) -> MethodHandler.renameMethodParameter(cu, code, method, index, newName));
    }

    // setMethodReturnType, addParameterToMethod and deleteParameterFromMethod used to live here: one-line
    // writes of the declaration alone, each reachable from a header control. They are gone rather than kept
    // "just in case" — every one of them was a way to change a signature without the project noticing, which
    // is the whole defect this round is closing. The header controls now build a FunctionDraft and go through
    // applyFunctionSignature above, so there is one write path and it is the one that scans.

    void moveBodyDeclaration(BodyDeclaration decl, TypeDeclaration targetType, int index) {
        gate.edit(targetType, EditKind.SIGNATURE, true, (cu, code) -> MethodHandler.moveBodyDeclaration(cu, code, decl, targetType, index));
    }

    // =================================================================================
    // ENUMS
    // =================================================================================

    // renameEnum, renameEnumConstant and renameMethod renamed the declaration and nothing else. A rename is
    // Refactor's now — every use in the bot, by binding, compiled first — written through applyRefactor.

    void addEnumConstant(EnumDeclaration enumNode, String constantName) {
        gate.edit(enumNode, EditKind.SIGNATURE, false, (cu, code) -> EnumManipulationHandler.addEnumConstant(cu, code, enumNode, constantName));
    }

    void deleteEnumConstant(EnumDeclaration enumNode, int index) {
        gate.edit(enumNode, EditKind.SIGNATURE, false, (cu, code) -> EnumManipulationHandler.deleteEnumConstant(cu, code, enumNode, index));
    }

    void addEnumToClass(TypeDeclaration typeDecl, String enumName, int index) {
        gate.insert(typeDecl, EditKind.SIGNATURE, true, (cu, code) -> EnumManipulationHandler.addEnumToClass(cu, code, typeDecl, enumName, index));
    }

    /** Adds the enum the Define Enum dialog described as a member of {@code typeDecl} — the class's Add enum. */
    void addEnumToClass(TypeDeclaration typeDecl, EnumDraft draft, int index) {
        if (draft == null) return;
        gate.insert(typeDecl, EditKind.SIGNATURE, false,
                (cu, code) -> EnumManipulationHandler.addEnumToClass(cu, code, typeDecl, draft.name(), draft.constants(), index));
    }

    void deleteEnumFromClass(EnumDeclaration enumDecl) {
        gate.edit(enumDecl, EditKind.SIGNATURE, false, (cu, code) -> EnumManipulationHandler.deleteEnumFromClass(cu, code, enumDecl));
    }

    // =================================================================================
    // IMPORTS / ANNOTATIONS
    // =================================================================================

    // getImports/addImport/removeImport stood here until 2026-09-29, for Project ▸ Manage Imports. Every edit
    // that writes a type already imports it (EditContext.addImport), and an unused import compiles, so the
    // window listed what nobody needed to touch; it is deleted with them.

    // repairSdkImports() stood here until 2026-09-02. It repointed a bot's `com.botmaker.sdk.api.*` and
    // `com.botmaker.shared.ocr.*` imports at wherever those classes had moved to, which is the editor
    // carrying one library's package-move history. A package move is now the bot author's @Migrate TYPE
    // rule (2026-10-16); with none it is a compile error in a line the user can read, the honest outcome.

    /**
     * Removes {@code annotation} from the declaration it sits on — the ✕ on an annotation pill. A
     * {@code @Refactor} takes its import with it once it was the file's last ({@link ReviewMarks#remove}).
     */
    void removeAnnotation(BodyDeclaration owner, Annotation annotation) {
        if (owner == null || annotation == null || annotation.getParent() != owner) return;
        gate.edit(owner, EditKind.SIGNATURE, false, (cu, code) -> {
            EditContext ctx = gate.ctx(cu);
            if (owner instanceof MethodDeclaration method && BotAnnotation.REFACTOR.marks(annotation)) {
                ReviewMarks.remove(ctx, method);
            } else {
                ctx.rewriter().getListRewrite(owner, owner.getModifiersProperty()).remove(annotation, null);
            }
            return ctx.applyTo(code);
        });
    }
}
