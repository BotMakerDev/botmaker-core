package com.botmaker.core.project.managed;

import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.params.JavaParameters;
import com.botmaker.java.edit.helpers.AstRewriteHelper;
import com.botmaker.java.index.BotIndex;
import com.botmaker.java.parse.BotParser;
import com.botmaker.plugin.api.source.ManagedParam;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.host.PluginCoordinate;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A plugin's parameter written in another group than its holder's, moved there whole (2026-11-05): the plugin
 * moved it, say the SDK's {@code capture} from {@code Sdk} to {@code Game}, and a bind found it in the old group.
 * The method goes as the user has it, comments and value included, in {@code params/} and in its starting copy
 * under {@code starting-params/}, so the fitter finds the pair where it looks. A group the move leaves empty goes,
 * both copies; the group it goes to is written when the bot lacks it.
 *
 * <p>The group the parameter sits in is found by the plugin's mark on its method, wherever under {@code params/}
 * it is. A move is skipped, and said in {@link Moves#left()}, when the starting copy lacks the method — nothing
 * here invents a starting value — when the target group has a method of that name already, or when the bot would
 * no longer compile.
 *
 * <p>Pure, over the bot's one parse and the starting copies on disk: nothing is written.
 */
public final class MisplacedParams {

    private MisplacedParams() {}

    /**
     * The bot after the moves.
     *
     * @param rewrites each file under {@code params/} written or created, with its whole new text
     * @param starting each starting copy written or created, outside the sources, with its whole new text
     * @param deleted  each group file the moves emptied, in {@code params/} and in {@code starting-params/}
     * @param moved    what was moved, one sentence each
     * @param left     what was not, and why, one sentence each
     */
    public record Moves(Map<Path, String> rewrites, Map<Path, String> starting, Set<Path> deleted,
                        List<String> moved, List<String> left) {

        public Moves {
            rewrites = Map.copyOf(rewrites);
            starting = Map.copyOf(starting);
            deleted = Set.copyOf(deleted);
            moved = List.copyOf(moved);
            left = List.copyOf(left);
        }

        /** Whether the moves change nothing. */
        public boolean isEmpty() {
            return rewrites.isEmpty() && starting.isEmpty() && deleted.isEmpty();
        }
    }

    /** Where one parameter belongs: its plugin and its holder's group file. */
    private record Home(PluginCoordinate plugin, String holder, Path file) {}

    /** A plugin's parameter method in the bot. */
    private record Found(String id, Path file, String className, String method) {}

    /** One file's text after a move out ({@code null} when nothing is left in it) and the target's after. */
    private record Moved(String from, String to) {}

    /**
     * The moves that put each parameter of {@code byPlugin} in its holder's group.
     *
     * @param byPlugin each plugin's coordinate and the values it declares
     */
    public static Moves plan(ProjectConfig config, BotIndex index,
                             Map<PluginCoordinate, List<ManagedValue<?>>> byPlugin) {
        List<ManagedValue<?>> known = new ArrayList<>();
        Map<String, Home> homes = new LinkedHashMap<>();
        byPlugin.forEach((plugin, declared) -> {
            for (ManagedValue<?> value : declared) {
                if (value == null) continue;
                known.add(value);
                Path file = ManagedHolders.groupFile(config, plugin, value.holder());
                if (value instanceof ManagedParam<?> && file != null) {
                    homes.putIfAbsent(value.id(), new Home(plugin, value.holder(), normal(file)));
                }
            }
        });
        Path paramsDir = normal(config.parametersPackageDir());
        List<Found> found = index.read(units -> {
            List<Found> out = new ArrayList<>();
            units.forEach((file, unit) -> {
                if (!file.startsWith(paramsDir)) return;
                for (Object each : unit.types()) {
                    if (!(each instanceof TypeDeclaration type)) continue;
                    for (MethodDeclaration method : type.getMethods()) {
                        String id = ManagedIds.idOf(ManagedIds.on(method, known), known);
                        if (!id.isEmpty()) {
                            out.add(new Found(id, file, type.getName().getIdentifier(),
                                    method.getName().getIdentifier()));
                        }
                    }
                }
            });
            return List.copyOf(out);
        });

        Map<Path, String> sources = new LinkedHashMap<>(index.sources());
        Map<Path, String> starting = new LinkedHashMap<>();
        Set<Path> startingGone = new LinkedHashSet<>();
        BotIndex current = index;
        List<String> moved = new ArrayList<>();
        List<String> left = new ArrayList<>();
        for (Found each : found) {
            Home home = homes.get(each.id());
            if (home == null || home.file().equals(each.file()) || !sources.containsKey(each.file())) continue;
            String name = each.className() + "." + each.method() + "()";
            String why = name + " belongs in the group " + home.holder();
            Path fromStarting = JavaParameters.startingFile(config, each.file()).map(MisplacedParams::normal)
                    .orElse(null);
            Path toStarting = JavaParameters.startingFile(config, home.file()).map(MisplacedParams::normal)
                    .orElse(null);
            String fromStartingText = fromStarting == null || startingGone.contains(fromStarting) ? null
                    : starting.computeIfAbsent(fromStarting, MisplacedParams::read);
            // A starting copy an earlier move emptied is gone, whatever the disk still holds.
            String toStartingRead = toStarting == null || startingGone.contains(toStarting) ? null
                    : starting.computeIfAbsent(toStarting, MisplacedParams::read);
            String empty = sources.containsKey(home.file()) && toStartingRead != null ? null
                    : ManagedHolders.emptyGroup(config, home.plugin(), home.holder());
            String toText = sources.getOrDefault(home.file(), empty);
            String toStartingText = toStarting == null ? null : Optional.ofNullable(toStartingRead).orElse(empty);
            if (fromStartingText == null || toStartingText == null) {
                left.add(why + ", but its starting copy in starting-params/ is missing, so it was not moved.");
                continue;
            }
            Optional<Moved> inParams = move(sources.get(each.file()), toText, each.className(), each.method(),
                    home.holder());
            Optional<Moved> inStarting = move(fromStartingText, toStartingText, each.className(), each.method(),
                    home.holder());
            if (inStarting.isEmpty() && missing(fromStartingText, each.className(), each.method())) {
                left.add(why + ", but its starting copy in starting-params/ is missing, so it was not moved.");
                continue;
            }
            if (inParams.isEmpty() || inStarting.isEmpty()) {
                left.add(why + ", which has a method of that name already, so it was not moved; merge the two by"
                        + " hand.");
                continue;
            }
            Map<Path, String> tried = new LinkedHashMap<>(sources);
            tried.put(home.file(), inParams.get().to());
            if (inParams.get().from() == null) tried.remove(each.file());
            else tried.put(each.file(), inParams.get().from());
            BotIndex after = current.replacing(tried);
            Optional<String> broken = current.firstNewError(after);
            if (broken.isPresent()) {
                left.add(why + ", but the bot would no longer compile there (" + broken.get()
                        + "), so it was not moved.");
                continue;
            }
            sources = tried;
            current = after;
            starting.put(toStarting, inStarting.get().to());
            startingGone.remove(toStarting);
            if (inStarting.get().from() == null) {
                starting.remove(fromStarting);
                startingGone.add(fromStarting);
            } else {
                starting.put(fromStarting, inStarting.get().from());
            }
            moved.add(name + " was moved to the group " + home.holder() + ", where its plugin declares it now.");
        }

        Map<Path, String> rewrites = new LinkedHashMap<>();
        sources.forEach((file, text) -> {
            if (!text.equals(index.sources().get(file))) rewrites.put(file, text);
        });
        Set<Path> deleted = new LinkedHashSet<>(index.sources().keySet());
        deleted.removeAll(sources.keySet());
        Map<Path, String> startingWrites = new LinkedHashMap<>();
        if (!moved.isEmpty()) {
            starting.forEach((file, text) -> {
                if (text != null && !text.equals(read(file))) startingWrites.put(file, text);
            });
            for (Path gone : startingGone) {
                if (Files.isRegularFile(gone)) deleted.add(gone);
            }
        }
        return new Moves(rewrites, startingWrites, deleted, moved, left);
    }

    /**
     * {@code className.method} moved from {@code from} into {@code toClass} of {@code to}, as the text it is
     * written as, its leading comments and the imports its names need with it; empty when {@code from} lacks it or
     * {@code to} has a method of its name already.
     */
    private static Optional<Moved> move(String from, String to, String className, String method, String toClass) {
        CompilationUnit source = BotParser.syntax(from);
        MethodDeclaration moving = method(source, className, method);
        CompilationUnit target = BotParser.syntax(to);
        TypeDeclaration into = type(target, toClass);
        if (moving == null || into == null || method(target, toClass, method) != null) return Optional.empty();
        int start = source.getExtendedStartPosition(moving);
        String text = from.substring(start, start + source.getExtendedLength(moving));

        ASTRewrite out = ASTRewrite.create(source.getAST());
        out.remove(moving, null);
        dropImportsOnlyIn(out, source, moving);
        TypeDeclaration owner = (TypeDeclaration) moving.getParent();
        String fromAfter = owner.bodyDeclarations().size() == 1 && source.types().size() == 1 ? null
                : AstRewriteHelper.applyRewrite(out, from);

        ASTRewrite in = ASTRewrite.create(target.getAST());
        ListRewrite members = in.getListRewrite(into, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        members.insertLast(in.createStringPlaceholder(text, ASTNode.METHOD_DECLARATION), null);
        addImports(in, target, source, moving);
        return Optional.of(new Moved(fromAfter, AstRewriteHelper.applyRewrite(in, to)));
    }

    /** Whether {@code source} lacks {@code className.method}. */
    private static boolean missing(String source, String className, String method) {
        return method(BotParser.syntax(source), className, method) == null;
    }

    private static MethodDeclaration method(CompilationUnit unit, String className, String name) {
        TypeDeclaration type = type(unit, className);
        if (type == null) return null;
        for (MethodDeclaration method : type.getMethods()) {
            if (method.getName().getIdentifier().equals(name)) return method;
        }
        return null;
    }

    private static TypeDeclaration type(CompilationUnit unit, String className) {
        for (Object each : unit.types()) {
            if (each instanceof TypeDeclaration type && type.getName().getIdentifier().equals(className)) return type;
        }
        return null;
    }

    /**
     * The single-type imports of {@code old} that {@code moving} names and {@code unit} lacks, added to it. An
     * on-demand import is not copied: two of them may declare one name, and a name only one gives fails the
     * compile check, so the move is skipped.
     */
    private static void addImports(ASTRewrite rewrite, CompilationUnit unit, CompilationUnit old,
                                   MethodDeclaration moving) {
        Set<String> used = names(moving, null);
        Set<String> have = new HashSet<>();
        for (Object each : unit.imports()) have.add(key((ImportDeclaration) each));
        AST ast = unit.getAST();
        ListRewrite imports = rewrite.getListRewrite(unit, CompilationUnit.IMPORTS_PROPERTY);
        for (Object each : old.imports()) {
            ImportDeclaration imported = (ImportDeclaration) each;
            if (imported.isOnDemand() || have.contains(key(imported))) continue;
            String name = imported.getName().getFullyQualifiedName();
            if (!used.contains(simple(name))) continue;
            ImportDeclaration added = ast.newImportDeclaration();
            added.setName(ast.newName(name));
            added.setStatic(imported.isStatic());
            imports.insertLast(added, null);
        }
    }

    /** The single-type imports of {@code unit} that {@code moving} names and nothing else in the unit does, removed. */
    private static void dropImportsOnlyIn(ASTRewrite rewrite, CompilationUnit unit, MethodDeclaration moving) {
        Set<String> inMoving = names(moving, null);
        Set<String> elsewhere = new HashSet<>();
        for (Object each : unit.types()) elsewhere.addAll(names((ASTNode) each, moving));
        for (Object each : unit.imports()) {
            ImportDeclaration imported = (ImportDeclaration) each;
            if (imported.isOnDemand()) continue;
            String name = simple(imported.getName().getFullyQualifiedName());
            if (inMoving.contains(name) && !elsewhere.contains(name)) rewrite.remove(imported, null);
        }
    }

    /** Every simple name written in {@code node}, but those inside {@code skipped}. */
    private static Set<String> names(ASTNode node, ASTNode skipped) {
        Set<String> out = new HashSet<>();
        node.accept(new ASTVisitor() {
            @Override
            public boolean preVisit2(ASTNode visited) {
                return visited != skipped;
            }

            @Override
            public boolean visit(SimpleName name) {
                out.add(name.getIdentifier());
                return true;
            }
        });
        return out;
    }

    private static String simple(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static String key(ImportDeclaration imported) {
        return (imported.isStatic() ? "static " : "") + imported.getName().getFullyQualifiedName()
                + (imported.isOnDemand() ? ".*" : "");
    }

    /** {@code file}'s text, or null when it is not there. */
    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static Path normal(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
