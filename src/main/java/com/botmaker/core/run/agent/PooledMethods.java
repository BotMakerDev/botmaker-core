package com.botmaker.core.run.agent;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.AccessFlag;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Rewrites the methods a run was dealt values for as their classes load (spec {@code 2026-11-15-run-claims-design.md}
 * §5): each listed method's body becomes a return of {@link PooledValues#value}, the value built once in this JVM.
 * Only the listed methods change; the rest of the class, its annotations and the method's own, stay as compiled.
 *
 * <p>Unlike the call trace, a rewrite that fails is not left out: a run on the body's value would hold one element
 * and use another. {@link #unfit} checks every listed method before the bot starts, and a class that still cannot be
 * rewritten as it loads stops the run.
 */
public final class PooledMethods implements ClassFileTransformer {

    private static final ClassDesc VALUES = ClassDesc.of(PooledValues.class.getName());
    private static final MethodTypeDesc VALUE = MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_String);

    /** By the owner's internal name, then the method's name and descriptor. */
    private final Map<String, Map<String, RunPlan.Pooled>> listed = new HashMap<>();

    /** Whether the agent has done its part, after which a class that cannot be rewritten ends the run as it loads. */
    private volatile boolean started;
    /** The first class the agent loaded that could not be rewritten; empty when none. */
    private volatile String failed = "";

    public PooledMethods(List<RunPlan.Pooled> pooled) {
        for (RunPlan.Pooled one : pooled) {
            listed.computeIfAbsent(internal(one.owner()), any -> new HashMap<>()).put(one.method() + one.descriptor(),
                    one);
        }
    }

    /**
     * Why a listed method cannot be rewritten in the classes {@code bytes} reads (by binary name), or empty when every
     * one can: what the core checks against the compile before a start, and the agent again before the bot's main.
     */
    public static Optional<String> unfit(List<RunPlan.Pooled> pooled, Function<String, Optional<byte[]>> bytes) {
        for (RunPlan.Pooled one : pooled) {
            Optional<byte[]> read = bytes.apply(one.owner());
            if (read.isEmpty()) return Optional.of(one.said() + " is not in the compiled bot: its class is gone");
            ClassModel model;
            try {
                model = ClassFile.of().parse(read.get());
            } catch (IllegalArgumentException e) {
                return Optional.of(one.said() + " could not be read from the compiled bot: " + e.getMessage());
            }
            MethodModel found = null;
            boolean named = false;
            for (MethodModel method : model.methods()) {
                if (!method.methodName().equalsString(one.method())) continue;
                named = true;
                if (method.methodType().equalsString(one.descriptor())) found = method;
            }
            if (found == null) {
                return Optional.of(one.said() + (named ? " has changed its type since the pools were read"
                        : " is not in the compiled bot") + ": compile again before starting several runs");
            }
            if (found.code().isEmpty()) return Optional.of(one.said() + " has no body to replace");
            if (MethodTypeDesc.ofDescriptor(one.descriptor()).returnType().equals(ConstantDescs.CD_void)) {
                return Optional.of(one.said() + " returns nothing, so it holds no value");
            }
        }
        return Optional.empty();
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain domain,
                            byte[] bytes) {
        if (redefined != null || className == null || !listed.containsKey(className)) return null;
        try {
            return rewrite(bytes, loader);
        } catch (RuntimeException | LinkageError e) {
            String why = className.replace('/', '.') + " could not take the values it was dealt: " + e;
            if (!started) {
                // Loaded by the agent before the bot's main (rebuilding a pool): it stops the run when it returns.
                if (failed.isEmpty()) failed = why;
                return null;
            }
            // Thrown from here the JVM would load the class unchanged, and the run would use the body's value.
            System.err.println("[BotMaker] this run stopped: " + why);
            System.err.flush();
            Runtime.getRuntime().halt(RunAgent.STOPPED);
            return null;
        }
    }

    /**
     * Why a class the agent loaded before the bot's {@code main} could not be rewritten; empty when none. Asked once,
     * as the agent's last step: from then on a class that cannot be rewritten ends the run as it loads.
     */
    Optional<String> failed() {
        started = true;
        return failed.isEmpty() ? Optional.empty() : Optional.of(failed);
    }

    /** {@code bytes} with each listed method of its class returning its dealt value; {@code null} when none is listed. */
    byte[] rewrite(byte[] bytes, ClassLoader loader) {
        ClassLoader resolving = loader == null ? ClassLoader.getSystemClassLoader() : loader;
        ClassFile files = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                ClassHierarchyResolver.ofResourceParsing(resolving).orElse(ClassHierarchyResolver.defaultResolver())));
        ClassModel model = files.parse(bytes);
        Map<String, RunPlan.Pooled> methods = listed.get(model.thisClass().asInternalName());
        if (methods == null) return null;
        ClassTransform replaced = (builder, element) -> {
            RunPlan.Pooled one = element instanceof MethodModel method
                    && !method.flags().has(AccessFlag.ABSTRACT) && !method.flags().has(AccessFlag.NATIVE)
                    ? methods.get(method.methodName().stringValue() + method.methodType().stringValue()) : null;
            if (one == null) {
                builder.with(element);
                return;
            }
            MethodModel method = (MethodModel) element;
            builder.withMethod(method.methodName(), method.methodType(), method.flags().flagsMask(), body -> {
                for (MethodElement part : method) {
                    if (!(part instanceof CodeModel)) body.with(part);
                }
                body.withCode(code -> returnDealt(code, one));
            });
        };
        return files.transformClass(model, replaced);
    }

    /** {@code return (T) PooledValues.value(key);}, unboxed for a primitive. */
    private static void returnDealt(CodeBuilder code, RunPlan.Pooled one) {
        ClassDesc returned = MethodTypeDesc.ofDescriptor(one.descriptor()).returnType();
        code.ldc(one.key());
        code.invokestatic(VALUES, "value", VALUE);
        if (returned.isPrimitive()) {
            TypeKind kind = TypeKind.from(returned);
            ClassDesc box = box(kind);
            code.checkcast(box);
            code.invokevirtual(box, primitiveName(kind) + "Value", MethodTypeDesc.of(returned));
            code.return_(kind);
        } else {
            code.checkcast(returned);
            code.areturn();
        }
    }

    private static ClassDesc box(TypeKind kind) {
        return switch (kind) {
            case BOOLEAN -> ConstantDescs.CD_Boolean;
            case BYTE -> ConstantDescs.CD_Byte;
            case CHAR -> ConstantDescs.CD_Character;
            case SHORT -> ConstantDescs.CD_Short;
            case INT -> ConstantDescs.CD_Integer;
            case LONG -> ConstantDescs.CD_Long;
            case FLOAT -> ConstantDescs.CD_Float;
            case DOUBLE -> ConstantDescs.CD_Double;
            default -> throw new IllegalArgumentException("Not a primitive: " + kind);
        };
    }

    private static String primitiveName(TypeKind kind) {
        return switch (kind) {
            case BOOLEAN -> "boolean";
            case BYTE -> "byte";
            case CHAR -> "char";
            case SHORT -> "short";
            case INT -> "int";
            case LONG -> "long";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            default -> throw new IllegalArgumentException("Not a primitive: " + kind);
        };
    }

    private static String internal(String binaryName) {
        return binaryName.replace('.', '/');
    }
}
