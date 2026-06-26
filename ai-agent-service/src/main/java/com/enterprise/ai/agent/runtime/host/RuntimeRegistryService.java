package com.enterprise.ai.agent.runtime.host;


import com.enterprise.ai.agent.runtime.*;
import com.enterprise.ai.agent.registry.AiRegistryService;
import com.enterprise.ai.agent.registry.ProjectInstanceEntity;
import com.enterprise.ai.agent.registry.RegistryContracts.RuntimeGovernancePolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RuntimeRegistryService {

    private final AgentRuntimeSelector runtimeSelector;
    private final AiRegistryService registryService;
    private final ObjectMapper objectMapper;

    public List<RuntimeRegistryEntry> list() {
        List<RuntimeRegistryEntry> entries = new ArrayList<>();
        entries.addAll(runtimeSelector.capabilities().stream()
                .map(this::fromPlatformRuntime)
                .toList());
        entries.addAll(registryService.listAllInstances().stream()
                .map(this::fromProjectInstance)
                .toList());
        return entries;
    }

    private RuntimeRegistryEntry fromPlatformRuntime(AgentRuntimeCapability c) {
        String runtimeType = c.getRuntimeType();
        boolean workflow = c.isSupportsGraph();
        boolean autonomous = !workflow;
        return new RuntimeRegistryEntry(
                "platform:" + runtimeType,
                "PLATFORM",
                "AGENT_RUNTIME",
                runtimeType,
                c.getDisplayName(),
                c.getDescription(),
                "CENTRAL",
                c.isAvailable() ? "ONLINE" : "DISABLED",
                c.isAvailable(),
                c.getUnavailableReason(),
                c.isSupportsGraph(),
                c.isSupportsTools(),
                autonomous,
                workflow,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                !c.isAvailable(),
                null,
                Boolean.FALSE,
                Boolean.FALSE,
                c.isAvailable() ? "ok" : c.getUnavailableReason(),
                List.of(runtimeType),
                Map.of(
                        "supportedModelTypes", c.getSupportedModelTypes() == null ? List.of() : c.getSupportedModelTypes(),
                        "securityLevel", c.getSecurityLevel() == null ? "" : c.getSecurityLevel()
                )
        );
    }

    private RuntimeRegistryEntry fromProjectInstance(ProjectInstanceEntity entity) {
        Map<String, Object> metadata = parseMetadata(entity.getMetadataJson());
        List<String> runtimeTypes = stringList(metadata.get("runtimeTypes"));
        if (runtimeTypes.isEmpty()) {
            runtimeTypes = List.of("EMBEDDED_RUNTIME");
        }
        String placement = firstText(asString(metadata.get("runtimePlacement")), "EMBEDDED").toUpperCase(Locale.ROOT);
        String runtimeRole = runtimeRole(placement, runtimeTypes, metadata);
        String status = firstText(entity.getStatus(), "UNKNOWN").toUpperCase(Locale.ROOT);
        boolean available = "ONLINE".equals(status);
        boolean supportsGraph = bool(metadata.get("supportsGraph"));
        boolean supportsTools = bool(metadata.get("supportsTools"));
        boolean embedded = bool(metadata.get("supportsEmbeddedExecution")) || "EMBEDDED".equals(placement);
        boolean hybrid = bool(metadata.get("supportsHybridExecution")) || "HYBRID".equals(placement);
        RuntimeGovernancePolicy policy = registryService.governancePolicy(entity);
        return new RuntimeRegistryEntry(
                "instance:" + entity.getProjectCode() + ":" + entity.getInstanceId(),
                "PROJECT_INSTANCE",
                runtimeRole,
                runtimeTypes.get(0),
                displayName(entity, runtimeRole),
                description(runtimeRole),
                placement,
                status,
                available,
                available ? null : "Runtime 实例当前状态为 " + status,
                supportsGraph,
                supportsTools,
                bool(metadata.get("supportsAutonomous")),
                bool(metadata.get("supportsWorkflow")) || supportsGraph,
                embedded,
                hybrid,
                entity.getProjectCode(),
                entity.getInstanceId(),
                entity.getBaseUrl(),
                entity.getHost(),
                entity.getPort(),
                entity.getAppVersion(),
                entity.getSdkVersion(),
                entity.getLastHeartbeatAt(),
                policy.disabled(),
                policy.minSdkVersion(),
                policy.allowEmbeddedExecution(),
                policy.allowHybridExecution(),
                policy.message(),
                runtimeTypes,
                metadata
        );
    }

    private String runtimeRole(String placement, List<String> runtimeTypes, Map<String, Object> metadata) {
        String explicit = asString(metadata.get("runtimeRole"));
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim().toUpperCase(Locale.ROOT);
        }
        if ("CAPABILITY_HOST".equals(placement)) {
            return "CAPABILITY_HOST";
        }
        for (String runtimeType : runtimeTypes) {
            String normalized = runtimeType == null ? "" : runtimeType.toUpperCase(Locale.ROOT);
            if (normalized.contains("CAPABILITY_HOST")) {
                return "CAPABILITY_HOST";
            }
        }
        return "AGENT_RUNTIME";
    }

    private String displayName(ProjectInstanceEntity entity, String runtimeRole) {
        if ("CAPABILITY_HOST".equals(runtimeRole)) {
            return "Capability Host";
        }
        return entity.getProjectCode() + " / " + entity.getHost();
    }

    private String description(String runtimeRole) {
        if ("CAPABILITY_HOST".equals(runtimeRole)) {
            return "业务系统通过 ReachAI JDK8 接入 SDK 暴露的 Capability Host";
        }
        return "业务系统 SDK 心跳上报的 Agent Runtime 实例";
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::asString)
                    .filter(s -> s != null && !s.isBlank())
                    .toList();
        }
        String single = asString(value);
        return single == null || single.isBlank() ? List.of() : List.of(single);
    }

    private boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return false;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
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
