// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.parser;

import com.botmaker.core.parser.factories.StatementFactory;
import com.botmaker.core.parser.handlers.MethodHandler;
import com.botmaker.core.parser.refactor.ReviewMarker;
import com.botmaker.core.project.LockResolver.EditKind;
import com.botmaker.core.project.ProjectState;
import com.botmaker.java.edit.BodyChildren;
import com.botmaker.java.edit.BodyInserts;
import com.botmaker.java.edit.EditContext;
import com.botmaker.java.edit.ImportManager;
import com.botmaker.java.edit.UseFix;
import com.botmaker.java.edit.factories.InitializerFactory;
import com.botmaker.java.edit.handlers.BranchChainHandler;
import com.botmaker.java.edit.handlers.StatementSourceHandler;
import com.botmaker.java.edit.handlers.TryHandler;
import com.botmaker.java.edit.helpers.AstRewriteHelper;
import com.botmaker.java.edit.helpers.CommentText;
import com.botmaker.java.edit.palette.BlockType;
import com.botmaker.java.edit.palette.EnumDraft;
import com.botmaker.java.edit.refactor.ReviewMarks;
import com.botmaker.java.grammar.SourceParser;
import com.botmaker.java.parse.JavaLevel;
import com.botmaker.java.types.ProjectAnalyzer;
import com.botmaker.java.types.ResolvedType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;

import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The lines of a body: added, removed, moved and pasted; an {@code if}'s and a {@code try}'s parts; the comments. */
final class StatementEdits {

    private final EditGate gate;
    private final ProjectState state;
    private final EditorOutput output;

    StatementEdits(EditGate gate, ProjectState state, EditorOutput output) {
        this.gate = gate;
        this.state = state;
        this.output = output;
    }

    /**
     * Inserts a declare-a-variable statement as the first statement of {@code method}'s body — the Variables
     * screen's Add. It goes to the top rather than to the cursor because the screen has no cursor: it is a
     * list of what this activity declares, and a new entry belongs where the list starts.
     */
    void addLocalVariable(MethodDeclaration method, BlockType.VarDecl decl) {
        gate.edit(method, EditKind.BODY, true, (cu, code) -> insertLocalVariable(gate.ctx(cu), code, method, decl));
    }

    private static String insertLocalVariable(EditContext ctx, String originalCode, MethodDeclaration method,
                                              BlockType.VarDecl decl) {
        Block body = method.getBody();
        if (body == null) return null;
        Statement statement = StatementFactory.createStatement(ctx, decl, body);
        if (statement == null) return null;
        ctx.rewriter().getListRewrite(body, Block.STATEMENTS_PROPERTY).insertFirst(statement, null);
        return ctx.applyTo(originalCode);
    }

    void addStatement(String bodyId, BlockType type, int index) {
        ASTNode targetBody = gate.nodeAt(bodyId);
        if (targetBody == null) return;
        if (!gate.canModify(targetBody, EditKind.BODY)) return;
        CompilationUnit cu = gate.getCompilationUnit();
        if (cu == null) return;
        String newCode = addStatement(gate.ctx(cu), gate.getCurrentCode(), targetBody, type, index);
        if (newCode == null || gate.wouldNotCompile(newCode)) return;
        // The refusal has to take the announcement with it: a BlockAddedEvent for a block that was never
        // published scrolls the canvas to a block that isn't there.
        if (!gate.triggerUpdate(newCode, true, targetBody, EditKind.BODY)) return;
        output.blockAdded(type);
    }

