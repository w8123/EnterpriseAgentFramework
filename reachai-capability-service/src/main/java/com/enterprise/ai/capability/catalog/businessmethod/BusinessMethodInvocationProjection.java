package com.enterprise.ai.capability.catalog.businessmethod;

import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;

/** The sole adapter from owner facts to the HTTP invocation protocol's technical definition. */
public final class BusinessMethodInvocationProjection {
    private static final ObjectMapper JSON = new ObjectMapper();
    private BusinessMethodInvocationProjection() { }

    public static ToolDefinitionEntity from(BusinessMethodDefinition method) {
        var asset = method.asset();
        var declaration = method.declaration();
        var projection = new ToolDefinitionEntity();
        projection.setId(asset.getId());
        projection.setName(asset.getInvocationName());
        projection.setQualifiedName(asset.getQualifiedName());
        projection.setSourceQualifiedName(asset.getQualifiedName());
        projection.setProjectId(asset.getProjectId());
        projection.setProjectCode(asset.getProjectCode());
        projection.setAssetType("BUSINESS_METHOD");
        projection.setSource("sdk");
        projection.setSourceLocation("sdk:" + asset.getProjectCode() + ":" + asset.getMethodCode());
        projection.setTitle(declaration.title());
        projection.setDescription(declaration.description());
        projection.setEnabled(Boolean.TRUE.equals(asset.getEnabled()));
        projection.setSideEffect(declaration.sideEffect());
        projection.setHttpMethod(declaration.httpMethod());
        projection.setBaseUrl(declaration.baseUrl());
        projection.setContextPath(declaration.contextPath());
        projection.setEndpointPath(declaration.endpointPath());
        projection.setRequestBodyType(declaration.requestBodyType());
        projection.setResponseType(declaration.responseType());
        projection.setCreateTime(asset.getCreatedAt());
        projection.setUpdateTime(asset.getUpdatedAt());
        try {
            var metadata = new LinkedHashMap<String, Object>(declaration.metadata());
            metadata.put("sideEffect", declaration.sideEffect());
            projection.setCapabilityMetadataJson(JSON.writeValueAsString(metadata));
            projection.setParametersJson(JSON.writeValueAsString(declaration.parameters()));
        } catch (Exception invalid) {
            throw new IllegalStateException("业务方法接纳声明无法生成调用定义", invalid);
        }
        return projection;
    }
}
