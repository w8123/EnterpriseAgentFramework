package com.enterprise.ai.agent.capability.catalog.tool.definition;

import java.util.List;

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
        String projectCode,
        String visibility,
        String qualifiedName,
        boolean enabled,
        boolean agentVisible,
        boolean lightweightEnabled,
        String sideEffect,
        String skillKind,
        String specJson,
        Boolean draft,
        Object capabilityMetadata
) {

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
            String projectCode,
            String visibility,
            String qualifiedName,
            boolean enabled,
            boolean agentVisible,
            boolean lightweightEnabled) {
        this(name, "TOOL", description, parameters, source, sourceLocation,
                httpMethod, baseUrl, contextPath, endpointPath,
                requestBodyType, responseType, projectId,
                projectCode, visibility, qualifiedName,
                enabled, agentVisible, lightweightEnabled,
                null, null, null, false, null);
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
        return skill(name, description, parameters, source, sourceLocation,
                enabled, agentVisible, sideEffect, skillKind, specJson, false);
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
            String specJson,
            boolean draft) {
        return new ToolDefinitionUpsertRequest(
                name, "SKILL", description, parameters, source, sourceLocation,
                null, null, null, null,
                null, null, null, null, null, null,
                enabled, agentVisible, false,
                sideEffect, skillKind, specJson, draft, null);
    }

    public ToolDefinitionUpsertRequest withProjectScope(Long scopedProjectId,
                                                        String scopedProjectCode,
                                                        String scopedVisibility,
                                                        String scopedQualifiedName) {
        return new ToolDefinitionUpsertRequest(
                name, kind, description, parameters, source, sourceLocation,
                httpMethod, baseUrl, contextPath, endpointPath,
                requestBodyType, responseType, scopedProjectId,
                scopedProjectCode, scopedVisibility, scopedQualifiedName,
                enabled, agentVisible, lightweightEnabled,
                sideEffect, skillKind, specJson, draft, capabilityMetadata);
    }
}
