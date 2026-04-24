package com.enterprise.ai.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 工具注册中心 — 集中管理所有 AiTool 实例
 * <p>
 * Spring 容器中所有实现 AiTool 接口的 Bean 会被自动注册。
 * AgentScope / Spring AI 等框架通过各自的 Adapter 访问本注册中心，
 * 工具实现与框架彻底解耦。
 * <p>
 * 此类位于 ai-skill-sdk，agent-service 和 skill-services 都可使用。
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, AiTool> tools = new LinkedHashMap<>();

    public ToolRegistry() {}

    public ToolRegistry(List<AiTool> aiTools) {
        aiTools.forEach(this::register);
    }

    public void register(AiTool tool) {
        tools.put(tool.name(), tool);
        log.debug("[ToolRegistry] 注册工具: {}", tool.name());
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

    /** 仅当已注册且为 {@link AiSkill} 时返回 true。 */
    public boolean isSkill(String name) {
        AiTool tool = tools.get(name);
        return tool instanceof AiSkill;
    }

    /** 从注册表移除（Skill/Tool 热更新 / 删除时用）。 */
    public AiTool remove(String name) {
        AiTool removed = tools.remove(name);
        if (removed != null) {
            log.debug("[ToolRegistry] 卸载工具: {}", name);
        }
        return removed;
    }

    public Set<String> getToolNames() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    public int size() {
        return tools.size();
    }
}
