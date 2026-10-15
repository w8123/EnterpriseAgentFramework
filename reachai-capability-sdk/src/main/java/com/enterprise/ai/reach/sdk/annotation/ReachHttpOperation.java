package com.enterprise.ai.reach.sdk.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the effect of an existing HTTP operation without declaring a Java business method.
 *
 * <p>Use this when the HTTP verb does not describe the business effect, for example a legacy
 * GET that sends a message or synchronizes records. A method declaration overrides a class
 * declaration; otherwise the Starter retains its normal HTTP-verb inference.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ReachHttpOperation {
    ReachSideEffectLevel sideEffect() default ReachSideEffectLevel.WRITE;

    /** Source-level explanation of the business effect; never place credentials or sample data here. */
    String reason() default "";
}
