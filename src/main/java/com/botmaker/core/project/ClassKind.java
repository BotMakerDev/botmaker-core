package com.botmaker.core.project;

import com.botmaker.core.project.managed.ManagedHolders;
import com.botmaker.java.parse.JavaLevel;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.IExtendedModifier;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** What a new file of the bot declares: a class, interface, record or enum. Was Studio's {@code ClassFiles.Kind}. */
public enum ClassKind {
    CLASS("class", "Class"),
    INTERFACE("interface", "Interface"),
    RECORD("record", "Record"),
    ENUM("enum", "Enum");

    private final String id;
    private final String displayName;

    ClassKind(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** The source of a new file of this kind named {@code name} in {@code packageName}, built as a tree. */
    public String source(String packageName, String name) {
        AST ast = JavaLevel.LATEST.newAst();
        AbstractTypeDeclaration type = switch (this) {
            case CLASS -> ast.newTypeDeclaration();
            case INTERFACE -> {
                TypeDeclaration declared = ast.newTypeDeclaration();
                declared.setInterface(true);
                yield declared;
            }
            case RECORD -> ast.newRecordDeclaration();
            case ENUM -> ast.newEnumDeclaration();
        };
        type.setName(ast.newSimpleName(name));
        @SuppressWarnings("unchecked")
        List<IExtendedModifier> modifiers = type.modifiers();
        modifiers.add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        return ManagedHolders.unit(ast, packageName, Set.of(), type);
    }

    /** The kind {@code id} names, ignoring case; empty for anything else. */
    public static Optional<ClassKind> fromId(String id) {
        String wanted = id == null ? "" : id.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(kind -> kind.id.equals(wanted)).findFirst();
    }
}
