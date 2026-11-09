package com.botmaker.core.parser.refactor;

import com.botmaker.core.plugin.PluginHost;
import com.botmaker.core.project.vcs.ProjectVcs;
import com.botmaker.core.project.vcs.VersionReader;
import com.botmaker.java.edit.EditContext;
import com.botmaker.java.edit.refactor.ReviewMarks;
import com.botmaker.java.grammar.SourceParser;
import com.botmaker.java.grammar.TypeNodes;
import com.botmaker.java.parse.BotParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Puts a function a refactor marked ({@code @Refactor}) back as the newest saved version holds it <em>without</em>
 * that mark: the state just before the refactor that wrote it, which every refactor saves first. Any version counts,
 * not only the refactor's own: the newest one without the mark is the one before it. Only that function changes,
 * with the single-type imports its old body names; the canvas's unused-import pass takes back any it does not need.
 *
 * <p>The function is written back as its earlier source, a string placeholder: a method restored exactly as written
 * is one of the places Java is held as text, since a tree copy would lose its comments and layout. The client asks by
 * the function's address ({@code server.Request.Restore}); the earlier text never crosses the channel.
 *
 * <p>It refuses, with a sentence, when the function is no longer marked so, when no version has it without the mark,
 * or when what it would write does not parse. What it brings back may not compile: the refactor ran because something
 * it used went away. Blocking: it reads the project's history.
 */
public final class FunctionRestore {

    private FunctionRestore() {}

    /** What a restore came to: the file's new text and the version it came back from, or why not. */
    public sealed interface Outcome {
        record Restored(String source, String version) implements Outcome {}

        record Refused(String reason) implements Outcome {}
    }

    /** The function as an earlier version wrote it, the version's title, and the imports the declaration names. */
    private record Earlier(String version, String declaration, List<String> imports) {}

    /**
     * {@code source}, the text of {@code file} in the project at {@code projectDir}, with its function
     * {@code function} whose open {@code @Refactor} entries are {@code entries} put back as it was.
     */
    public static Outcome restore(Path projectDir, Path file, String source, String function, List<String> entries) {
        CompilationUnit unit = BotParser.syntax(source);
        MethodDeclaration target = SourceParser.hasSyntaxErrors(unit) ? null : marked(unit, function, entries);
        if (target == null) return new Outcome.Refused(function + "() changed since the list was drawn.");
        Earlier earlier;
        try {
            earlier = earlier(projectDir, file, function, entries, target);
        } catch (IOException e) {
            return new Outcome.Refused("This bot's versions could not be read: " + e.getMessage());
        }
        if (earlier == null) {
            return new Outcome.Refused("No saved version has " + function + "() as it was before this change.");
        }
        EditContext ctx = EditContext.of(unit, null, PluginHost.typesOnly());
        ctx.rewriter().replace(target,
                ctx.rewriter().createStringPlaceholder(earlier.declaration(), target.getNodeType()), null);
        for (String name : earlier.imports()) ctx.addImport(name);
        String rewritten = ctx.applyTo(source);
        // Same rule as every other rewrite: a result that does not parse is not written.
        if (rewritten == null || SourceParser.hasSyntaxErrors(BotParser.syntax(rewritten))) {
            return new Outcome.Refused("The old version of " + function + "() does not fit the file as it is now.");
        }
        return new Outcome.Restored(rewritten, earlier.version());
    }

    /** The marked {@code function} whose open entries are {@code entries} (two overloads can both be marked), or null. */
    private static MethodDeclaration marked(CompilationUnit unit, String function, List<String> entries) {
        for (MethodDeclaration method : ReviewMarks.markedIn(unit)) {
            if (method.getName().getIdentifier().equals(function)
                    && ReviewMarks.openEntriesOf(method).equals(entries)) {
                return method;
            }
        }
        return null;
    }

    /** The newest version's {@code function} without the mark, matched by name and {@code target}'s parameters, or null. */
    private static Earlier earlier(Path projectDir, Path file, String function, List<String> entries,
                                   MethodDeclaration target) throws IOException {
        String relative = projectDir.toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        VersionReader reader = new VersionReader(projectDir);
        for (ProjectVcs.CommitInfo commit : new ProjectVcs(projectDir).history()) {
            byte[] bytes = reader.at(commit.sha(), relative);
            if (bytes == null) continue;
            Earlier before = unmarked(new String(bytes, StandardCharsets.UTF_8), function, entries, target,
                    commit.title());
            if (before != null) return before;
        }
        return null;
    }

    /** {@code function} in {@code source} when it carries none of {@code entries}, else null. */
    private static Earlier unmarked(String source, String function, List<String> entries, MethodDeclaration target,
                                    String version) {
        CompilationUnit unit = BotParser.syntax(source);
        for (Object type : unit.types()) {
            if (!(type instanceof TypeDeclaration declaration)) continue;
            for (MethodDeclaration method : declaration.getMethods()) {
                if (!method.getName().getIdentifier().equals(function)) continue;
                if (!sameParameters(method, target)) continue;
                if (ReviewMarks.openEntriesOf(method).stream().anyMatch(entries::contains)) continue;
                String text = source.substring(method.getStartPosition(),
                        method.getStartPosition() + method.getLength());
                List<String> imports = new ArrayList<>();
                for (Object each : unit.imports()) {
                    ImportDeclaration imported = (ImportDeclaration) each;
                    if (imported.isStatic() || imported.isOnDemand()) continue;
                    String qualified = imported.getName().getFullyQualifiedName();
                    if (names(method, qualified.substring(qualified.lastIndexOf('.') + 1))) imports.add(qualified);
                }
                return new Earlier(version, text, List.copyOf(imports));
            }
        }
        return null;
    }

    /**
     * What tells {@code one} from an overload of its name: the same parameter types, each as its own file resolves it
     * ({@link TypeNodes#same}), with the same array parts and varargs.
     */
    private static boolean sameParameters(MethodDeclaration one, MethodDeclaration other) {
        if (one.parameters().size() != other.parameters().size()) return false;
        for (int i = 0; i < one.parameters().size(); i++) {
            SingleVariableDeclaration a = (SingleVariableDeclaration) one.parameters().get(i);
            SingleVariableDeclaration b = (SingleVariableDeclaration) other.parameters().get(i);
            if (a.isVarargs() != b.isVarargs() || a.getExtraDimensions() != b.getExtraDimensions()
                    || !TypeNodes.same(a.getType(), b.getType())) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code method}'s code says {@code simple}: a comment or a string says nothing. */
    private static boolean names(MethodDeclaration method, String simple) {
        boolean[] found = {false};
        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName name) {
                found[0] |= name.getIdentifier().equals(simple);
                return false;
            }
        });
        return found[0];
    }
}