    /**
     * Inserts the enum the Define Enum dialog described — its name and its values, as the user gave them
     * rather than the palette's {@code MyEnum { OPTION_A, OPTION_B }}. A name the file already uses is refused
     * by the compile check like any other insert, so the dialog's own check is a courtesy, not the guard.
     */
    void addEnumStatement(String bodyId, int index, EnumDraft draft) {
        ASTNode targetBody = gate.nodeAt(bodyId);
        if (draft == null || targetBody == null) return;
        gate.insert(targetBody, EditKind.BODY, false, (cu, code) -> {
            EditContext ctx = gate.ctx(cu);
            Statement statement = StatementFactory.createEnumDeclaration(cu.getAST(), draft);
            ListRewrite listRewrite = AstRewriteHelper.getListRewriteForBody(ctx.rewriter(), targetBody);
            insertIntoList(listRewrite, targetBody, statement, index, code);
            return AstRewriteHelper.applyRewrite(ctx.rewriter(), code);
        });
    }

    /**
     * Inserts {@code <type> <name> = <fresh value>;} — what Declare Variable writes once its type is chosen
     * from the type chooser (2026-09-26). Any type: the type is written as a tree with its imports, and the
     * value is the grammar's fresh one for it, else the language default.
     */
    void declareLocal(String bodyId, int index, String name, Type type) {
        ASTNode targetBody = gate.nodeAt(bodyId);
        if (name == null || type == null || targetBody == null) return;
        gate.insert(targetBody, EditKind.BODY, false, (cu, code) -> {
            EditContext ctx = gate.ctx(cu);
            AST ast = cu.getAST();
            VariableDeclarationFragment fragment = ast.newVariableDeclarationFragment();
            fragment.setName(ast.newSimpleName(name));
            fragment.setInitializer(MethodHandler.defaultValueFor(ctx, type));
            VariableDeclarationStatement statement = ast.newVariableDeclarationStatement(fragment);
            statement.setType(MethodHandler.typeNodeFor(ctx, type));
            ListRewrite listRewrite = AstRewriteHelper.getListRewriteForBody(ctx.rewriter(), targetBody);
            insertIntoList(listRewrite, targetBody, statement, index, code);
            return AstRewriteHelper.applyRewrite(ctx.rewriter(), code);
        });
    }

    void deleteStatement(Statement toDelete) {
        gate.edit(toDelete, EditKind.BODY, false, (cu, code) -> deleteStatement(cu, code, toDelete));
    }

    /**
     * Deletes a variable <em>and settles its uses</em>, in one write.
     *
     * <p>{@link #deleteStatement} on a declaration removes the line and leaves every {@code attempts} in the
     * method pointing at a name that no longer exists — a file that does not compile, from one press of a ✕.
     * What the uses become is {@link UseFix}, one of the fixes the refusal offers
     * ({@code DeleteVariableDialog}); a variable with no uses never reaches here, since {@code deleteStatement}
     * is already the right answer for it. A default is a guess and marks the function {@code @Refactor}.
     *
     * <p>One write, not two: the uses and the declaration go in a single {@code ASTRewrite}, so the undo is one
     * step and the intermediate state — uses rewritten, declaration still there, or worse the reverse — never
     * exists as a version of the file.
     */
    void deleteVariable(VariableDeclarationStatement decl, UseFix fix) {
        if (decl == null || fix == null) return;
        // Renaming the uses to another variable is a complete repair — the body goes on reading a real value,
        // and nothing is left to check. Defaulting them is not, so only that one is recorded.
        boolean marker = !(fix instanceof UseFix.Rename) && ReviewMarker.available(state);
        gate.edit(decl, EditKind.BODY, false, (cu, code) -> deleteVariable(gate.ctx(cu), code, decl, fix, marker));
    }

    void pasteCode(String bodyId, int index, String codeToPaste) {
        ASTNode targetBody = gate.nodeAt(bodyId);
        if (targetBody == null) return;
        gate.edit(targetBody, EditKind.BODY, false,
                (cu, code) -> pasteCodeString(gate.ctx(cu), code, targetBody, index, codeToPaste));
    }

