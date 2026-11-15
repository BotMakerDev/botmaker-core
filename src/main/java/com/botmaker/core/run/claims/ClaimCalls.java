package com.botmaker.core.run.claims;

import com.botmaker.plugin.api.params.Claims;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.DirectMethodHandleDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Where the compiled bot claims a value while it runs ({@link Claims#claim}, spec {@code 2026-11-15-run-claims-design.md}
 * §6). The host can neither count nor deal such a key ahead, so a start of several runs says these are "decided while
 * running" rather than counting them into how many runs fit.
 */
public final class ClaimCalls {

    private static final String CLAIMS = Claims.class.getName().replace('.', '/');
    private static final String CLAIMS_DESCRIPTOR = "L" + CLAIMS + ";";
    /** An anonymous or local class's part of a binary name: {@code $1}, {@code $1Local}. */
    private static final Pattern NUMBERED = Pattern.compile("\\$\\d+\\w*");
    /** A lambda's body, {@code lambda$run$0}: named by the method it is written in. */
    private static final Pattern LAMBDA = Pattern.compile("lambda\\$(\\w+?)\\$\\d+");

    private ClaimCalls() {}

    /**
     * Each method of the compile in {@code classes} that calls {@code Claims.claim}, directly or as {@code Claims::claim},
     * as {@code Class.method} with the class's simple name, sorted; a lambda's or an anonymous class's call is named by
     * the method and class it is written in. Empty when none does or the folder is missing. A class file that cannot be
     * read or does not parse is passed over.
     */
    public static List<String> in(Path classes) {
        if (!Files.isDirectory(classes)) return List.of();
        TreeSet<String> sites = new TreeSet<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                try {
                    ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
                    for (MethodModel method : model.methods()) {
                        if (claims(method)) sites.add(className(model) + "." + methodName(method));
                    }
                } catch (IOException | IllegalArgumentException unreadable) {
                    // Parsed lazily: a broken method throws here too. The other classes still count.
                }
            }
        } catch (IOException | UncheckedIOException e) {
            throw new UncheckedIOException("Could not read the compiled bot in " + classes,
                    e instanceof UncheckedIOException unchecked ? unchecked.getCause() : (IOException) e);
        }
        return List.copyOf(sites);
    }

    private static boolean claims(MethodModel method) {
        return method.code().map(code -> code.elementStream().anyMatch(ClaimCalls::claim)).orElse(false);
    }

    private static boolean claim(CodeElement element) {
        if (element instanceof InvokeInstruction call) {
            return call.owner().asInternalName().equals(CLAIMS) && call.name().equalsString("claim");
        }
        if (element instanceof InvokeDynamicInstruction dynamic) {
            // Claims::claim: the handle is among the bootstrap's arguments.
            return dynamic.bootstrapArgs().stream().anyMatch(arg -> arg instanceof DirectMethodHandleDesc handle
                    && handle.owner().descriptorString().equals(CLAIMS_DESCRIPTOR)
                    && handle.methodName().equals("claim"));
        }
        return false;
    }

    private static String className(ClassModel model) {
        String name = model.thisClass().asInternalName();
        name = NUMBERED.matcher(name.substring(name.lastIndexOf('/') + 1)).replaceAll("");
        return name.replace('$', '.');
    }

    private static String methodName(MethodModel method) {
        String name = method.methodName().stringValue();
        var lambda = LAMBDA.matcher(name);
        return lambda.matches() ? lambda.group(1) : name;
    }
}
