package com.botmaker.core.project;

import com.botmaker.java.grammar.SourceParser;
import com.botmaker.java.parse.JavaLevel;
import com.botmaker.plugin.api.params.Param;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a class of the bot is a group only a run writes (2026-11-09): it carries an annotation that a plugin marked
 * {@link Param.WrittenByRun}, as the SDK's {@code @Facts} is. Such a class is locked on the canvas whole, and so is
 * the file that declares it.
 *
 * <p>A binding first, as {@code ManagedIds} reads a marker: a unit parsed against the classpath says what the
 * annotation's class carries. Without one, or when the bot's classpath holds a copy of the annotation without the
 * mark, the annotation's name is resolved through the unit's imports and its package (never a suffix match), and the
 * class is loaded, not initialised, from the plugins' loader to read its marks.
 */
public final class RunWritten {

    /** What the canvas says of a locked group. */
    public static final String REASON = "Facts are written by the run. The Parameters window shows them.";

    /** The status-line badge of a file that declares one. */
    public static final String BADGE = "Facts - Read Only";

    private static final String META = Param.WrittenByRun.class.getName();

    // Per loader, since an unbound plugin set's classes must not answer for the next one.
    private static final Map<ClassLoader, Map<String, Boolean>> LOADED = Collections.synchronizedMap(new WeakHashMap<>());

    // A file's verdict until it changes on disk or the plugins are loaded again.
    private record Seen(long modified, long size, int loader, boolean marked) {}

    private static final Map<Path, Seen> FILES = new ConcurrentHashMap<>();

    private RunWritten() {}

    /**
     * Whether {@code type} carries a run-written group's annotation; {@code plugins} is where an annotation is loaded
     * from when the unit has no bindings, {@code null} to trust bindings alone.
     */
    public static boolean marks(AbstractTypeDeclaration type, ClassLoader plugins) {
        if (type == null) return false;
        for (Object modifier : type.modifiers()) {
            if (modifier instanceof Annotation annotation && marks(annotation, plugins)) return true;
        }
        return false;
    }

    /**
     * Whether the Java file at {@code file} declares, at its top level, a group only a run writes: parsed without
     * bindings and read against {@code plugins}, then kept until the file or the loader changes. A file that cannot
     * be read, or no loader, is not one.
     */
    public static boolean declaredIn(Path file, ClassLoader plugins) {
        if (file == null || plugins == null || !file.toString().endsWith(".java")) return false;
        Path key = file.toAbsolutePath().normalize();
        long modified;
        long size;
        try {
            modified = Files.getLastModifiedTime(key).toMillis();
            size = Files.size(key);
        } catch (IOException e) {
            return false;
        }
        int loader = System.identityHashCode(plugins);
        Seen seen = FILES.get(key);
        if (seen != null && seen.modified() == modified && seen.size() == size && seen.loader() == loader) {
            return seen.marked();
        }
        boolean marked = false;
        try {
            CompilationUnit unit = SourceParser.parse(JavaLevel.LATEST, Files.readString(key));
            if (unit != null) {
                for (Object type : unit.types()) {
                    if (marks((AbstractTypeDeclaration) type, plugins)) marked = true;
                }
            }
        } catch (IOException | UncheckedIOException e) {
            marked = false;
        }
        FILES.put(key, new Seen(modified, size, loader, marked));
        return marked;
    }

    private static boolean marks(Annotation annotation, ClassLoader plugins) {
        IAnnotationBinding binding = annotation.resolveAnnotationBinding();
        ITypeBinding type = binding == null ? null : binding.getAnnotationType();
        if (type != null && !type.isRecovered()) {
            for (IAnnotationBinding meta : type.getAnnotations()) {
                ITypeBinding metaType = meta.getAnnotationType();
                if (metaType != null && META.equals(metaType.getBinaryName())) return true;
            }
            // The bot's classpath may hold an older copy than the plugin loaded: the loaded one has the last word.
            return plugins != null && type.getBinaryName() != null && loadedMarks(type.getBinaryName(), plugins);
        }
        if (plugins == null) return false;
        for (String candidate : candidates(annotation)) {
            if (loadedMarks(candidate, plugins)) return true;
        }
        return false;
    }

    /**
     * The source names {@code annotation}'s written name may stand for in its unit: a single-type import of its first
     * segment alone, as Java lets it shadow the rest; else the name as written when qualified, each on-demand import
     * and the unit's package.
     */
    static List<String> candidates(Annotation annotation) {
        String written = annotation.getTypeName().getFullyQualifiedName();
        int dot = written.indexOf('.');
        String first = dot < 0 ? written : written.substring(0, dot);
        String rest = dot < 0 ? "" : written.substring(dot);
        List<String> out = new ArrayList<>();
        if (!(annotation.getRoot() instanceof CompilationUnit unit)) {
            if (dot >= 0) out.add(written);
            return out;
        }
        for (Object each : unit.imports()) {
            ImportDeclaration declaration = (ImportDeclaration) each;
            if (declaration.isStatic() || declaration.isOnDemand()) continue;
            String name = declaration.getName().getFullyQualifiedName();
            if (name.endsWith("." + first)) return List.of(name + rest);
        }
        if (dot >= 0) out.add(written);
        for (Object each : unit.imports()) {
            ImportDeclaration declaration = (ImportDeclaration) each;
            if (declaration.isStatic() || !declaration.isOnDemand()) continue;
            out.add(declaration.getName().getFullyQualifiedName() + "." + written);
        }
        if (unit.getPackage() != null) out.add(unit.getPackage().getName().getFullyQualifiedName() + "." + written);
        return out;
    }

    private static boolean loadedMarks(String sourceName, ClassLoader plugins) {
        return LOADED.computeIfAbsent(plugins, loader -> new ConcurrentHashMap<>())
                .computeIfAbsent(sourceName, name -> {
                    Class<?> found = load(name, plugins);
                    if (found == null || !found.isAnnotation()) return false;
                    for (java.lang.annotation.Annotation meta : found.getAnnotations()) {
                        if (META.equals(meta.annotationType().getName())) return true;
                    }
                    return false;
                });
    }

    /** {@code name} as a top-level class, else as a nested one, its last dots turned to {@code $} one by one. */
    private static Class<?> load(String name, ClassLoader plugins) {
        String binary = name;
        while (true) {
            try {
                return Class.forName(binary, false, plugins);
            } catch (ClassNotFoundException | LinkageError e) {
                int dot = binary.lastIndexOf('.');
                if (dot < 0) return null;
                binary = binary.substring(0, dot) + '$' + binary.substring(dot + 1);
            }
        }
    }
}
