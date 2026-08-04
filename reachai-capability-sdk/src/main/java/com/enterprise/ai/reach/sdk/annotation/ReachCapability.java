package com.enterprise.ai.reach.sdk.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ReachCapability {

    String name() default "";

    String title() default "";

    String description() default "";

    String domain() default "";

    String module() default "";

    String[] tags() default {};

    ReachSideEffectLevel sideEffect() default ReachSideEffectLevel.WRITE;

    String[] requiredRoles() default {};

    int timeoutMs() default 0;

    int retryLimit() default -1;
}