    /**
     * Inserts a statement the host built as a tree — a recorded call, whose classes came from the plugin that
     * declared it — with exactly {@code imports}. One edit, so one undo step.
     */
    void insertStatement(String bodyId, int index, Statement statement, List<String> imports) {
        ASTNode targetBody = gate.nodeAt(bodyId);
        if (statement == null || targetBody == null) return;
        gate.insert(targetBody, EditKind.BODY, false, (cu, code) -> {
            EditContext ctx = gate.ctx(cu);
            for (String qualified : imports) ctx.addImport(qualified);
            ASTRewrite rewriter = ctx.rewriter();
            Statement copied = (Statement) JavaLevel.copy(cu.getAST(), statement);
            ListRewrite listRewrite = AstRewriteHelper.getListRewriteForBody(rewriter, targetBody);
            insertIntoList(listRewrite, targetBody, copied, index, code);
            return AstRewriteHelper.applyRewrite(rewriter, code);
        });
    }

    /**
     * A move is two edits, so both ends must permit it: dragging a statement <em>out of</em> a locked body
     * removes code from it just as surely as dropping one in adds code. Checking only the destination would
     * let a drag empty out a generated method.
     */
    void moveStatement(String statementId, String sourceBodyId, String targetBodyId, int targetIndex) {
        ASTNode toMove = gate.nodeAt(statementId);
        ASTNode sourceBody = gate.nodeAt(sourceBodyId);
        ASTNode targetBody = gate.nodeAt(targetBodyId);
        if (toMove == null || sourceBody == null || targetBody == null) return;
        if (!gate.canModify(sourceBody, EditKind.BODY)) return;
        gate.edit(targetBody, EditKind.BODY, false,
                (cu, code) -> moveStatement(cu, code, toMove, sourceBody, targetBody, targetIndex));
    }

    /**
     * Replaces {@code statement} with Java the user typed — the one edit a statement drawn as its own source
     * has. Text that is not Java statements is refused with a status line rather than written: a file that no
     * longer parses draws as an empty canvas.
     */
    void replaceStatementSource(Statement statement, String newSource) {
        if (statement == null || !gate.canModify(statement, EditKind.BODY)) return;
        if (newSource == null || newSource.strip().equals(statement.toString().strip())) return;
        String newCode;
        try {
            newCode = StatementSourceHandler.replace(gate.getCurrentCode(), statement, newSource);
        } catch (JavaLevel.TooOld tooOld) {
            gate.status(tooOld.getMessage());
            return;
        }
        if (newCode == null) {
            gate.status("That is not a Java statement, so the code was left as it was.");
            return;
        }
        if (!newCode.equals(gate.getCurrentCode())) gate.triggerUpdate(newCode, false, statement, EditKind.BODY);
    }

    // =================================================================================
    // IF / BRANCHES
    // =================================================================================

    void deleteElseFromIfStatement(IfStatement ifStmt) {
        gate.edit(ifStmt, EditKind.BODY, false, (cu, code) -> deleteElseFromIfStatement(cu, code, ifStmt));
    }

    void convertElseToElseIf(IfStatement ifStmt) {
        gate.edit(ifStmt, EditKind.BODY, false, (cu, code) -> convertElseToElseIf(cu, code, ifStmt));
    }

    void addElseToIfStatement(IfStatement ifStmt) {
        gate.edit(ifStmt, EditKind.BODY, false, (cu, code) -> addElseToIfStatement(cu, code, ifStmt));
    }

    /**
     * Inserts another branch into a chain, after the link at {@code afterIndex}.
     *
     * <p>{@code markUnedited} is true for the same reason every other insert sets it: the statement now holds
     * a branch that tests {@code false} and does nothing, which is a shape the user has to finish rather than
     * a change to what the bot does.
     */
    void addBranchLink(Statement chain, int afterIndex) {
        gate.insert(chain, EditKind.BODY, true,
                (cu, code) -> BranchChainHandler.applyAddLink(gate.ctx(cu), code, chain, afterIndex));
    }

    /** Removes one branch from a chain, closing it over the gap. Refused for the last remaining link. */
    void removeBranchLink(Statement chain, int index) {
        gate.edit(chain, EditKind.BODY, false,
                (cu, code) -> BranchChainHandler.applyRemoveLink(gate.ctx(cu), code, chain, index));
    }

