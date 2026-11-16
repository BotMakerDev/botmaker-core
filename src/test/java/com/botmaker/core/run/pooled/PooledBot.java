// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.run.pooled;

import com.botmaker.plugin.api.params.Claims;

import java.io.IOException;
import java.io.OutputStream;

/**
 * A bot that reads its pooled parameter through a direct call, as a bot's code does. Given {@code crash}, it ends at
 * once with 9; given {@code claim}, it claims {@link #KEY}, says whether it got it, and keeps it until its input
 * closes; given {@code claim-and-crash}, it claims the key and ends at once with 9.
 */
public final class PooledBot {

    /** The key every run here claims. */
    public static final String KEY = "guild:7";

    private PooledBot() {}

    public static void main(String[] args) throws IOException {
        System.out.println("account=" + new PooledParams().account());
        if (args.length == 0) return;
        switch (args[0]) {
            case "crash" -> Runtime.getRuntime().halt(9);
            case "claim" -> {
                System.out.println("claimed=" + Claims.claim(KEY));
                System.out.flush();
                // Holds the key, alive, until the test closes this run's input.
                System.in.transferTo(OutputStream.nullOutputStream());
            }
            case "claim-and-crash" -> {
                System.out.println("claimed=" + Claims.claim(KEY));
                System.out.flush();
                Runtime.getRuntime().halt(9);
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }
}
