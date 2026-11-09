package com.botmaker.core.project;

import com.botmaker.core.project.managed.ManagedHolders;
import com.botmaker.java.parse.JavaLevel;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one {@code .java} a <b>blank</b> project starts with, composed by Studio.
 *
 * <h2>Why there is only one, and why it is this small</h2>
 *
 * <p>The SDK wrote a project's Java until 2026-08-29 — an entry point, {@code GoHome}, {@code Popups}, and
 * one class per activity — and then kept editing them. That made files inside a user's own source tree ones
 * they could not freely rename, move or delete, so all of it went and Studio composed the starting file
 * instead: a blank one, and a game bot.
 *
 * <p>The game bot went too, on 2026-08-30. It was not Studio's to write: <b>it is a bot project that calls
 * the SDK's static API</b>, which is exactly what the gallery already publishes, browses and installs. So a
 * richer starting point is now any published bot, which New Project clones linked to its author (2026-10-23;
 * only bots tagged {@code template}, copied and renamed, until then) — and anybody can publish one without a
 * Studio release.
 *
 * <p>What is left here is the starting point that must work with <b>no network and no gallery</b>, which is
 * the reason Studio composes any at all. It is deliberately the smallest honest thing: a class, a
 * {@code main}, and a line of output. It prints with {@code System.out.println} and imports nothing —
 * teaching {@code BotMaker.print} for what the JDK already does would be spending a user's first impression
 * on a BotMaker spelling of a Java call.
 *
 * <p><b>And since 2026-09-04 "imports nothing" is a property of the pom as well as of this file.</b> A blank
 * project's {@code pom.xml} names no plugin — see {@code MavenService.BLANK_DEPENDENCIES} — so there is no
 * BotMaker API on its classpath to import even if this file wanted to. That is the platform rule reaching
 * project creation: the SDK is one plugin among any number, and choosing it is the user's to make, one step
 * away in <b>Project ▸ Plugins &amp; Libraries</b>.
 *
 * <p><b>Nothing here is a template in the old sense.</b> There are no holes, no fences and no manifest;
 * nothing parses this text back, nothing reconciles it, and no repair puts it back if it is deleted. It is
 * the text a project happens to begin with, and every line of it is the user's from the moment it lands.
 */
public final class StarterSources {

    private StarterSources() {}

    /**
     * The project's starting files, keyed by path relative to the project root — what
     * {@code Authoring.createProject} is handed as {@code callerFiles} beside the pom.
     *
     * <p>Only {@link ProjectTemplate#EMPTY} reaches here. A project made from a published bot gets its
     * files by cloning that bot, and a caller asking for one of those by name is a bug rather than a
     * case to handle.
     *
     * @param cfg the project being created, for its package and entry class name
     */
    public static Map<String, String> of(ProjectConfig cfg) {
        String pkg = "com." + cfg.packageName();
        String name = cfg.className();
        String path = "src/main/java/" + pkg.replace('.', '/') + "/" + name + ".java";
        return Map.of(path, blank(pkg, name));
    }

    /**
     * A bare {@code main} that prints a line. Everything after it is the user's to invent.
     *
     * <p><b>The comment names no BotMaker type, and since 2026-09-04 it could not.</b> It used to tell the
     * reader about {@code ImageFinder}, {@code Mouse}, {@code Wait}, {@code Bot.start} and
     * {@code FlowGraph.run} — none of which resolve here, because a blank project's pom names no plugin.
     * Documentation that points at classes the project cannot see is worse than none: it reads as the
     * install being broken. What it says instead is the one thing that is both true and actionable, which
     * is where the API comes from.
     */
    @SuppressWarnings("unchecked")
    private static String blank(String pkg, String name) {
        AST ast = JavaLevel.LATEST.newAst();
        TypeDeclaration type = ast.newTypeDeclaration();
        type.setJavadoc(ManagedHolders.javadoc(ast, DOC));
        type.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        type.setName(ast.newSimpleName(name));

        MethodInvocation println = ast.newMethodInvocation();
        println.setExpression(ast.newName("System.out"));
        println.setName(ast.newSimpleName("println"));
        StringLiteral hello = ast.newStringLiteral();
        hello.setLiteralValue("Hello from " + name + "!");
        println.arguments().add(hello);
        Block body = ast.newBlock();
        body.statements().add(ast.newExpressionStatement(println));

        SingleVariableDeclaration args = ast.newSingleVariableDeclaration();
        args.setType(ast.newArrayType(ast.newSimpleType(ast.newSimpleName("String"))));
        args.setName(ast.newSimpleName("args"));
        MethodDeclaration main = ast.newMethodDeclaration();
        main.modifiers().addAll(ast.newModifiers(Modifier.PUBLIC | Modifier.STATIC));
        main.setReturnType2(ast.newPrimitiveType(PrimitiveType.VOID));
        main.setName(ast.newSimpleName("main"));
        main.parameters().add(args);
        main.setBody(body);
        type.bodyDeclarations().add(main);
        return ManagedHolders.unit(ast, pkg, Set.of(), type);
    }

    /** The class's doc comment, a line each, an empty one between paragraphs. */
    private static final List<String> DOC = List.of(
            "Your project.",
            "",
            "<p>BotMaker wrote this file once, when the project was created, and will never touch it",
            "again. Every line of it is yours — rename it, split it up, throw it away.",
            "",
            "<p>This is a plain Java project: it has a pom, a source folder and this main(). To make it",
            "a bot, add the BotMaker SDK from <b>Project ▸ Plugins & Libraries</b> — it is a plugin like any",
            "other, and installing it brings the palette, the pictures, the capture tools and the rest.",
            "Or start from a published bot instead, which arrives with all of that already pinned.");
}
