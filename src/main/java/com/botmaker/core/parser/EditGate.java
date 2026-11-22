// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.parser;

import com.botmaker.core.events.FileEdit;
import com.botmaker.core.parser.guard.RefusalJournal;
import com.botmaker.core.parser.guard.RefusedEdit;
import com.botmaker.core.project.LockResolver;
import com.botmaker.core.project.LockResolver.EditKind;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectFile;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.project.StudioProjectSettings;
import com.botmaker.java.edit.BlockId;
import com.botmaker.java.edit.EditContext;
import com.botmaker.java.edit.guard.CompileGuard;
import com.botmaker.java.edit.guard.UnusedImports;
import com.botmaker.java.edit.helpers.AstRewriteHelper;
import com.botmaker.java.grammar.SourceFormatter;
import com.botmaker.java.grammar.SourceParser;
import com.botmaker.java.parse.BotParser;
import com.botmaker.java.parse.JavaLevel;
import com.botmaker.java.types.ProjectAnalyzer;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.util.List;
import java.util.function.BiFunction;

/**
 * What every edit of the open file passes: whether the node may change, whether the rewrite leaves source that
 * parses (and compiles, where Studio wrote it), and the publish. The {@code *Edits} classes of this package each hold
 * one, and {@link CodeEditor} is the only class that makes one.
 */
final class EditGate {

    private final ProjectConfig config;
    private final ProjectState state;
    private final EditorOutput output;
    private final ProjectAnalyzer analyzer;
    private final RefusalJournal journal;

    EditGate(ProjectConfig config, ProjectState state, EditorOutput output, ProjectAnalyzer analyzer,
             RefusalJournal journal) {
        this.config = config;
        this.state = state;
        this.output = output;
        this.analyzer = analyzer;
        this.journal = journal;
    }

    String getCurrentCode() { return state.getCurrentCode(); }
    CompilationUnit getCompilationUnit() { return state.getCompilationUnit().orElse(null); }

    /** Tells the user {@code message}, on the status line. */
    void status(String message) {
        output.status(message);
    }

    /**
     * Whether {@code target} may be changed, telling the user why not when it may not. A null {@code config}
     * (tests, no project open) permits everything; a null {@code target} never does.
     */
    boolean canModify(ASTNode target, EditKind kind) {
        LockResolver.Verdict verdict = LockResolver.forActiveFile(config, state).check(target, kind);
        if (!verdict.allowed()) {
            output.status(verdict.reason());
        }
        return verdict.allowed();
    }

    /** The node a {@link BlockId} names in the open file, or {@code null} when there is none any more. */
    ASTNode nodeAt(String id) {
        CompilationUnit cu = getCompilationUnit();
        return cu == null || id == null ? null : BlockId.find(cu, id).orElse(null);
    }

    boolean triggerUpdate(String newCode, ASTNode target) {
        return triggerUpdate(newCode, false, target, EditKind.BODY);
    }

    /**
     * Publishes {@code newCode} as the file's new content — unless it wouldn't parse. Returns whether it was
     * published, so a caller with a follow-up event of its own can drop that too.
     *
     * <p>{@code target} and {@code kind} are carried only for the refusal journal — they say <em>which block</em>
     * the rewrite that emitted broken source was editing, which is the one thing a refusal can't be diagnosed
     * without and the one thing the guard couldn't see when it only took two strings. Both are nullable: a
     * whole-file rewrite ({@code normalizeSwitches}) has no target, and the journal treats every field as
     * best-effort.
     */
    boolean triggerUpdate(String newCode, boolean markNewIdentifiersAsUnedited,
                          ASTNode target, EditKind kind) {
        return triggerUpdate(newCode, markNewIdentifiersAsUnedited, target, kind, List.of());
    }

    /**
     * The same, for an edit that also rewrote other files — the migration path, and nothing else.
     *
     * <p>{@code alsoChanged} rides along so history records the whole change as one step. Without it, ↶ after a
     * rename that crossed four files puts one of them back and leaves three calling a name that is gone.
     */
    boolean triggerUpdate(String newCode, boolean markNewIdentifiersAsUnedited,
                          ASTNode target, EditKind kind,
                          List<FileEdit> alsoChanged) {
        String previousCode = getCurrentCode();
        newCode = withoutUnusedImports(formatted(previousCode, newCode), previousCode);
        if (wouldBreak(newCode, previousCode, target, kind)) return false;
        output.written(new EditorOutput.Write(newCode, previousCode, markNewIdentifiersAsUnedited,
                stepLabel(target, alsoChanged.size()), alsoChanged));
        return true;
    }