    // The Matches switch had five editor methods here — setMatchesCheckMode, setMatchesGuard, addMatchesCase,
    // removeMatchesCase and, until 2026-08-31, setMatchesCheckTemplates. All are deleted (2026-09-01) with the
    // guarded pattern switch they wrote. Every one of them named a piece of one library's vocabulary: a check
    // mode, a guard tree, a case seeded with a picture. Their replacements are addBranchLink and
    // removeBranchLink above, which say nothing but "a branch" — a chain is composed of ordinary calls, so
    // editing one is editing an expression and a body, which these classes could already do.

    // =================================================================================
    // TRY
    // =================================================================================

    void addCatchClause(TryStatement tryStmt) {
        gate.edit(tryStmt, EditKind.BODY, false, (cu, code) -> TryHandler.addCatch(gate.ctx(cu), code, tryStmt));
    }

    /** Removes a {@code catch}; refused, with a status line, when it is all that keeps the {@code try} legal. */
    void deleteCatchClause(TryStatement tryStmt, int index) {
        gate.edit(tryStmt, EditKind.BODY, false, (cu, code) -> gate.refusedWith(
                TryHandler.deleteCatch(gate.ctx(cu), code, tryStmt, index),
                "A try needs a catch or a finally, so its last one stays."));
    }

    void addFinallyClause(TryStatement tryStmt) {
        gate.edit(tryStmt, EditKind.BODY, false, (cu, code) -> TryHandler.addFinally(gate.ctx(cu), code, tryStmt));
    }

    void deleteFinallyClause(TryStatement tryStmt) {
        gate.edit(tryStmt, EditKind.BODY, false, (cu, code) -> gate.refusedWith(
                TryHandler.deleteFinally(gate.ctx(cu), code, tryStmt),
                "A try needs a catch or a finally, so its last one stays."));
    }

    /**
     * Makes {@code clause} catch {@code typeText} ({@code IOException}, or {@code A | B}). Compiled first, as a
     * pick is: a checked exception the body never throws is refused with javac's reason.
     */
    void setCatchType(CatchClause clause, String typeText) {
        if (typeText == null || typeText.strip().equals(TryHandler.catchTypeText(clause))) return;
        gate.insert(clause, EditKind.BODY, false, (cu, code) -> gate.refusedWith(
                TryHandler.setCatchType(gate.ctx(cu), code, clause, typeText),
                "\"" + typeText.strip() + "\" is not an exception type name."));
    }

    // =================================================================================
    // COMMENTS
    // =================================================================================

    void updateComment(Comment commentNode, String newText) {
        gate.edit(commentNode, EditKind.BODY, false, (cu, code) -> updateComment(code, commentNode, newText));
    }

    void deleteComment(Comment commentNode) {
        gate.edit(commentNode, EditKind.BODY, false, (cu, code) -> deleteComment(code, commentNode));
    }

    // =================================================================================
    // PURE TRANSFORMS — each is (cu, code, …) -> code.
    // =================================================================================

    /**
     * The node a new statement's seeds read their scope off: the statement it lands before, whose scope is
     * exactly what the new one will see, or the body itself when it lands last — which the analyzer reads as
     * the body's end. The body was always passed, and read as its <em>start</em>: an assignment appended to
     * {@code main} could not see the {@code count} declared a line above it.
     */
    private static ASTNode insertionContext(ASTNode body, int index) {
        if (!(body instanceof Block block)) return body;
        int at = BodyChildren.statementIndex(block, index);
        return at < block.statements().size() ? (ASTNode) block.statements().get(at) : block;
    }

