package com.botmaker.core.edit;

import com.botmaker.core.parser.CodeEditor;
import com.botmaker.core.parser.EditorOutput;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectState;
import com.botmaker.java.edit.BlockId;
import com.botmaker.java.edit.BodyChildren;
import com.botmaker.java.edit.palette.BlockType;
import com.botmaker.java.types.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BreakStatement;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One step of an {@link EditSession}: a parsed tree, and a {@link CodeEditor} over it whose output goes nowhere. What it
 * would publish is kept here, for the session to judge. A target is found by its {@link BlockId} in this tree, and what
 * counts as a body and as a line of it is read off the tree, the same way a client draws them.
 */
public final class EditStage {

    public final ProjectState state;
    public final CompilationUnit cu;
    public final ProjectAnalyzer analyzer;
    public final CodeEditor editor;

    final List<String> status = new ArrayList<>();
    final List<BlockType> added = new ArrayList<>();
    EditorOutput.Write written;

    EditStage(ProjectConfig config, ProjectState state, CompilationUnit cu, ProjectAnalyzer analyzer,
              EditWorkspace workspace) {
        this.state = state;
        this.cu = cu;
        this.analyzer = analyzer;
        EditorOutput kept = new EditorOutput() {
            @Override
            public void status(String message) {
                status.add(message);
            }

            @Override
            public void written(Write write) {
                written = write;
            }

            @Override
            public void blockAdded(BlockType type) {
                added.add(type);
            }
        };
        this.editor = workspace != null && workspace.journal() != null
                ? new CodeEditor(config, state, kept, analyzer, workspace.journal())
                : new CodeEditor(config, state, kept, analyzer);
    }

    /** The node of class {@code kind} that {@code id} names in this tree. */
    public <T extends ASTNode> Optional<T> findNode(String id, Class<T> kind) {
        if (cu == null || id == null) return Optional.empty();
        return BlockId.find(cu, id).filter(kind::isInstance).map(kind::cast);
    }

    /**
     * The statement {@code id} names: by its own id, or, as a call statement is named by its call, by the id of an
     * expression statement's expression. A method's body or a branch's is a body, not a statement; only a block standing
     * as a line of another body is both.
     */
    public Optional<Statement> statement(String id) {
        return findNode(id, Statement.class).filter(s -> !(s instanceof Block) || s.getParent() instanceof Block)
                .or(() -> findNode(id, Expression.class).map(ASTNode::getParent)
                        .filter(ExpressionStatement.class::isInstance).map(Statement.class::cast));
    }

    /**
     * The body {@code id} names: a {@link Block}, or the label of a {@code case} of a switch statement whose lines stand
     * under it. An arrow rule with braces has its block for a body, as the insert lands inside it. A switch expression's
     * label is no body: the editor inserts only under a statement's.
     */
    public Optional<ASTNode> body(String id) {
        return findNode(id, ASTNode.class).filter(node -> switch (node) {
            case Block ignored -> true;
            case SwitchCase label -> label.getParent() instanceof SwitchStatement && ruleBlock(label) == null;
            default -> false;
        });
    }

    /** How many lines {@code body} has, which is what an index into it counts: comments too, a closing break not. */
    public int lines(ASTNode body) {
        return switch (body) {
            case Block block -> block.statements().size()
                    + (cu == null ? 0 : BodyChildren.commentsOf(block, BodyChildren.nonJavadoc(cu)).size());
            case SwitchCase label when label.getParent() instanceof SwitchStatement owner -> {
                List<?> statements = owner.statements();
                int count = 0;
                for (int i = statements.indexOf(label) + 1; i < statements.size(); i++) {
                    if (statements.get(i) instanceof SwitchCase) break;
                    if (!closesCase(statements, i)) count++;
                }
                yield count;
            }
            default -> 0;
        };
    }

    /** The body {@code statement} is a line of, or empty when it stands in none: a lone branch, a case's closing break. */
    public Optional<ASTNode> bodyOf(Statement statement) {
        ASTNode parent = statement.getParent();
        if (parent instanceof Block) return Optional.of(parent);
        if (!(parent instanceof SwitchStatement owner)) return Optional.empty();
        List<?> statements = owner.statements();
        int at = statements.indexOf(statement);
        if (closesCase(statements, at)) return Optional.empty();
        for (int i = at - 1; i >= 0; i--) {
            if (statements.get(i) instanceof SwitchCase label) return Optional.of(label);
        }
        return Optional.empty();
    }

    private static boolean closesCase(List<?> statements, int i) {
        return statements.get(i) instanceof BreakStatement
                && (i == statements.size() - 1 || statements.get(i + 1) instanceof SwitchCase);
    }

    private static Block ruleBlock(SwitchCase label) {
        if (!label.isSwitchLabeledRule() || !(label.getParent() instanceof SwitchStatement owner)) return null;
        List<?> statements = owner.statements();
        int next = statements.indexOf(label) + 1;
        return next < statements.size() && statements.get(next) instanceof Block block ? block : null;
    }
}
