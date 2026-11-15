package com.botmaker.core.run.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PooledMethodsTest {

    private static final String OWNER = PooledFixture.class.getName();

    private static RunPlan.Pooled pooled(String method, String descriptor, String value) {
        return new RunPlan.Pooled(OWNER, method, descriptor, value, "", List.of());
    }

    private static final RunPlan.Pooled ACCOUNT = pooled("account", "()Ljava/lang/String;", "\"dealt\"");
    private static final RunPlan.Pooled LEVEL = pooled("level", "()I", "7");
    private static final RunPlan.Pooled GOLD = pooled("gold", "()J", "9L");

    @AfterEach
    void forget() {
        PooledValues.install(Map.of());
    }

    @Test
    void eachListedMethodReturnsItsDealtValueAndTheRestKeepTheirBodies() throws Exception {
        List<RunPlan.Pooled> listed = List.of(ACCOUNT, LEVEL, GOLD);
        byte[] rewritten = new PooledMethods(listed).rewrite(bytes(OWNER), getClass().getClassLoader());
        Map<String, Object> values = new HashMap<>();
        values.put(ACCOUNT.key(), "dealt");
        values.put(LEVEL.key(), 7);
        values.put(GOLD.key(), 9L);
        PooledValues.install(values);

        Class<?> type = new OneClass(OWNER, rewritten).loadClass(OWNER);
        Object instance = type.getConstructor().newInstance();
        assertEquals("dealt", type.getMethod("account").invoke(instance));
        assertEquals(7, type.getMethod("level").invoke(null));
        assertEquals(9L, type.getMethod("gold").invoke(null));
        assertEquals("kept", type.getMethod("untouched").invoke(instance));
    }

    @Test
    void aDealtNullIsReturnedAsNull() throws Exception {
        byte[] rewritten = new PooledMethods(List.of(ACCOUNT)).rewrite(bytes(OWNER), getClass().getClassLoader());
        Map<String, Object> values = new HashMap<>();
        values.put(ACCOUNT.key(), null);
        PooledValues.install(values);

        Class<?> type = new OneClass(OWNER, rewritten).loadClass(OWNER);
        assertNull(type.getMethod("account").invoke(type.getConstructor().newInstance()));
    }

    @Test
    void aClassWithNothingListedIsLeftAlone() throws IOException {
        String other = PooledFixture.NoBody.class.getName();
        assertNull(new PooledMethods(List.of(ACCOUNT)).rewrite(bytes(other), getClass().getClassLoader()));
    }

    @Test
    void aMethodThatIsGoneChangedOrBodilessIsRefusedBeforeTheStart() {
        assertEquals(Optional.empty(), PooledMethods.unfit(List.of(ACCOUNT, LEVEL), PooledMethodsTest::read));

        Optional<String> gone = PooledMethods.unfit(List.of(pooled("missing", "()I", "1")), PooledMethodsTest::read);
        assertTrue(gone.orElseThrow().contains("PooledFixture.missing() is not in the compiled bot"), gone.get());

        Optional<String> changed = PooledMethods.unfit(List.of(pooled("level", "()J", "1")), PooledMethodsTest::read);
        assertTrue(changed.orElseThrow().contains("has changed its type"), changed.get());

        RunPlan.Pooled abstractOne = new RunPlan.Pooled(PooledFixture.NoBody.class.getName(), "name",
                "()Ljava/lang/String;", "\"x\"", "", List.of());
        Optional<String> bodiless = PooledMethods.unfit(List.of(abstractOne), PooledMethodsTest::read);
        assertTrue(bodiless.orElseThrow().contains("has no body to replace"), bodiless.get());

        Optional<String> noClass = PooledMethods.unfit(List.of(ACCOUNT), name -> Optional.empty());
        assertTrue(noClass.orElseThrow().contains("its class is gone"), noClass.get());
    }

    private static Optional<byte[]> read(String binaryName) {
        try {
            return Optional.of(bytes(binaryName));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static byte[] bytes(String binaryName) throws IOException {
        try (InputStream in = PooledMethodsTest.class.getClassLoader()
                .getResourceAsStream(binaryName.replace('.', '/') + ".class")) {
            if (in == null) throw new IOException("no class " + binaryName);
            return in.readAllBytes();
        }
    }

    /** Defines one class from the given bytes, everything else from the test's own loader. */
    private static final class OneClass extends ClassLoader {
        private final String name;
        private final byte[] bytes;

        OneClass(String name, byte[] bytes) {
            super(PooledMethodsTest.class.getClassLoader());
            this.name = name;
            this.bytes = bytes;
        }

        @Override
        protected Class<?> loadClass(String wanted, boolean resolve) throws ClassNotFoundException {
            if (!wanted.equals(name)) return super.loadClass(wanted, resolve);
            synchronized (getClassLoadingLock(wanted)) {
                Class<?> found = findLoadedClass(wanted);
                return found != null ? found : defineClass(wanted, bytes, 0, bytes.length);
            }
        }
    }
}
