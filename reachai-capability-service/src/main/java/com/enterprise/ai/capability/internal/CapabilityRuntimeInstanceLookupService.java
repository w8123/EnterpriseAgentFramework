package com.enterprise.ai.capability.internal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.registry.ProjectInstanceEntity;
import com.enterprise.ai.agent.registry.ProjectInstanceMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CapabilityRuntimeInstanceLookupService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ProjectInstanceMapper instanceMapper;
    private final ObjectMapper objectMapper;

    public List<Map<String, Object>> listRuntimeInstances() {
        return instanceMapper.selectList(Wrappers.<ProjectInstanceEntity>lambdaQuery()
                        .orderByDesc(ProjectInstanceEntity::getLastHeartbeatAt))
                .stream()
                .map(this::toRuntimeInstance)
                .collect(Collectors.toList());
    }

    private Map<String, Object> toRuntimeInstance(ProjectInstanceEntity entity) {
        Map<String, Object> metadata = parseMap(entity.getMetadataJson());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectCode", entity.getProjectCode());
        body.put("instanceId", entity.getInstanceId());
        body.put("baseUrl", entity.getBaseUrl());
        body.put("host", entity.getHost());
        body.put("port", entity.getPort());
        body.put("appVersion", entity.getAppVersion());
        body.put("sdkVersion", entity.getSdkVersion());
        body.put("status", entity.getStatus());
        body.put("metadataJson", entity.getMetadataJson());
        body.put("lastHeartbeatAt", entity.getLastHeartbeatAt());
        body.put("runtimePlacement", metadata.get("runtimePlacement"));
        body.put("runtimeTypes", metadata.get("runtimeTypes"));
        body.put("supportsGraph", metadata.get("supportsGraph"));
        body.put("supportsTools", metadata.get("supportsTools"));
        body.put("supportsAutonomous", metadata.get("supportsAutonomous"));
        body.put("supportsWorkflow", metadata.get("supportsWorkflow"));
        body.put("supportsEmbeddedExecution", metadata.get("supportsEmbeddedExecution"));
        body.put("supportsHybridExecution", metadata.get("supportsHybridExecution"));
        body.put("governancePolicy", governancePolicy(entity));
        return body;
    }

    private Map<String, Object> governancePolicy(ProjectInstanceEntity entity) {
        Map<String, Object> stored = parseMap(entity.getGovernancePolicyJson());
        boolean disabledByStatus = "DISABLED".equalsIgnoreCase(entity.getStatus());
        boolean disabled = disabledByStatus || bool(stored.get("disabled"));
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("disabled", disabled);
        policy.put("status", entity.getStatus() == null ? "UNKNOWN" : entity.getStatus());
        policy.put("minSdkVersion", stored.get("minSdkVersion"));
        policy.put("allowEmbeddedExecution", stored.containsKey("allowEmbeddedExecution")
                ? stored.get("allowEmbeddedExecution")
                : Boolean.TRUE);
        policy.put("allowHybridExecution", stored.containsKey("allowHybridExecution")
                ? stored.get("allowHybridExecution")
                : Boolean.TRUE);
        policy.put("message", firstText(
                text(stored.get("message")),
                disabledByStatus ? "Runtime instance is disabled by platform governance." : "ok"));
        return policy;
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(text(value));
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
