package com.botmaker.core.run.claims;

import com.botmaker.core.project.params.JavaParameter;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Where a plugin's pool lands in the bot (spec {@code 2026-11-15-run-claims-design.md} §2): the method of a
 * {@code @Param} group that carries the plugin's mark for the pooled parameter, with its descriptor as compiled.
 */
public final class BoundMethods {

    private BoundMethods() {}

    /**
     * By a plugin parameter's id, the bot's method that holds it; empty for one the bot has no method for, or whose
     * class is not in {@code classes}.
     *
     * @param parameters the bot's parameters that apply to it now: a conditional one whose condition does not hold
     *                   is left out by the caller, so its pool is not dealt
     * @param sources    the bot's source root, which a group's package is read from
     * @param classes    the compile the runs start from
     */
    public static Function<String, Optional<PoolSources.Target>> of(List<JavaParameter> parameters, Path sources,
                                                                    Path classes) {
        return id -> parameters.stream()
                .filter(parameter -> parameter.group() && parameter.managedId().equals(id))
                .findFirst()
                .flatMap(parameter -> target(parameter, sources, classes));
    }

    private static Optional<PoolSources.Target> target(JavaParameter parameter, Path sources, Path classes) {
        Path folder = parameter.file().toAbsolutePath().normalize().getParent();
        Path root = sources.toAbsolutePath().normalize();
        if (folder == null || !folder.startsWith(root)) return Optional.empty();
        String pack = root.relativize(folder).toString().replace('\\', '/').replace('/', '.');
        String owner = pack.isEmpty() ? parameter.className() : pack + "." + parameter.className();
        Path file = classes.resolve(owner.replace('.', '/') + ".class");
        ClassModel model;
        try {
            model = ClassFile.of().parse(Files.readAllBytes(file));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
        for (MethodModel method : model.methods()) {
            String descriptor = method.methodType().stringValue();
            if (method.methodName().equalsString(parameter.name()) && descriptor.startsWith("()")) {
                return Optional.of(new PoolSources.Target(owner, parameter.name(), descriptor, ""));
            }
        }
        return Optional.empty();
    }
}
