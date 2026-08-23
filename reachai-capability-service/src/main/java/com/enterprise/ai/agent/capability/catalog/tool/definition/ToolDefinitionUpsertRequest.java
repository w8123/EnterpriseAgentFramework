package com.enterprise.ai.agent.capability.catalog.tool.definition;

import java.util.List;

public record ToolDefinitionUpsertRequest(
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
        boolean enabled,
        String sideEffect,
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
        this(name, title, description, parameters, source, sourceLocation,
                httpMethod, baseUrl, contextPath, endpointPath,
                requestBodyType, responseType, projectId,
                projectCode, qualifiedName, enabled,
                null, null);
    }

    public ToolDefinitionUpsertRequest withProjectScope(Long scopedProjectId,
                                                        String scopedProjectCode,
                                                        String scopedQualifiedName) {
        return new ToolDefinitionUpsertRequest(
                name, title, description, parameters, source, sourceLocation,
                httpMethod, baseUrl, contextPath, endpointPath,
                requestBodyType, responseType, scopedProjectId,
                scopedProjectCode, scopedQualifiedName, enabled,
                sideEffect, capabilityMetadata);
    }
}
