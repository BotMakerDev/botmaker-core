package com.botmaker.core.run.agent;

/** The methods {@link PooledMethodsTest} rewrites: each body is what a run of several must not see. */
public class PooledFixture {

    public String account() {
        return "body";
    }

    public static int level() {
        return 1;
    }

    public static long gold() {
        return 1L;
    }

    public String untouched() {
        return "kept";
    }

    /** A method with no body to replace. */
    public abstract static class NoBody {
        public abstract String name();
    }
}
