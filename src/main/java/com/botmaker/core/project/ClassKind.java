package com.botmaker.core.project;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** What a new file of the bot declares: a class, interface, record or enum. Was Studio's {@code ClassFiles.Kind}. */
public enum ClassKind {
    CLASS("class", "Class", "public class %s {\n}\n"),
    INTERFACE("interface", "Interface", "public interface %s {\n}\n"),
    RECORD("record", "Record", "public record %s() {\n}\n"),
    ENUM("enum", "Enum", "public enum %s {\n}\n");

    private final String id;
    private final String displayName;
    private final String body;

    ClassKind(String id, String displayName, String body) {
        this.id = id;
        this.displayName = displayName;
        this.body = body;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** The source of a new file of this kind named {@code name} in {@code packageName}. */
    public String source(String packageName, String name) {
        return "package " + packageName + ";\n\n" + body.formatted(name);
    }

    /** The kind {@code id} names, ignoring case; empty for anything else. */
    public static Optional<ClassKind> fromId(String id) {
        String wanted = id == null ? "" : id.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(kind -> kind.id.equals(wanted)).findFirst();
    }
}
