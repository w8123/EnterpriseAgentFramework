package com.enterprise.ai.capability.registry;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
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
            throw new IllegalStateException("能力目录状态无法序列化，不能创建可回滚的评审项", ex);
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
        state.put("assetType", assetType(row.getAssetType()));
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
        state.put("assetType", assetType(tool.getAssetType()));
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

    static void restoreScanTool(ScanProjectToolEntity row, JsonNode state) {
        row.setId(nullableLong(state, "id"));
        row.setProjectId(nullableLong(state, "projectId"));
        row.setModuleId(nullableLong(state, "moduleId"));
        row.setName(nullableText(state, "name"));
        row.setTitle(nullableText(state, "title"));
        row.setDescription(nullableText(state, "description"));
        row.setParametersJson(nullableText(state, "parametersJson"));
        row.setSource(nullableText(state, "source"));
        row.setSourceLocation(nullableText(state, "sourceLocation"));
        row.setAssetType(assetType(state));
        row.setHttpMethod(nullableText(state, "httpMethod"));
        row.setBaseUrl(nullableText(state, "baseUrl"));
        row.setContextPath(nullableText(state, "contextPath"));
        row.setEndpointPath(nullableText(state, "endpointPath"));
        row.setRequestBodyType(nullableText(state, "requestBodyType"));
        row.setResponseType(nullableText(state, "responseType"));
        row.setAiDescription(nullableText(state, "aiDescription"));
        row.setCapabilityMetadataJson(nullableText(state, "capabilityMetadataJson"));
        row.setSensitiveDataJson(nullableText(state, "sensitiveDataJson"));
        row.setEnabled(nullableBoolean(state, "enabled"));
        row.setGlobalToolDefinitionId(nullableLong(state, "globalToolDefinitionId"));
        row.setRemovedFromSource(nullableBoolean(state, "removedFromSource"));
        row.setRemovedAt(nullableDateTime(state, "removedAt"));
    }

    static void restoreGlobalTool(ToolDefinitionEntity tool, JsonNode state) {
        tool.setId(nullableLong(state, "id"));
        tool.setName(nullableText(state, "name"));
        tool.setTitle(nullableText(state, "title"));
        tool.setDescription(nullableText(state, "description"));
        tool.setAiDescription(nullableText(state, "aiDescription"));
        tool.setCapabilityMetadataJson(nullableText(state, "capabilityMetadataJson"));
        tool.setParametersJson(nullableText(state, "parametersJson"));
        tool.setSource(nullableText(state, "source"));
        tool.setSourceLocation(nullableText(state, "sourceLocation"));
        tool.setAssetType(assetType(state));
        tool.setHttpMethod(nullableText(state, "httpMethod"));
        tool.setBaseUrl(nullableText(state, "baseUrl"));
        tool.setContextPath(nullableText(state, "contextPath"));
        tool.setEndpointPath(nullableText(state, "endpointPath"));
        tool.setRequestBodyType(nullableText(state, "requestBodyType"));
        tool.setResponseType(nullableText(state, "responseType"));
        tool.setProjectId(nullableLong(state, "projectId"));
        tool.setProjectCode(nullableText(state, "projectCode"));
        tool.setQualifiedName(nullableText(state, "qualifiedName"));
        tool.setModuleId(nullableLong(state, "moduleId"));
        tool.setEnabled(nullableBoolean(state, "enabled"));
        tool.setSideEffect(nullableText(state, "sideEffect"));
    }

    /** Adds the projection default only in memory for pre-asset-type review snapshots. */
    static JsonNode normalizeLegacyAssetTypes(JsonNode state) {
        if (!(state instanceof ObjectNode root)) {
            return state;
        }
        ObjectNode normalized = root.deepCopy();
        normalizeLegacyAssetType(normalized.get("scanTool"));
        normalizeLegacyAssetType(normalized.get("globalTool"));
        return normalized;
    }

    private static void normalizeLegacyAssetType(JsonNode state) {
        if (state instanceof ObjectNode projection
                && (!projection.has("assetType") || projection.get("assetType").isNull())) {
            projection.put("assetType", CapabilityAssetType.UNCLASSIFIED.name());
        }
    }

    private static String nullableText(JsonNode state, String field) {
        JsonNode value = state.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long nullableLong(JsonNode state, String field) {
        JsonNode value = state.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }

    private static Boolean nullableBoolean(JsonNode state, String field) {
        JsonNode value = state.get(field);
        return value == null || value.isNull() ? null : value.asBoolean();
    }

    private static String assetType(JsonNode state) {
        return assetType(nullableText(state, "assetType"));
    }

    private static String assetType(String value) {
        return StringUtils.hasText(value) ? value : CapabilityAssetType.UNCLASSIFIED.name();
    }

    private static LocalDateTime nullableDateTime(JsonNode state, String field) {
        String value = nullableText(state, field);
        return StringUtils.hasText(value) ? LocalDateTime.parse(value) : null;
    }

}
