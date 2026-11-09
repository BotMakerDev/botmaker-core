package com.botmaker.core.project.managed;

import com.botmaker.plugin.api.params.Param;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/** A plugin's group annotation whose values only a run writes, as the SDK's {@code @Facts}: what the tests lock. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Param.WrittenByRun
public @interface TestFacts {

    /** The same mark on an annotation nested in a holder, as a plugin may nest its own. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Param.WrittenByRun
    @interface Inner {}
}
