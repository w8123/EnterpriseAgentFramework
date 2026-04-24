package com.enterprise.ai.agent.scan;

import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;

/**
 * 将扫描接口行转为 {@link ToolDefinitionEntity} 视图，供 HTTP 动态工具与语义上下文收集复用。
 */
public final class ScanProjectToolAdapter {

    private ScanProjectToolAdapter() {
    }

    public static ToolDefinitionEntity toDefinitionEntity(ScanProjectToolEntity s) {
        ToolDefinitionEntity e = new ToolDefinitionEntity();
        e.setId(s.getId());
        e.setName(s.getName());
        e.setDescription(s.getDescription());
        e.setParametersJson(s.getParametersJson());
        e.setSource(s.getSource());
        e.setSourceLocation(s.getSourceLocation());
        e.setHttpMethod(s.getHttpMethod());
        e.setBaseUrl(s.getBaseUrl());
        e.setContextPath(s.getContextPath());
        e.setEndpointPath(s.getEndpointPath());
        e.setRequestBodyType(s.getRequestBodyType());
        e.setResponseType(s.getResponseType());
        e.setAiDescription(s.getAiDescription());
        e.setProjectId(s.getProjectId());
        e.setModuleId(s.getModuleId());
        e.setEnabled(s.getEnabled());
        e.setAgentVisible(s.getAgentVisible());
        e.setLightweightEnabled(s.getLightweightEnabled());
        return e;
    }
}
