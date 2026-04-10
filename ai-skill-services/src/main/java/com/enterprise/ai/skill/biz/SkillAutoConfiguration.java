package com.enterprise.ai.skill.biz;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Skill 模块自动配置 — 通过 spring.factories / AutoConfiguration.imports 被宿主应用自动加载
 */
@Configuration
@ComponentScan(basePackages = "com.enterprise.ai.skill.biz")
public class SkillAutoConfiguration {
}
