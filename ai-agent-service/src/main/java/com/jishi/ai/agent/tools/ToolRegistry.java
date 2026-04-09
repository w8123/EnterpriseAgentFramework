package com.jishi.ai.agent.tools;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册中心 — 集中管理所有 AiTool 实例
 * <p>
 * Spring 容器中所有实现 AiTool 接口的 Bean 会被自动注册。
 * AgentScope / Spring AI 等框架通过各自的 Adapter 访问本注册中心，
 * 工具实现与框架彻底解耦。
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, AiTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AiTool> aiTools) {
        aiTools.forEach(this::register);
    }

    @PostConstruct
    public void init() {
        log.info("[ToolRegistry] 已注册 {} 个工具: {}", tools.size(), tools.keySet());
    }

    public void register(AiTool tool) {
        tools.put(tool.name(), tool);
    }

    public AiTool get(String name) {
        AiTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未注册的工具: " + name + "，已注册: " + tools.keySet());
        }
        return tool;
    }

    public Object execute(String toolName, Map<String, Object> args) {
        log.debug("[ToolRegistry] 执行工具: name={}, args={}", toolName, args);
        return get(toolName).execute(args);
    }

    public Collection<AiTool> getAllTools() {
        return Collections.unmodifiableCollection(tools.values());
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }
}
