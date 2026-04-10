package com.jishi.ai.agent.tools;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具注册中心（Spring 管理层）— 继承 ai-skill-sdk 的 ToolRegistry
 * <p>
 * 在 Spring 容器中自动发现和注册所有 AiTool Bean。
 * 底层能力由 sdk 的 {@link com.enterprise.ai.skill.ToolRegistry} 提供。
 */
@Slf4j
@Component
public class ToolRegistry extends com.enterprise.ai.skill.ToolRegistry {

    public ToolRegistry(List<com.enterprise.ai.skill.AiTool> aiTools) {
        super(aiTools);
    }

    @PostConstruct
    public void init() {
        log.info("[ToolRegistry] 已注册 {} 个工具: {}", size(), getToolNames());
    }
}
