package com.botmaker.core.project.managed;

import com.botmaker.core.project.managed.ManagedHolders;
import com.botmaker.java.index.nav.Refactor;
import com.botmaker.core.project.PluginFiles;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.java.edit.helpers.AstRewriteHelper;
import com.botmaker.java.index.BotIndex;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Moving a plugin's holders written in an older folder into the one Studio writes now (2026-10-30, the user's
 * call): {@code plugins/sdk/Sdk.java} becomes {@code plugins/com_github_botmakerdev/botmaker_sdk/Sdk.java}. The
 * holders are the plugin's by their mark wherever they are ({@link PluginFiles}), so the move is offered, never
 * needed.
 *
 * <p>Each file keeps its text but its package; every import, qualified name and same-package use in the bot
 * follows it. The plan is a {@link Refactor.Planned}, checked like a rename: one that would stop the bot
 * compiling — a holder that uses a class of the old package that does not move, say — is refused.
 */
public final class HolderMove {

    private HolderMove() {}

    /** One holder file to move: where it is, where it goes, and its class's old and new qualified names. */
    record Moved(Path from, Path to, String oldName, String newName) {

        String oldPackage() {
            return packageOf(oldName);
        }

        String newPackage() {
            return packageOf(newName);
        }
    }

    /**
     * The holders of {@code holders} outside their plugin's folder, and every file that names them, as one plan;
     * empty when every holder is in its folder already.
     */
    public static Optional<Refactor.Outcome> plan(BotIndex index, ProjectConfig config, List<PluginFiles.Holder> holders) {
        List<Moved> moved = moves(index, config, holders);
        if (moved.isEmpty()) return Optional.empty();
        List<Moved> stale = new ArrayList<>();
        for (Moved each : moved) {
            if (!index.sources().containsKey(each.to()) && !Files.exists(each.to())) continue;
            // A committed copy of a parameter group the bot has in params/ already (2026-11-12: SwordXStaff's Game
            // and Running in plugins/): params/ is the bot's own, fitted from starting-params/, so the copy goes.
            if (ManagedHolders.groupSegmentOf(each.to()) != null && index.sources().containsKey(each.to())) {
                stale.add(each);
                continue;
            }
            return Optional.of(new Refactor.Refused(relative(config, each.to()) + " exists already, so "
                    + relative(config, each.from()) + " was not moved."));
        }
        Map<Path, String> rewrites;
        try {
            rewrites = new LinkedHashMap<>(rewrites(index, moved));
        } catch (AstRewriteHelper.RewriteFailed failed) {
            return Optional.of(new Refactor.Refused(failed.getMessage()));
        }
        // The copy "moves" onto params/'s file carrying that file's own text: the copy is gone, params/ unchanged.
        stale.forEach(each -> rewrites.put(each.from(),
                rewrites.getOrDefault(each.to(), index.sources().get(each.to()))));
        Map<Path, Path> moves = new LinkedHashMap<>();
        moved.forEach(each -> moves.put(each.from(), each.to()));
        Set<String> folders = new LinkedHashSet<>();
        moved.stream().filter(each -> !stale.contains(each))
                .forEach(each -> folders.add(relative(config, each.to().getParent()) + "/"));
        List<String> parts = new ArrayList<>();
        int count = moved.size() - stale.size();
        if (count > 0) parts.add("Moved " + count + (count == 1 ? " file" : " files") + " to " + String.join(", ", folders));
        if (!stale.isEmpty()) {
            parts.add("Removed " + String.join(", ", stale.stream().map(each -> relative(config, each.from())).toList())
                    + ", " + (stale.size() == 1 ? "a copy" : "copies") + " of what params/ holds");
        }
        Refactor.Planned plan = new Refactor.Planned(String.join("; ", parts), rewrites, moves);
        return Optional.of(Refactor.checked(index, plan, "Moving the plugin files"));
    }

    /**
     * Whether any holder of {@code holders} is outside its folder, read from the sources without bindings — what
     * is asked before the whole bot is parsed and compiled to plan the move.
     *
     * @param sources each bot file and its text
     */
    public static boolean anyOutside(Map<Path, String> sources, List<PluginFiles.Holder> holders) {
        for (Map.Entry<Path, String> each : sources.entrySet()) {
            Optional<PluginFiles.Holder> owner = PluginFiles.owner(each.getKey(), each.getValue(), holders);
            if (owner.isPresent() && !inPlace(owner.get(), each.getKey())) return true;
        }
        return false;
    }

