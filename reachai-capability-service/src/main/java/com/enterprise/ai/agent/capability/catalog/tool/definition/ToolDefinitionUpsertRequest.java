package com.enterprise.ai.agent.capability.catalog.tool.definition;

import java.util.List;

public record ToolDefinitionUpsertRequest(
        String name,
        String title,
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
        String qualifiedName,
        boolean enabled,
        String sideEffect,
        String skillKind,
        String specJson,
        Boolean draft,
        Object capabilityMetadata
) {

    public ToolDefinitionUpsertRequest(
            String name,
            String title,
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
            String qualifiedName,
            boolean enabled) {
        this(name, title, "TOOL", description, parameters, source, sourceLocation,
                httpMethod, baseUrl, contextPath, endpointPath,
                requestBodyType, responseType, projectId,
                projectCode, qualifiedName, enabled,
                null, null, null, false, null);
    }

    public static ToolDefinitionUpsertRequest skill(
            String name,
            String description,
            List<ToolDefinitionParameter> parameters,
            String source,
            String sourceLocation,
            boolean enabled,
            String sideEffect,
            String skillKind,
            String specJson) {
        return skill(name, description, parameters, source, sourceLocation,
                enabled, sideEffect, skillKind, specJson, false);
    }

    public static ToolDefinitionUpsertRequest skill(
            String name,
            String description,
            List<ToolDefinitionParameter> parameters,
            String source,
            String sourceLocation,
            boolean enabled,
            String sideEffect,
            String skillKind,
            String specJson,
            boolean draft) {
        return new ToolDefinitionUpsertRequest(
                name, name, "SKILL", description, parameters, source, sourceLocation,
                null, null, null, null,
                null, null, null, null, null,
                enabled,
                sideEffect, skillKind, specJson, draft, null);
    }

    public ToolDefinitionUpsertRequest withProjectScope(Long scopedProjectId,
                                                        String scopedProjectCode,
                                                        String scopedQualifiedName) {
        return new ToolDefinitionUpsertRequest(
                name, title, kind, description, parameters, source, sourceLocation,
                httpMethod, baseUrl, contextPath, endpointPath,
                requestBodyType, responseType, scopedProjectId,
                scopedProjectCode, scopedQualifiedName, enabled,
                sideEffect, skillKind, specJson, draft, capabilityMetadata);
    }
}
