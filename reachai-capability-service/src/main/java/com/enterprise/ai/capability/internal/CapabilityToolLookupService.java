package com.enterprise.ai.capability.internal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CapabilityToolLookupService {

    private final ToolDefinitionMapper toolDefinitionMapper;

    public Map<String, Object> getToolDefinition(String qualifiedName) {
        if (!StringUtils.hasText(qualifiedName)) {
            throw new IllegalArgumentException("Tool definition not found: " + qualifiedName);
        }
        String key = qualifiedName.trim();
        ToolDefinitionEntity entity = toolDefinitionMapper.selectOne(Wrappers.<ToolDefinitionEntity>lambdaQuery()
                .eq(ToolDefinitionEntity::getQualifiedName, key)
                .last("limit 1"));
        if (entity == null) {
            entity = toolDefinitionMapper.selectOne(Wrappers.<ToolDefinitionEntity>lambdaQuery()
                    .eq(ToolDefinitionEntity::getName, key)
                    .last("limit 1"));
        }
        if (entity == null) {
            throw new IllegalArgumentException("Tool definition not found: " + key);
        }
        return toMap(entity);
    }

    private Map<String, Object> toMap(ToolDefinitionEntity entity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", entity.getId());
        body.put("name", entity.getName());
        body.put("kind", entity.getKind());
        body.put("description", entity.getDescription());
        body.put("aiDescription", entity.getAiDescription());
        body.put("capabilityMetadataJson", entity.getCapabilityMetadataJson());
        body.put("parametersJson", entity.getParametersJson());
        body.put("specJson", entity.getSpecJson());
        body.put("source", entity.getSource());
        body.put("sourceLocation", entity.getSourceLocation());
        body.put("httpMethod", entity.getHttpMethod());
        body.put("baseUrl", entity.getBaseUrl());
        body.put("contextPath", entity.getContextPath());
        body.put("endpointPath", entity.getEndpointPath());
        body.put("requestBodyType", entity.getRequestBodyType());
        body.put("responseType", entity.getResponseType());
        body.put("projectId", entity.getProjectId());
        body.put("projectCode", entity.getProjectCode());
        body.put("visibility", entity.getVisibility());
        body.put("qualifiedName", entity.getQualifiedName());
        body.put("moduleId", entity.getModuleId());
        body.put("enabled", entity.getEnabled());
        body.put("agentVisible", entity.getAgentVisible());
        body.put("sideEffect", entity.getSideEffect());
        body.put("skillKind", entity.getSkillKind());
        body.put("draft", entity.getDraft());
        body.put("lightweightEnabled", entity.getLightweightEnabled());
        body.put("createTime", String.valueOf(entity.getCreateTime()));
        body.put("updateTime", String.valueOf(entity.getUpdateTime()));
        return body;
    }
}