    private static String addStatement(EditContext ctx, String originalCode, ASTNode targetBody, BlockType type, int index) {
        ASTRewrite rewriter = ctx.rewriter();
        Statement newStatement = NodeCreator.createDefaultStatement(ctx, type, insertionContext(targetBody, index));
        if (newStatement == null) return originalCode;
        ListRewrite listRewrite = AstRewriteHelper.getListRewriteForBody(rewriter, targetBody);
        insertIntoList(listRewrite, targetBody, newStatement, index, originalCode);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    private static String deleteStatement(CompilationUnit cu, String originalCode, Statement statement) {
        ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
        if (statement instanceof IfStatement ifStmt && ifStmt.getParent() instanceof IfStatement parent
                && parent.getElseStatement() == ifStmt) {
            Statement childElse = ifStmt.getElseStatement();
            if (childElse != null) {
                ASTNode moveTarget = rewriter.createMoveTarget(childElse);
                rewriter.replace(ifStmt, moveTarget, null);
                return AstRewriteHelper.applyRewrite(rewriter, originalCode);
            }
        }
        rewriter.remove(statement, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    /**
     * The rewrite behind {@link #deleteVariable(VariableDeclarationStatement, UseFix)}: every use settled, then the
     * declaration removed.
     *
     * <p>Only a single-fragment declaration is handled — {@code int a = 1, b = 2;} is not a shape this editor
     * writes, and removing one name from it is a different edit than removing the statement. Returning null
     * refuses rather than half-doing it.
     */
    private static String deleteVariable(EditContext ctx, String originalCode,
                                         VariableDeclarationStatement decl, UseFix fix, boolean marks) {
        if (decl.fragments().size() != 1
                || !(decl.fragments().getFirst() instanceof VariableDeclarationFragment fragment)) return null;
        ASTRewrite rewriter = ctx.rewriter();
        List<SimpleName> uses = AstRewriteHelper.referencesWithin(fragment.getName());

        if (fix instanceof UseFix.Rename rename) {
            for (SimpleName use : uses) {
                rewriter.set(use, SimpleName.IDENTIFIER_PROPERTY, rename.variableName(), null);
            }
        } else {
            // A use that *writes* the variable can't take a value in its place — `0 = 5;` is not source — so the
            // line that wrote it goes with the declaration. Collected first because one such line can hold
            // several uses (`attempts = attempts + 1;`), and the ones inside it must not also be replaced.
            Set<Statement> dropped = new LinkedHashSet<>();
            for (SimpleName use : uses) {
                Statement written = writeStatementOf(use);
                if (written != null) dropped.add(written);
            }
            for (Statement statement : dropped) rewriter.remove(statement, null);
            ResolvedType type = ProjectAnalyzer.resolveType(decl.getType());
            for (SimpleName use : uses) {
                if (dropped.stream().anyMatch(statement -> isInside(use, statement))) continue;
                Expression value = InitializerFactory.createDefaultInitializer(ctx, type);
                if (value == null) return null;
                rewriter.replace(use, value, null);
            }
        }

        rewriter.remove(decl, null);
        if (marks && !uses.isEmpty()) {
            String name = fragment.getName().getIdentifier();
            ReviewMarks.mark(ctx, AstRewriteHelper.enclosingMethod(decl),
                    List.of("the variable \"" + name + "\" was deleted, and the "
                            + uses.size() + (uses.size() == 1 ? " place" : " places")
                            + " that read it now read a default value instead."));
        }
        return ctx.applyTo(originalCode);
    }

    /** The statement {@code use} is the target of — {@code x = 1;}, {@code x += 1;}, {@code x++;} — or null. */
    private static Statement writeStatementOf(SimpleName use) {
        ASTNode parent = use.getParent();
        boolean isTarget = switch (parent) {
            case Assignment assignment -> assignment.getLeftHandSide() == use;
            case PostfixExpression postfix -> postfix.getOperand() == use;
            case PrefixExpression prefix -> prefix.getOperand() == use
                    && (prefix.getOperator() == PrefixExpression.Operator.INCREMENT
                    || prefix.getOperator() == PrefixExpression.Operator.DECREMENT);
            case null, default -> false;
        };
        return isTarget && parent.getParent() instanceof ExpressionStatement statement ? statement : null;
    }

    private static boolean isInside(ASTNode node, ASTNode ancestor) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n == ancestor) return true;
        }
        return false;
    }

