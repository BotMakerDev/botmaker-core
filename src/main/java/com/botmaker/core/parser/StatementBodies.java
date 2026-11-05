package com.botmaker.core.parser;

import com.botmaker.java.edit.BodyChildren;
import com.botmaker.java.edit.handlers.BranchChainHandler;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The bodies of a bot's code as the editor draws them (2026-11-05, plan phase 9a): what lines a body has, and which
 * bodies sit inside a statement. Studio's block converter builds its body blocks from these and the core's
 * {@code read_tree} lists them, so a body the canvas shows is the body the assistant is told of, with the same id.
 *
 * <p>A body is anchored on a JDT {@link Block}, except a {@code case X:} body, which has none: its anchor is the
 * {@link SwitchCase} label, and its lines the statements after the label, the case's closing {@code break} left out.
 */
public final class StatementBodies {

    /** One body: the node its id is read from, and its lines (statements and comments) in source order. */
    public record Body(ASTNode anchor, List<ASTNode> lines) {
        public Body {
            lines = List.copyOf(lines);
        }
    }

    /** One case of a switch statement: its label, its body, and whether a closing {@code break} ends it. */
    public record Case(SwitchCase label, Body body, boolean closingBreak) {
    }

    private StatementBodies() {
    }

    /**
     * The node a body's {@code line} is addressed by: a call run as a statement is its block by the call itself (its
     * id ends {@code /expression}), as the canvas draws it; every other line by itself.
     */
    public static ASTNode addressOf(ASTNode line) {
        StatementKind kind = StatementKind.of(line);
        return (kind == StatementKind.PROJECT_CALL || kind == StatementKind.EXTERNAL_CALL)
                && line instanceof ExpressionStatement statement ? statement.getExpression() : line;
    }

    /** {@code block}'s statements and its own {@code comments} (the unit's non-Javadoc ones), in source order. */
    public static Body of(Block block, List<Comment> comments) {
        List<ASTNode> lines = new ArrayList<>();
        for (Object statement : block.statements()) lines.add((ASTNode) statement);
        lines.addAll(BodyChildren.commentsOf(block, comments));
        lines.sort(Comparator.comparingInt(ASTNode::getStartPosition));
        return new Body(block, lines);
    }

    /**
     * The cases of {@code statement}, in order. An arrow rule's braced body is the Block that follows its label; a
     * colon case's body is the statements up to the next label, its last {@code break} the case's own chrome.
     */
    public static List<Case> cases(SwitchStatement statement, List<Comment> comments) {
        List<?> statements = statement.statements();
        List<Case> cases = new ArrayList<>();
        for (int i = 0; i < statements.size(); i++) {
            if (!(statements.get(i) instanceof SwitchCase label)) continue;
            if (label.isSwitchLabeledRule() && i + 1 < statements.size() && statements.get(i + 1) instanceof Block rule) {
                cases.add(new Case(label, of(rule, comments), false));
                i++;
                continue;
            }
            List<ASTNode> lines = new ArrayList<>();
            boolean closingBreak = false;
            int j = i + 1;
            for (; j < statements.size() && !(statements.get(j) instanceof SwitchCase); j++) {
                boolean last = j == statements.size() - 1 || statements.get(j + 1) instanceof SwitchCase;
                if (statements.get(j) instanceof BreakStatement && last) closingBreak = true;
                else lines.add((ASTNode) statements.get(j));
            }
            cases.add(new Case(label, new Body(label, lines), closingBreak));
            i = j - 1;
        }
        return cases;
    }

    /**
     * The bodies directly inside {@code statement}, in source order — not their statements' bodies, which those
     * statements list. A statement drawn as source has none; an else-if or a labelled loop lends its own.
     */
    public static List<Body> inside(Statement statement, List<Comment> comments) {
        List<Body> bodies = new ArrayList<>();
        switch (StatementKind.of(statement)) {
            case SOURCE, BREAK, CONTINUE, DECLARE_ENUM, COMMENT -> { }
            case BODY -> bodies.add(of((Block) statement, comments));
            // A chain's links, each a body after its condition, as the canvas draws one row per link.
            case BRANCH_CHAIN -> {
                for (BranchChainHandler.Link link : BranchChainHandler.read(
                        (MethodInvocation) ((ExpressionStatement) statement).getExpression())) {
                    bodies.add(of(link.body(), comments));
                }
            }
            case SWITCH -> {
                SwitchStatement switched = (SwitchStatement) statement;
                if (switched.getExpression() != null) switched.getExpression().accept(new Finder(null, bodies, comments));
                for (Case each : cases(switched, comments)) bodies.add(each.body());
            }
            default -> statement.accept(new Finder(statement, bodies, comments));
        }
        return bodies;
    }

    /** Walks a statement's own parts, stopping at each body and each statement inside it, which answer for theirs. */
    private static final class Finder extends ASTVisitor {
        private final ASTNode root;
        private final List<Body> bodies;
        private final List<Comment> comments;

        Finder(ASTNode root, List<Body> bodies, List<Comment> comments) {
            this.root = root;
            this.bodies = bodies;
            this.comments = comments;
        }

        @Override
        public boolean preVisit2(ASTNode node) {
            if (node == root) return true;
            if (node instanceof Block block) {
                bodies.add(of(block, comments));
                return false;
            }
            if (node instanceof Statement statement) {
                bodies.addAll(inside(statement, comments));
                return false;
            }
            return true;
        }

        /**
         * A call draws its arguments, not its receiver, which shows as a scope string: a lambda in
         * {@code supplyAsync(() -> { … }).join()}'s receiver is no body on the canvas.
         */
        @Override
        public boolean visit(MethodInvocation call) {
            for (Object argument : call.arguments()) ((ASTNode) argument).accept(this);
            return false;
        }

        /** A class body written in place is drawn as source: nothing in it is a body. */
        @Override
        public boolean visit(AnonymousClassDeclaration declaration) {
            return false;
        }

        /** Only an arrow switch value is drawn as blocks; one with colon labels is source. */
        @Override
        public boolean visit(SwitchExpression expression) {
            List<?> statements = expression.statements();
            if (!statements.stream().filter(SwitchCase.class::isInstance)
                    .allMatch(s -> ((SwitchCase) s).isSwitchLabeledRule())) {
                return false;
            }
            expression.getExpression().accept(this);
            for (Object each : statements) {
                switch (each) {
                    case SwitchCase ignored -> { }
                    case YieldStatement yield when yield.isImplicit() && yield.getExpression() != null ->
                            yield.getExpression().accept(this);
                    case Block block -> bodies.add(of(block, comments));
                    case Statement other -> bodies.addAll(inside(other, comments));
                    default -> { }
                }
            }
            return false;
        }
    }
}
