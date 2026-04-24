package com.enterprise.ai.agent.tools.definition;

import java.util.List;

/**
 * Tool / Skill 创建/更新请求。
 * <p>
 * Phase 2.0 起，此 record 同时承载 TOOL 与 SKILL：
 * <ul>
 *   <li>kind=TOOL：照旧用 httpMethod/baseUrl/contextPath/endpointPath；</li>
 *   <li>kind=SKILL：忽略 HTTP 字段，用 specJson（结构见 {@code SubAgentSpec}）。</li>
 * </ul>
 * 老调用方通过 {@link #tool(...)} 工厂仍然无需感知 Skill 字段。
 */
public record ToolDefinitionUpsertRequest(
        String name,
        String kind,
        String description,
        List<ToolDefinitionParameter> parameters,
        String source,
        String sourceLocation,
        String httpMethod,
        String baseUrl,
        String contextPath,
        String endpointPath,
        String requestBodyType,
        String responseType,
        Long projectId,
        boolean enabled,
        boolean agentVisible,
        boolean lightweightEnabled,
        String sideEffect,
        String skillKind,
        String specJson
) {

    /** 老的 15 参构造保持兼容：等价于 kind=TOOL、sideEffect/skillKind/specJson 为空。 */
    public ToolDefinitionUpsertRequest(
            String name,
            String description,
            List<ToolDefinitionParameter> parameters,
            String source,
            String sourceLocation,
            String httpMethod,
            String baseUrl,
            String contextPath,
            String endpointPath,
            String requestBodyType,
            String responseType,
            Long projectId,
            boolean enabled,
            boolean agentVisible,
            boolean lightweightEnabled) {
        this(name, "TOOL", description, parameters, source, sourceLocation,
                httpMethod, baseUrl, contextPath, endpointPath,
                requestBodyType, responseType, projectId,
                enabled, agentVisible, lightweightEnabled,
                null, null, null);
    }

    public static ToolDefinitionUpsertRequest skill(
            String name,
            String description,
            List<ToolDefinitionParameter> parameters,
            String source,
            String sourceLocation,
            boolean enabled,
            boolean agentVisible,
            String sideEffect,
            String skillKind,
            String specJson) {
        return new ToolDefinitionUpsertRequest(
                name, "SKILL", description, parameters, source, sourceLocation,
                null, null, null, null,
                null, null, null,
                enabled, agentVisible, false,
                sideEffect, skillKind, specJson);
    }
}
