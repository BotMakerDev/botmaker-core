package com.botmaker.core.assist;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;

/** Interfaces that answer nothing, for tests that list the MCP's tools; public for Studio's tests (the tests jar). */
public final class Stubs {

    private Stubs() {
    }

    /**
     * {@code type}, answering nothing: listing the tools calls none of their handlers. A list is empty, an optional
     * empty, a default method its own body.
     */
    public static <T> T of(Class<T> type) {
        InvocationHandler none = (proxy, method, args) -> {
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            Class<?> returns = method.getReturnType();
            if (returns == List.class) return List.of();
            if (returns == Optional.class) return Optional.empty();
            if (returns == boolean.class) return false;
            if (returns == int.class) return 0;
            if (returns == long.class) return 0L;
            if (returns == String.class) return "";
            return null;
        };
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, none));
    }
}
