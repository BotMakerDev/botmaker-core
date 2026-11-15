package com.botmaker.core.run.pooled;

/** A bot that reads its pooled parameter through a direct call, as a bot's code does. */
public final class PooledBot {

    private PooledBot() {}

    public static void main(String[] args) {
        System.out.println("account=" + new PooledParams().account());
        if (args.length > 0 && args[0].equals("crash")) Runtime.getRuntime().halt(9);
    }
}
