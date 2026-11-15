package com.botmaker.core.run.agent;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Ends a run the agent stopped before its {@code main} ({@link RunAgent#STOPPED}) as the bot's main class initialises:
 * its static initialiser is replaced by a call to {@link #now}, so none of the bot's code runs.
 *
 * <p>Not from {@code premain} itself: on JDK 26 on Windows an exit made while the launcher still loads its agents or
 * the main class waits sixty seconds in {@code Shutdown.halt0}, holding the run's row all the while; made from the main
 * class's initialiser it is immediate. Where the main class cannot be named (a {@code -jar} launch), is loaded already,
 * or cannot be rewritten, the JVM ends at once and slowly.
 */
public final class MainStop implements ClassFileTransformer {

    private static final MethodTypeDesc VOID = MethodTypeDesc.of(ConstantDescs.CD_void);

    private static volatile String why = "";

    private final String main;

    private MainStop(String main) {
        this.main = main;
    }

    /** Says {@code reason} and ends the JVM before the bot's {@code main} runs: as its class initialises, or now. */
    static void install(Instrumentation instrumentation, String reason) {
        why = reason;
        String command = System.getProperty("sun.java.command", "").strip();
        String first = command.isEmpty() ? "" : command.split("\\s+", 2)[0];
        if (first.isEmpty() || first.endsWith(".jar") || first.endsWith(".java") || first.contains("/")
                || first.contains("\\")) {
            now();
            return;
        }
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            // Loaded already (a pool rebuilt named it): its initialiser is past rewriting.
            if (loaded.getName().equals(first)) {
                now();
                return;
            }
        }
        instrumentation.addTransformer(new MainStop(first.replace('.', '/')));
    }

    /** Called by the main class's replaced initialiser: says why, and ends the JVM. */
    public static void now() {
        System.err.println("[BotMaker] this run stopped before it started: " + why);
        System.err.flush();
        System.exit(RunAgent.STOPPED);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain domain,
                            byte[] bytes) {
        if (redefined != null || !main.equals(className)) return null;
        try {
            ClassFile files = ClassFile.of();
            ClassModel model = files.parse(bytes);
            ClassTransform dropped = (builder, element) -> {
                if (!(element instanceof MethodModel method && method.methodName().equalsString("<clinit>"))) {
                    builder.with(element);
                }
            };
            return files.transformClass(model, dropped.andThen(ClassTransform.endHandler(builder ->
                    builder.withMethodBody(ConstantDescs.CLASS_INIT_NAME, VOID, ClassFile.ACC_STATIC, code -> {
                        code.invokestatic(ClassDesc.of(MainStop.class.getName()), "now", VOID);
                        code.return_();
                    }))));
        } catch (RuntimeException e) {
            now();
            return null;
        }
    }
}