    private static String pasteCodeString(EditContext ctx, String originalCode, ASTNode targetBody, int index,
                                          String codeToPaste) {
        ASTRewrite rewriter = ctx.rewriter();
        // The clipboard carries bare source with no import context (an in-app copy is just node.toString()),
        // so a pasted ImageFinder.find(...) would land in a file that never imported ImageFinder. Bring the
        // imports along; addImportForSimpleName is a no-op for anything already imported or unresolvable.
        for (String typeName : referencedTypeNames(codeToPaste)) {
            ctx.addImportForSimpleName(typeName);
        }
        Statement placeHolder = (Statement) rewriter.createStringPlaceholder(codeToPaste, ASTNode.EMPTY_STATEMENT);
        ListRewrite listRewrite = AstRewriteHelper.getListRewriteForBody(rewriter, targetBody);
        insertIntoList(listRewrite, targetBody, placeHolder, index, originalCode);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    private static String moveStatement(CompilationUnit cu, String originalCode, ASTNode toMove, ASTNode sourceBody, ASTNode targetBody, int targetIndex) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        // A block's backing node isn't always the Statement itself: a bare expression block (e.g.
        // ImageFinder.find(...)) stores the MethodInvocation, whose enclosing ExpressionStatement is what
        // the list rewrite operates on. Walk up to the nearest Statement rather than casting blindly.
        Statement statement = enclosingStatement(toMove);
        if (statement == null) return originalCode;
        ListRewrite sourceListRewrite = AstRewriteHelper.getListRewriteForBody(rewriter, sourceBody);
        ListRewrite targetListRewrite = AstRewriteHelper.getListRewriteForBody(rewriter, targetBody);
        Statement copiedStatement = (Statement) JavaLevel.copy(ast, statement);
        sourceListRewrite.remove(statement, null);
        insertIntoList(targetListRewrite, targetBody, copiedStatement, targetIndex, originalCode);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    /** The nearest enclosing {@link Statement} of {@code node} (itself if already one), or {@code null}. */
    private static Statement enclosingStatement(ASTNode node) {
        while (node != null && !(node instanceof Statement)) {
            node = node.getParent();
        }
        return (Statement) node;
    }

    private static String convertElseToElseIf(CompilationUnit cu, String originalCode, IfStatement ifStatement) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        Statement elseStatement = ifStatement.getElseStatement();
        if (elseStatement != null && elseStatement.getNodeType() == ASTNode.BLOCK) {
            IfStatement newElseIf = ast.newIfStatement();
            newElseIf.setExpression(ast.newBooleanLiteral(true));
            newElseIf.setThenStatement((Block) JavaLevel.copy(ast, elseStatement));
            rewriter.replace(elseStatement, newElseIf, null);
        }
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    private static String addElseToIfStatement(CompilationUnit cu, String originalCode, IfStatement ifStatement) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        if (ifStatement.getElseStatement() == null) {
            rewriter.set(ifStatement, IfStatement.ELSE_STATEMENT_PROPERTY, ast.newBlock(), null);
        }
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    private static String deleteElseFromIfStatement(CompilationUnit cu, String originalCode, IfStatement ifStatement) {
        ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
        if (ifStatement.getElseStatement() != null) {
            rewriter.remove(ifStatement.getElseStatement(), null);
        }
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    private static String updateComment(String originalCode, Comment commentNode, String newText) {
        try {
            IDocument document = new Document(originalCode);
            int end = commentNode.getStartPosition() + commentNode.getLength();
            document.replace(commentNode.getStartPosition(), commentNode.getLength(),
                    CommentText.written(newText, codeFollows(originalCode, end)));
            return document.get();
        } catch (Exception e) {
            return originalCode;
        }
    }

    /** Whether anything but white space follows {@code offset} on its line. */
    private static boolean codeFollows(String code, int offset) {
        for (int i = offset; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '\n' || c == '\r') return false;
            if (!Character.isWhitespace(c)) return true;
        }
        return false;
    }

    private static String deleteComment(String originalCode, Comment commentNode) {
        try {
            IDocument document = new Document(originalCode);
            document.replace(commentNode.getStartPosition(), commentNode.getLength(), "");
            return document.get();
        } catch (Exception e) {
            return originalCode;
        }
    }

    /**
     * The simple type names a pasted snippet refers to, for import resolution.
     *
     * <p>Parsed rather than regex-matched: the snippet is re-parsed as a statement sequence (no bindings
     * needed — {@link ImportManager} resolves the names itself) and two shapes are collected, which between
     * them cover what a copied block looks like:
     * <ul>
     *   <li>{@link SimpleType} — declarations and {@code new Foo(...)}</li>
     *   <li>the scope of a static call or constant, i.e. the {@code Foo} in {@code Foo.bar()} /
     *       {@code Foo.BAZ}, which appears only as a {@link SimpleName} and has no type node at all</li>
     * </ul>
     *
     * <p>Best-effort: unparseable clipboard text yields an empty set rather than failing the paste, and a
     * false positive (a variable named like a type) is harmless — the import simply won't resolve.
     */
    private static Set<String> referencedTypeNames(String snippet) {
        Set<String> names = new LinkedHashSet<>();
        try {
            // Clipboard text, read only for the names it mentions: the newest Java reads the most.
            Block parsed = SourceParser.statements(JavaLevel.LATEST, snippet);
            if (parsed == null) return Set.of();
            parsed.accept(new ASTVisitor() {
                @Override
                public boolean visit(SimpleType node) {
                    names.add(node.getName().getFullyQualifiedName());
                    return true;
                }

                @Override
                public boolean visit(MethodInvocation node) {
                    if (node.getExpression() instanceof SimpleName scope) addIfTypeLike(scope);
                    return true;
                }

                @Override
                public boolean visit(QualifiedName node) {
                    if (node.getQualifier() instanceof SimpleName scope) addIfTypeLike(scope);
                    return true;
                }

                /** Uppercase-initial is the only signal available without bindings — the JLS naming convention. */
                private void addIfTypeLike(SimpleName name) {
                    String id = name.getIdentifier();
                    if (!id.isEmpty() && Character.isUpperCase(id.charAt(0))) names.add(id);
                }
            });
        } catch (Exception e) {
            return Set.of();
        }
        return names;
    }

    /**
     * Inserts {@code newStatement} at a body's child index, which counts the comments drawn in it, always on the
     * rewriter: after a comment too ({@link BodyInserts}, which reads {@code source}, the text the tree was parsed
     * from, only to write a comment again). A statement the same rewrite removes from that body is removed first.
     */
    static void insertIntoList(ListRewrite listRewrite, ASTNode node, Statement newStatement,
                               int relativeIndex, String source) {
        if (node instanceof Block block) {
            BodyInserts.insert(listRewrite.getASTRewrite(), block, relativeIndex, newStatement, source);
        } else if (node instanceof SwitchCase caseNode) {
            // Colon form only. A case's statements are siblings of its label here, so offsetting from the label
            // is the insertion point. An arrow rule's body is a Block instead, and this arithmetic would write
            // the statement in front of that Block — a bare block among arrow rules, which doesn't parse and
            // takes the branch's contents off the canvas with it. BlockConverter backs an arrow rule's body
            // with its Block precisely so nothing reaches here; refuse rather than trust that silently.
            if (caseNode.isSwitchLabeledRule()) {
                throw new IllegalArgumentException(
                        "an arrow rule's body is its Block, not its label — see BlockConverter.labeledRuleBody");
            }
            SwitchStatement parent = (SwitchStatement) caseNode.getParent();
            List<?> allStatements = parent.statements();
            int caseIndex = allStatements.indexOf(caseNode);
            int absoluteIndex = caseIndex + 1 + relativeIndex;
            listRewrite.insertAt(newStatement, absoluteIndex, null);
        }
    }
}