    /**
     * Whether {@code file} of {@code holder} is where Studio writes it: anything in the plugin's own folder of
     * {@code params/} stays there, private (2026-11-09: a parameter group moved to {@code plugins/} was committed
     * with its values); a holder with values of its own is in place in its folder of {@code plugins/}.
     */
    static boolean inPlace(PluginFiles.Holder holder, Path file) {
        if (holder.segment().equals(ManagedHolders.groupSegmentOf(file))) return true;
        return !holder.parameterGroup() && holder.segment().equals(ManagedHolders.segmentOf(file));
    }

    /** The banner's line offering {@code plan}: how many files, from which folders and to which. */
    public static String offer(ProjectConfig config, Refactor.Planned plan) {
        Set<String> from = new LinkedHashSet<>();
        Set<String> to = new LinkedHashSet<>();
        plan.moves().forEach((was, now) -> {
            from.add(relative(config, was.getParent()) + "/");
            to.add(relative(config, now.getParent()) + "/");
        });
        int count = plan.moves().size();
        long copies = plan.moves().values().stream().filter(Files::exists).count();
        String where = (count == 1 ? "A plugin file is" : count + " plugin files are") + " in " + String.join(", ", from)
                + ", not where Studio writes them now (" + String.join(", ", to) + ").";
        return where + (copies == 0 ? " They work where they are."
                : " " + (copies == 1 ? "One is a committed copy" : copies + " are committed copies")
                + " of a parameter group the bot keeps there, privately; Move removes "
                + (copies == 1 ? "it" : "them") + ".");
    }

    /** The holders to move, each outside its plugin's folder. */
    static List<Moved> moves(BotIndex index, ProjectConfig config, List<PluginFiles.Holder> holders) {
        List<Moved> out = new ArrayList<>();
        index.read(units -> {
            units.forEach((path, unit) -> PluginFiles.owner(path, index.sources().get(path), holders).ifPresent(holder -> {
                // A holder in the default package stays: no import can name it from the folder it would go to.
                if (inPlace(holder, path) || unit.getPackage() == null) return;
                String pkg = unit.getPackage().getName().getFullyQualifiedName();
                // A parameter group goes to params/, private; a holder of values to plugins/.
                boolean group = holder.parameterGroup();
                String newPkg = (group ? config.parametersPackage() : config.mainPackage() + ".plugins") + "."
                        + holder.segment();
                Path to = (group ? config.parametersPackageDir() : config.mainPackageDir().resolve("plugins"))
                        .resolve(holder.segment().replace('.', '/')).resolve(holder.className() + ".java")
                        .toAbsolutePath().normalize();
                out.add(new Moved(path, to, qualified(pkg, holder.className()), newPkg + "." + holder.className()));
            }));
            return null;
        });
        out.sort(Comparator.comparing(Moved::oldName));
        return List.copyOf(out);
    }

    /** Every file's text once the holders are moved, by its current path; unchanged files left out. */
    private static Map<Path, String> rewrites(BotIndex index, List<Moved> moved) {
        Map<String, Moved> byOldName = new LinkedHashMap<>();
        moved.forEach(each -> byOldName.put(each.oldName(), each));
        Map<Path, Moved> byFile = new LinkedHashMap<>();
        moved.forEach(each -> byFile.put(each.from(), each));
        Map<Path, String> out = new LinkedHashMap<>();
        index.read(units -> {
            Set<String> stayingPackages = new LinkedHashSet<>();
            units.forEach((path, unit) -> {
                if (!byFile.containsKey(path) && unit.getPackage() != null) {
                    stayingPackages.add(unit.getPackage().getName().getFullyQualifiedName());
                }
            });
            units.forEach((path, unit) -> {
                String text = index.sources().get(path);
                if (text == null) return;
                String rewritten = rewritten(text, unit, byFile.get(path), byOldName, stayingPackages);
                if (!rewritten.equals(text)) out.put(path, rewritten);
            });
            return null;
        });
        return out;
    }

