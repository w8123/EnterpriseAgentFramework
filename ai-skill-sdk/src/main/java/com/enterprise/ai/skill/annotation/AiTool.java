package com.enterprise.ai.skill.annotation;

import com.enterprise.ai.skill.SideEffectLevel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a Java method as a ReachAI Tool source.
 * <p>
 * The annotation lives in a sub-package because {@code com.enterprise.ai.skill.AiTool}
 * is already the executable tool interface.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AiTool {

    String name() default "";

    String title() default "";

    String description() default "";

    String domain() default "";

    String module() default "";

    String[] tags() default {};

    SideEffectLevel sideEffect() default SideEffectLevel.WRITE;

    boolean agentVisible() default true;

    String[] requiredRoles() default {};

    int timeoutMs() default 0;

    int retryLimit() default -1;
}
