package com.enterprise.ai.runtime.registry;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RuntimeRegistryService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RuntimeCapabilityCatalogClient capabilityClient;
    private final ObjectMapper objectMapper;

    public List<RuntimeRegistryEntry> listRuntimes() {
        List<RuntimeRegistryEntry> entries = new ArrayList<>();
        entries.add(RuntimeRegistryEntry.platformLangGraph4j());
        for (Map<String, Object> item : capabilityClient.listRuntimeInstances()) {
            entries.add(fromCapabilityInstance(item));
        }
        return entries;
    }

    public RuntimeRegistryEntry findRuntimeInstance(String projectCode, String instanceId) {
        return listRuntimes().stream()
                .filter(entry -> textEquals(projectCode, entry.projectCode()))
                .filter(entry -> textEquals(instanceId, entry.instanceId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Runtime instance not found: " + projectCode + "/" + instanceId));
    }

    private RuntimeRegistryEntry fromCapabilityInstance(Map<String, Object> item) {
        Map<String, Object> metadata = metadata(item);
        List<String> runtimeTypes = stringList(firstValue(item, metadata, "runtimeTypes"));
        if (runtimeTypes.isEmpty()) {
            runtimeTypes = List.of("EMBEDDED_RUNTIME");
        }
        String placement = firstText(asString(firstValue(item, metadata, "runtimePlacement")), "EMBEDDED")
                .toUpperCase(Locale.ROOT);
        String runtimeRole = runtimeRole(placement, runtimeTypes, metadata);
        String status = firstText(asString(item.get("status")), "UNKNOWN").toUpperCase(Locale.ROOT);
        boolean available = "ONLINE".equals(status);
        Map<String, Object> policy = asMap(item.get("governancePolicy"));
        String projectCode = asString(item.get("projectCode"));
        String instanceId = asString(item.get("instanceId"));
        boolean supportsGraph = bool(firstValue(item, metadata, "supportsGraph"));
        boolean supportsTools = bool(firstValue(item, metadata, "supportsTools"));
        boolean embedded = bool(firstValue(item, metadata, "supportsEmbeddedExecution")) || "EMBEDDED".equals(placement);
        boolean hybrid = bool(firstValue(item, metadata, "supportsHybridExecution")) || "HYBRID".equals(placement);
        return new RuntimeRegistryEntry(
                "instance:" + projectCode + ":" + instanceId,
                "PROJECT_INSTANCE",
                runtimeRole,
                runtimeTypes.get(0),
                displayName(projectCode, asString(item.get("host")), runtimeRole),
                description(runtimeRole),
                placement,
                status,
                available,
                available ? null : "Runtime instance status is " + status,
                supportsGraph,
                supportsTools,
                bool(firstValue(item, metadata, "supportsAutonomous")),
                bool(firstValue(item, metadata, "supportsWorkflow")) || supportsGraph,
                embedded,
                hybrid,
                projectCode,
                instanceId,
                asString(item.get("baseUrl")),
                asString(item.get("host")),
                integer(item.get("port")),
                asString(item.get("appVersion")),
                asString(item.get("sdkVersion")),
                localDateTime(item.get("lastHeartbeatAt")),
                bool(policy.get("disabled")),
                asString(policy.get("minSdkVersion")),
                booleanObject(policy.get("allowEmbeddedExecution")),
                booleanObject(policy.get("allowHybridExecution")),
                firstText(asString(policy.get("message")), "ok"),
                runtimeTypes,
                metadata);
    }

    private Map<String, Object> metadata(Map<String, Object> item) {
        Map<String, Object> metadata = parseMap(asString(item.get("metadataJson")));
        if (!metadata.isEmpty()) {
            return metadata;
        }
        Map<String, Object> derived = new LinkedHashMap<>();
        for (String key : List.of(
                "runtimePlacement",
                "runtimeTypes",
                "supportsGraph",
                "supportsTools",
                "supportsAutonomous",
                "supportsWorkflow",
                "supportsEmbeddedExecution",
                "supportsHybridExecution")) {
            if (item.containsKey(key)) {
                derived.put(key, item.get(key));
            }
        }
        return derived;
    }

    private Object firstValue(Map<String, Object> item, Map<String, Object> metadata, String key) {
        return item.containsKey(key) ? item.get(key) : metadata.get(key);
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

    private String displayName(String projectCode, String host, String runtimeRole) {
        if ("CAPABILITY_HOST".equals(runtimeRole)) {
            return "Capability Host";
        }
        return firstText(projectCode, "") + " / " + firstText(host, "");
    }

    private String description(String runtimeRole) {
        if ("CAPABILITY_HOST".equals(runtimeRole)) {
            return "Capability Host exposed by a business system through the ReachAI JDK8 SDK";
        }
        return "Agent Runtime instance reported by a business system SDK heartbeat";
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::asString)
                    .filter(item -> item != null && !item.isBlank())
                    .collect(Collectors.toList());
        }
        String single = asString(value);
        return single == null || single.isBlank() ? List.of() : List.of(single);
    }

    private boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(asString(value));
    }

    private Boolean booleanObject(Object value) {
        if (value == null) {
            return null;
        }
        return bool(value);
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = asString(value);
        return text == null || text.isBlank() ? null : Integer.parseInt(text);
    }

    private LocalDateTime localDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        String text = asString(value);
        return text == null || text.isBlank() ? null : LocalDateTime.parse(text);
    }

    private String asString(Object value) {
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

    private boolean textEquals(String left, String right) {
        return left != null && right != null && left.trim().equals(right.trim());
    }
}
