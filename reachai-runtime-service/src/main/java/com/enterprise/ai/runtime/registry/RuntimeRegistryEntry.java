package com.enterprise.ai.runtime.registry;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record RuntimeRegistryEntry(
        String id,
        String source,
        String runtimeRole,
        String runtimeType,
        String displayName,
        String description,
        String runtimePlacement,
        String status,
        boolean available,
        String unavailableReason,
        boolean supportsGraph,
        boolean supportsTools,
        boolean supportsAutonomous,
        boolean supportsWorkflow,
        boolean supportsEmbeddedExecution,
        boolean supportsHybridExecution,
        String projectCode,
        String instanceId,
        String baseUrl,
        String host,
        Integer port,
        String appVersion,
        String sdkVersion,
        LocalDateTime lastHeartbeatAt,
        boolean policyDisabled,
        String minSdkVersion,
        Boolean allowEmbeddedExecution,
        Boolean allowHybridExecution,
        String policyMessage,
        List<String> runtimeTypes,
        Map<String, Object> metadata) {

    public RuntimeRegistryEntry {
        runtimeTypes = runtimeTypes == null ? List.of() : List.copyOf(runtimeTypes);
        metadata = metadata == null ? Map.of() : metadata.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public static RuntimeRegistryEntry platformLangGraph4j() {
        return new RuntimeRegistryEntry(
                "platform:LANGGRAPH4J",
                "PLATFORM",
                "AGENT_RUNTIME",
                "LANGGRAPH4J",
                "LangGraph4j Runtime",
                "ReachAI central GraphSpec workflow runtime",
                "CENTRAL",
                "ONLINE",
                true,
                null,
                true,
                true,
                false,
                true,
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
                false,
                null,
                Boolean.FALSE,
                Boolean.FALSE,
                "ok",
                List.of("LANGGRAPH4J"),
                Map.of("securityLevel", "IN_PROCESS"));
    }
}
