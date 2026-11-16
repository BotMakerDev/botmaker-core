// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.run.pooled;

import com.botmaker.plugin.api.params.Param;

/** A bot's parameter group as {@code PooledRunsTest} runs it: the body's value is what no run of several sees. */
@Param
public class PooledParams {

    public String account() {
        return "body";
    }
}
