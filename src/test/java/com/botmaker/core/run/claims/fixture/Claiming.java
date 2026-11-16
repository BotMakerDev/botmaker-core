// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.run.claims.fixture;

import com.botmaker.plugin.api.params.Claims;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Claims written every way a bot may write them, for {@code ClaimCallsTest}; never run. */
public final class Claiming {

    private Claiming() {}

    public static Optional<String> byReference(List<String> names) {
        return names.stream().filter(Claims::claim).findFirst();
    }

    public static Supplier<Boolean> inALambda(String name) {
        return () -> Claims.claim(name);
    }

    public static Supplier<Boolean> inAnAnonymousClass(String name) {
        return new Supplier<>() {
            @Override
            public Boolean get() {
                return Claims.claim(name);
            }
        };
    }

    public static void releases(String name) {
        Claims.release(name);
    }
}
