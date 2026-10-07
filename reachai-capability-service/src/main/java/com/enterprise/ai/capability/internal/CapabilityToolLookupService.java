package com.enterprise.ai.capability.internal;

import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodCatalogService;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodInvocationProjection;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodExecutionRevision;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CapabilityToolLookupService {

    private final BusinessMethodCatalogService businessMethods;
    private final RegistrySecurityService registrySecurity;

    public Map<String, Object> getToolDefinition(String qualifiedName) {
        if (!StringUtils.hasText(qualifiedName)) {
            throw new IllegalArgumentException("Tool definition not found: " + qualifiedName);
        }
        var method = businessMethods.find(qualifiedName.trim())
                .orElseThrow(() -> new IllegalArgumentException("Business method not found: " + qualifiedName.trim()));
        Map<String, Object> result = toMap(BusinessMethodInvocationProjection.from(method));
        result.put("assetId", method.asset().getId());
        result.put("acceptedRevisionId", method.revision().getId());
        result.put("businessContractHash", method.revision().getContractHash());
        result.put("bindingHash", method.revision().getBindingHash());
        result.put("contractHash", method.revision().getInvocationHash());
        result.put("executionRevision", BusinessMethodExecutionRevision.of(method,
                registrySecurity.findPrimaryActiveCredential(method.asset().getProjectCode()).orElse(null)));
        result.put("sourceAvailability", method.sourceAvailability());
        return result;
    }

    private Map<String, Object> toMap(ToolDefinitionEntity entity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", entity.getId());
        body.put("name", entity.getName());
        body.put("title", entity.getTitle());
        body.put("description", entity.getDescription());
        body.put("aiDescription", entity.getAiDescription());
        body.put("capabilityMetadataJson", entity.getCapabilityMetadataJson());
        body.put("parametersJson", entity.getParametersJson());
        body.put("source", entity.getSource());
        body.put("assetType", entity.getAssetType());
        body.put("sourceLocation", entity.getSourceLocation());
        body.put("httpMethod", entity.getHttpMethod());
        body.put("baseUrl", entity.getBaseUrl());
        body.put("contextPath", entity.getContextPath());
        body.put("endpointPath", entity.getEndpointPath());
        body.put("requestBodyType", entity.getRequestBodyType());
        body.put("responseType", entity.getResponseType());
        body.put("projectId", entity.getProjectId());
        body.put("projectCode", entity.getProjectCode());
        body.put("qualifiedName", entity.getQualifiedName());
        body.put("moduleId", entity.getModuleId());
        body.put("enabled", entity.getEnabled());
        body.put("sideEffect", entity.getSideEffect());
        body.put("createTime", String.valueOf(entity.getCreateTime()));
        body.put("updateTime", String.valueOf(entity.getUpdateTime()));
        return body;
    }
}
