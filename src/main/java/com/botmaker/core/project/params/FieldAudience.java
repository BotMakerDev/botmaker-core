// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.project.params;

import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.services.BotSources;
import com.botmaker.java.grammar.BotAnnotation;
import com.botmaker.java.parse.BotParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.HashSet;
import java.util.Set;

/**
 * The {@code @Param} fields a bot's users saw, by the {@code visibility} the contract declared until 0.20
 * (2026-11-22). Read before {@link JavaParameters#stripRemovedMembers} takes that member off, since the move of the
 * fields ({@link FieldParameters}) tells a parameter from its author's constant by it.
 *
 * @param shown the fields whose source names them public, each {@code Class.field}
 */
public record FieldAudience(Set<String> shown) {

    public FieldAudience {
        shown = Set.copyOf(shown);
    }

    /** What the bot's sources say now. */
    public static FieldAudience of(ProjectConfig config, ProjectState state) {
        Set<String> shown = new HashSet<>();
        BotSources.forEach(config, state, (file, source) -> {
            if (source.contains("visibility")) shown.addAll(read(source).shown());
            return null;
        });
        return new FieldAudience(shown);
    }

    /** What one file says. */
    static FieldAudience read(String source) {
        Set<String> shown = new HashSet<>();
        BotParser.syntax(source).accept(new ASTVisitor() {
            @Override
            public boolean visit(FieldDeclaration field) {
                if (namesPublic(field)) {
                    String holder = JavaParameterSource.enclosingTypeName(field);
                    for (Object fragment : field.fragments()) {
                        shown.add(holder + "." + ((VariableDeclarationFragment) fragment).getName().getIdentifier());
                    }
                }
                return true;
            }
        });
        return new FieldAudience(shown);
    }

    /** Whether the bot's users saw {@code field}. */
    public boolean shows(JavaParameter field) {
        return shown.contains(field.qualified());
    }

    /** {@code visibility = "public"}, or the constant {@code Param.PUBLIC} as written: the class no longer has it. */
    private static boolean namesPublic(FieldDeclaration field) {
        if (!(BotAnnotation.PARAM.on(field) instanceof NormalAnnotation annotation)) return false;
        for (Object each : annotation.values()) {
            MemberValuePair pair = (MemberValuePair) each;
            if (!pair.getName().getIdentifier().equals("visibility")) continue;
            return switch (pair.getValue()) {
                case StringLiteral literal -> literal.getLiteralValue().equalsIgnoreCase("public");
                case QualifiedName name -> name.getName().getIdentifier().equals("PUBLIC");
                case SimpleName name -> name.getIdentifier().equals("PUBLIC");
                default -> false;
            };
        }
        return false;
    }
}