    /**
     * What this edit is called when the Undo menu has to name it.
     *
     * <p>Deliberately coarse: the node being edited and how many files went with it. A per-control sentence
     * would mean every one of the fifty write methods carrying a string it never otherwise needs, and "Undo the
     * change to loadTargets, in 4 files" is already the sentence a user is checking against.
     */
    private static String stepLabel(ASTNode target, int otherFiles) {
        String what = target instanceof MethodDeclaration method
                ? "the change to " + method.getName().getIdentifier()
                : "the last change";
        return otherFiles == 0 ? what : what + ", in " + (otherFiles + 1) + " files";
    }

    /**
     * The same edit without an import it left unused ({@link UnusedImports}): one parse per edit, with a
     * resolved classpath only — the rule {@link #wouldNotCompile} follows, since without bindings every
     * import reads as unused.
     */
    private String withoutUnusedImports(String newCode, String previousCode) {
        if (newCode == null || newCode.equals(previousCode) || state.getResolvedClasspath().isEmpty()) return newCode;
        if (LockResolver.forActiveFile(config, state).suppressesInteraction()) return newCode;
        ProjectFile active = state.getActiveFile();
        return new UnusedImports(BotParser.of(state), active == null ? null : active.getPath()).removeFrom(newCode);
    }

    /**
     * {@code newCode} with the lines this edit changed laid out — the single place Studio formats, sitting on
     * the single place it publishes, so no write path can skip it and none of them has to remember to ask.
     *
     * <p>Before the guard rather than after it, so the text the guard judges is the text the user gets; and
     * after every rewrite rather than inside them, because {@code ASTRewrite} only formats what it inserts.
     * <b>Only the changed lines</b> since 2026-09-19 ({@link SourceFormatter#formatChanged}): the whole file
     * used to be reflowed, which rewrote members the edit never touched and made a one-block change a diff
     * across the file.
     *
     * <p>Skipped for a file the user can't edit anyway, on the same reasoning as
     * {@code CodeEditorService}'s call to {@code normalizeSwitches}: reformatting generated scaffolding
     * produces a diff nobody asked for in a file nobody can change.
     */
    private String formatted(String previousCode, String newCode) {
        if (newCode == null) return null;
        if (LockResolver.forActiveFile(config, state).suppressesInteraction()) return newCode;
        return SourceFormatter.formatChanged(state.level(), previousCode, newCode);
    }

    /**
     * Whether publishing {@code newCode} would leave the user with source that doesn't parse — the one gate
     * between a rewrite and the canvas.
     *
     * <p><b>Why it exists.</b> A rewrite that <em>throws</em> is already handled: {@code AstRewriteHelper
     * .applyRewrite} throws {@code RewriteFailed}, which {@code applied} (or {@code CoreEditor}) tells and publishes
     * nothing for. A rewrite that succeeds and produces broken Java had nothing checking it: the code was published, {@code refreshUI} re-parsed it, JDT recovered a mangled
     * tree, and the method rendered <em>empty</em>. Adding one block could therefore erase the visible contents
     * of a method, with Ctrl-Z as the only way back. Refusing here costs the user the edit; publishing costs
     * them the method.
     *
     * <p><b>Only a newly introduced break is refused</b>, which is the half that makes this liveable. If the
     * file already has a syntax error — a half-typed argument, a paste in progress — every subsequent edit
     * would fail the check, and the guard would lock the user out of the very edits that fix it. So a file that
     * was already broken is left entirely alone.
     *
     * <p>Syntax errors only, via {@link SourceParser}: with bindings unresolved an unimported class or an
     * unknown type isn't reported, and mustn't be — a block that names a type the project doesn't have yet is
     * a normal intermediate state. A broken brace is not.
     *
     * <p>Costs one full-file parse on the edit path, and only on the path that already parses: the new code is
     * checked first, so the overwhelmingly common clean edit pays a single parse and never touches the old
     * code. {@code refreshUI} parses the same file on every edit anyway.
     *
     * <p><b>Every refusal is recorded</b>, not just printed — see {@link RefusalJournal}. The refusal is the
     * symptom; the rewrite that emitted broken source is the bug, and it is fixed later, usually from someone
     * else's machine. So the problem, the rewrite, the block, the project and both full sources go to disk.
     */
    private boolean wouldBreak(String newCode, String previousCode, ASTNode target, EditKind kind) {
        if (newCode == null || newCode.equals(previousCode)) return false;
        IProblem problem = SourceParser.firstSyntaxError(SourceParser.parse(state.level(), newCode));
        if (problem == null) return false;
        if (SourceParser.hasSyntaxErrors(SourceParser.parse(state.level(), previousCode))) return false;
        RefusedEdit refused = RefusedEdit.of(problem, refusedBy(), kind, target, previousCode, config, state);
        System.err.println("Refused an edit that would have broken the code " + refused.summary());
        System.err.println("  the source it would have published: "
                + journal.record(refused, newCode, previousCode));
        output.status("That change would have broken the code, so nothing was changed.");
        return true;
    }

