package com.enterprise.ai.skill;

/**
 * 工具参数描述 — 用于生成 JSON Schema 供 LLM 理解参数结构
 */
public record ToolParameter(
        String name,
        String type,
        String description,
        boolean required
) {
    public static ToolParameter required(String name, String type, String description) {
        return new ToolParameter(name, type, description, true);
    }

    public static ToolParameter optional(String name, String type, String description) {
        return new ToolParameter(name, type, description, false);
    }
}
