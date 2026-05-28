package com.enterprise.ai.skill.annotation;

import com.enterprise.ai.skill.interaction.InteractionType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AiInteraction {

    String code();

    String name() default "";

    String description() default "";

    InteractionType type() default InteractionType.COLLECT_INPUT;

    String targetTool() default "";

    boolean agentVisible() default true;
}