    /**
     * The public {@link CodeEditor} method that reached the refused edit, for the log line. This is the handle
     * on <em>which rewrite</em> emits broken source — the thing worth fixing, of which the refusal is only the
     * symptom.
     *
     * <p>{@link CodeEditor} holds its public methods and nothing else, so its first frame is the one that was
     * called: neither this gate nor the {@code *Edits} class between them can answer with its own name.
     */
    private static String refusedBy() {
        return StackWalker.getInstance().walk(frames -> frames
                .filter(f -> f.getClassName().equals(CodeEditor.class.getName()))
                .map(f -> f.getMethodName() + ":" + f.getLineNumber())
                .findFirst()
                .orElse("unknown"));
    }

    /**
     * The write-path context for a rewrite of {@code cu} — this editor's analyzer and project state, plus a
     * fresh {@link ASTRewrite}. Every {@link #edit} lambda that calls a handler builds one of these, which is
     * why the handlers take a single {@link EditContext} rather than re-listing the same four arguments.
     */
    EditContext ctx(CompilationUnit cu) {
        return EditContext.of(cu, analyzer, state).withFavorites(this::favoriteSignature);
    }

    /** The overload the user pinned for {@code methodKey}, a project setting, or {@code null}. */
    private String favoriteSignature(String methodKey) {
        StudioProjectSettings settings = state == null ? null : state.getSettings();
        return settings == null ? null : settings.favoriteSignature(methodKey);
    }

    /**
     * Runs a pure rewrite under the modify-guard and publishes the result. {@code op} is a
     * {@code (CompilationUnit, originalCode) -> newCode} transform — a handler call or a local static transform.
     *
     * <p>{@code target} is the node being changed and {@code kind} says which half of its method that touches;
     * together they are what the guard needs to resolve a lock. Passing the node the rewrite actually mutates
     * matters: {@code deleteMethod} targets the method itself ({@code SIGNATURE}, refused for a supervise hook),
     * while {@code addStatement} targets its body ({@code BODY}, allowed for the same hook).
     */
    void edit(ASTNode target, EditKind kind, boolean markUnedited,
              BiFunction<CompilationUnit, String, String> op) {
        if (!canModify(target, kind)) return;
        CompilationUnit cu = getCompilationUnit();
        if (cu == null) return;
        String newCode = applied(op, cu);
        if (newCode != null) triggerUpdate(newCode, markUnedited, target, kind);
    }

    /**
     * {@code op} over the current code, or {@code null} after saying why when it needs a newer Java than the bot's
     * (2026-11-04): the refusal names the version, where JDT would throw and javac would fail later. Also {@code null},
     * told, when its rewrite cannot be applied ({@code RewriteFailed}, 2026-11-05).
     */
    private String applied(BiFunction<CompilationUnit, String, String> op, CompilationUnit cu) {
        try {
            return op.apply(cu, getCurrentCode());
        } catch (JavaLevel.TooOld tooOld) {
            output.status(tooOld.getMessage());
            return null;
        } catch (AstRewriteHelper.RewriteFailed failed) {
            output.status(failed.getMessage());
            return null;
        }
    }

    /**
     * {@link #edit}, for an edit that writes Java the user did not type — a block from a menu, a value from a
     * picker — and so is refused when it would add a compile error. See {@link #wouldNotCompile}.
     */
    void insert(ASTNode target, EditKind kind, boolean markUnedited,
                BiFunction<CompilationUnit, String, String> op) {
        if (!canModify(target, kind)) return;
        CompilationUnit cu = getCompilationUnit();
        if (cu == null) return;
        String newCode = applied(op, cu);
        if (newCode == null || wouldNotCompile(newCode)) return;
        triggerUpdate(newCode, markUnedited, target, kind);
    }

    /**
     * Whether {@code newCode} compiles with an error the current code does not have — told to the user in a
     * status line naming it. {@link CompileGuard} is the rule; this is the canvas's use of it, beside the
     * syntax gate in {@link #wouldBreak} that every edit passes.
     *
     * <p>Only with a resolved classpath. Without one every class a plugin declares reads as unknown, so the
     * first call to it would refuse; the syntax gate is all a project with no classpath gets, as before.
     */
    boolean wouldNotCompile(String newCode) {
        String before = getCurrentCode();
        if (before == null || newCode.equals(before) || state.getResolvedClasspath().isEmpty()) return false;
        ProjectFile active = state.getActiveFile();
        List<CompileGuard.Problem> introduced = new CompileGuard(BotParser.of(state),
                active == null ? null : active.getPath()).introduced(before, newCode);
        if (introduced.isEmpty()) return false;
        output.status("That would not compile here, so nothing was changed: " + introduced.getFirst().message());
        return true;
    }

    /** {@code newCode}, or {@code null} after saying why when a handler refused the edit. */
    String refusedWith(String newCode, String reason) {
        if (newCode == null) output.status(reason);
        return newCode;
    }
}