    /**
     * {@code text} with its package moved when it is a holder ({@code self}), and every name of a moved class
     * following the class.
     */
    static String rewritten(String text, CompilationUnit unit, Moved self, Map<String, Moved> byOldName,
                            Set<String> stayingPackages) {
        AST ast = unit.getAST();
        ASTRewrite rewrite = ASTRewrite.create(ast);
        PackageDeclaration declaration = unit.getPackage();
        String oldPkg = declaration == null ? "" : declaration.getName().getFullyQualifiedName();
        String newPkg = self == null ? oldPkg : self.newPackage();
        if (self != null && declaration != null) rewrite.replace(declaration.getName(), ast.newName(newPkg), null);

        Set<String> imported = new LinkedHashSet<>();
        Set<String> added = new LinkedHashSet<>();
        for (Object each : unit.imports()) {
            ImportDeclaration imp = (ImportDeclaration) each;
            String name = imp.getName().getFullyQualifiedName();
            imported.add(name);
            if (imp.isOnDemand() && !imp.isStatic()) {
                Set<String> to = new LinkedHashSet<>();
                byOldName.values().stream().filter(m -> m.oldPackage().equals(name)).forEach(m -> to.add(m.newPackage()));
                if (to.isEmpty()) continue;
                // The old package's own import goes once nothing is left in it; each new package gets one.
                List<String> news = new ArrayList<>(to);
                if (!stayingPackages.contains(name)) rewrite.replace(imp.getName(), ast.newName(news.removeFirst()), null);
                news.forEach(pkg -> added.add(pkg + ".*"));
                continue;
            }
            renamed(rewrite, imp.getName(), byOldName);
        }

        // Qualified names in the code, and a moved class used by its simple name from what was its package.
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(ImportDeclaration node) {
                return false;
            }

            @Override
            public boolean visit(PackageDeclaration node) {
                return false;
            }

            @Override
            public boolean visit(QualifiedName node) {
                return !renamed(rewrite, node, byOldName);
            }

            @Override
            public boolean visit(SimpleName node) {
                if (node.getParent() instanceof QualifiedName q && q.getName() == node) return false;
                IBinding binding = node.resolveBinding();
                if (!(binding instanceof ITypeBinding type) || !type.isTopLevel() || node.isDeclaration()) return false;
                String name = type.getErasure().getQualifiedName();
                String typePkg = packageOf(name);
                if (!typePkg.equals(oldPkg)) return false;
                Moved other = byOldName.get(name);
                String nowIn = other == null ? typePkg : other.newPackage();
                String nowName = other == null ? name : other.newName();
                if (!nowIn.equals(newPkg) && !imported.contains(nowName)) added.add(nowName);
                return false;
            }
        });

        ListRewrite imports = rewrite.getListRewrite(unit, CompilationUnit.IMPORTS_PROPERTY);
        for (String name : added) {
            ImportDeclaration each = ast.newImportDeclaration();
            boolean onDemand = name.endsWith(".*");
            each.setName(ast.newName(onDemand ? name.substring(0, name.length() - 2) : name));
            each.setOnDemand(onDemand);
            imports.insertLast(each, null);
        }
        return AstRewriteHelper.applyRewrite(rewrite, text);
    }

    /**
     * Gives {@code name} its moved class's new name, when it is, or starts with, a moved class's old one; whether it
     * did.
     */
    private static boolean renamed(ASTRewrite rewrite, Name name, Map<String, Moved> byOldName) {
        for (Name part = name; part != null; part = part instanceof QualifiedName q ? q.getQualifier() : null) {
            Moved moved = byOldName.get(part.getFullyQualifiedName());
            if (moved != null) {
                rewrite.replace(part, name.getAST().newName(moved.newName()), null);
                return true;
            }
        }
        return false;
    }

    private static String packageOf(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? "" : qualified.substring(0, dot);
    }

    private static String qualified(String pkg, String name) {
        return pkg.isEmpty() ? name : pkg + "." + name;
    }

    private static String relative(ProjectConfig config, Path path) {
        Path root = config.mainPackageDir().toAbsolutePath().normalize();
        Path at = path.toAbsolutePath().normalize();
        return (at.startsWith(root) ? root.relativize(at) : at).toString().replace('\\', '/');
    }
}
