package com.enterprise.ai.capability.registry;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 评审前的可回滚目录状态。来源身份和写入时间由接纳命令维护，不进入回滚快照。
 */
final class CapabilityCatalogStateCodec {

    private CapabilityCatalogStateCodec() {
    }

    static String capture(
            ObjectMapper objectMapper,
            ScanProjectToolEntity scanTool,
            ToolDefinitionEntity globalTool) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("scanTool", scanTool == null ? null : scanToolState(scanTool));
        state.put("globalTool", globalTool == null ? null : globalToolState(globalTool));
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("方法调用投影状态无法序列化，不能创建可回滚的评审项", ex);
        }
    }

    private static Map<String, Object> scanToolState(ScanProjectToolEntity row) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", row.getId());
        state.put("projectId", row.getProjectId());
        state.put("moduleId", row.getModuleId());
        state.put("name", row.getName());
        state.put("title", row.getTitle());
        state.put("description", row.getDescription());
        state.put("parametersJson", row.getParametersJson());
        state.put("source", row.getSource());
        state.put("sourceLocation", row.getSourceLocation());
        state.put("assetType", row.getAssetType());
        state.put("httpMethod", row.getHttpMethod());
        state.put("baseUrl", row.getBaseUrl());
        state.put("contextPath", row.getContextPath());
        state.put("endpointPath", row.getEndpointPath());
        state.put("requestBodyType", row.getRequestBodyType());
        state.put("responseType", row.getResponseType());
        state.put("aiDescription", row.getAiDescription());
        state.put("capabilityMetadataJson", row.getCapabilityMetadataJson());
        state.put("sensitiveDataJson", row.getSensitiveDataJson());
        state.put("enabled", row.getEnabled());
        state.put("globalToolDefinitionId", row.getGlobalToolDefinitionId());
        state.put("removedFromSource", row.getRemovedFromSource());
        state.put("removedAt", row.getRemovedAt() == null ? null : row.getRemovedAt().toString());
        return state;
    }

    private static Map<String, Object> globalToolState(ToolDefinitionEntity tool) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", tool.getId());
        state.put("name", tool.getName());
        state.put("title", tool.getTitle());
        state.put("description", tool.getDescription());
        state.put("aiDescription", tool.getAiDescription());
        state.put("capabilityMetadataJson", tool.getCapabilityMetadataJson());
        state.put("parametersJson", tool.getParametersJson());
        state.put("source", tool.getSource());
        state.put("sourceLocation", tool.getSourceLocation());
        state.put("assetType", tool.getAssetType());
        state.put("httpMethod", tool.getHttpMethod());
        state.put("baseUrl", tool.getBaseUrl());
        state.put("contextPath", tool.getContextPath());
        state.put("endpointPath", tool.getEndpointPath());
        state.put("requestBodyType", tool.getRequestBodyType());
        state.put("responseType", tool.getResponseType());
        state.put("projectId", tool.getProjectId());
        state.put("projectCode", tool.getProjectCode());
        state.put("qualifiedName", tool.getQualifiedName());
        state.put("moduleId", tool.getModuleId());
        state.put("enabled", tool.getEnabled());
        state.put("sideEffect", tool.getSideEffect());
        return state;
    }

}
