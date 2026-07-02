package com.enterprise.ai.capability.internal;

import com.enterprise.ai.agent.capability.CapabilityAssetService;
import com.enterprise.ai.agent.capability.CompositionDefinitionEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CapabilityCompositionLookupService {

    private final CapabilityAssetService capabilityAssetService;

    public Map<String, Object> getCompositionDefinition(String qualifiedName) {
        if (!StringUtils.hasText(qualifiedName)) {
            throw new IllegalArgumentException("Composition definition not found: " + qualifiedName);
        }
        String key = qualifiedName.trim();
        CompositionDefinitionEntity entity = capabilityAssetService.findCompositionByQualifiedName(key)
                .orElseThrow(() -> new IllegalArgumentException("Composition definition not found: " + key));
        return toMap(entity);
    }

    private Map<String, Object> toMap(CompositionDefinitionEntity entity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", entity.getId());
        body.put("capabilityModuleId", entity.getCapabilityModuleId());
        body.put("capabilityCode", entity.getCapabilityCode());
        body.put("compositionCode", entity.getCompositionCode());
        body.put("name", entity.getName());
        body.put("qualifiedName", entity.getQualifiedName());
        body.put("description", entity.getDescription());
        body.put("graphSpecJson", entity.getGraphSpecJson());
        body.put("inputSchemaJson", entity.getInputSchemaJson());
        body.put("outputSchemaJson", entity.getOutputSchemaJson());
        body.put("sideEffect", entity.getSideEffect());
        body.put("enabled", entity.getEnabled());
        body.put("agentVisible", entity.getAgentVisible());
        body.put("createTime", String.valueOf(entity.getCreateTime()));
        body.put("updateTime", String.valueOf(entity.getUpdateTime()));
        return body;
    }
}
