// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.run.pooled;

import com.botmaker.plugin.api.params.Pool;
import com.botmaker.plugin.api.params.Pools;

/** The pool {@code PooledRunsTest} deals from, which each run's agent builds again. */
@Pools
public class PooledPools {

    public static Pool<String> accounts() {
        return Pool.of("account-1@example.com", "account-2@example.com").fills(PooledParams::account);
    }
}
