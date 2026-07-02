package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RuntimeCompositionExecutionService {

    private final RuntimeCapabilityCatalogClient capabilityClient;
    private final RuntimeGraphSpecExecutor graphSpecExecutor;

    public Map<String, Object> execute(String qualifiedName, Map<String, Object> request) {
        if (!StringUtils.hasText(qualifiedName)) {
            return failure("RUNTIME_COMPOSITION_NAME_REQUIRED", "Composition qualifiedName is required", qualifiedName);
        }
        Map<String, Object> composition;
        try {
            composition = capabilityClient.getCompositionDefinition(qualifiedName.trim());
        } catch (Exception ex) {
            return failure("RUNTIME_COMPOSITION_NOT_FOUND",
                    "Composition definition not found: " + qualifiedName.trim(), qualifiedName.trim());
        }
        if (composition == null || composition.isEmpty()) {
            return failure("RUNTIME_COMPOSITION_NOT_FOUND",
                    "Composition definition not found: " + qualifiedName.trim(), qualifiedName.trim());
        }
        if (isFalse(composition.get("enabled"))) {
            return failure("RUNTIME_COMPOSITION_DISABLED",
                    "Composition definition is disabled: " + qualifiedName.trim(), qualifiedName.trim());
        }
        String graphSpecJson = text(composition.get("graphSpecJson"));
        if (!StringUtils.hasText(graphSpecJson)) {
            return failure("RUNTIME_COMPOSITION_GRAPH_MISSING",
                    "Composition GraphSpec JSON is missing: " + qualifiedName.trim(), qualifiedName.trim());
        }

        RuntimeGraphSpecExecutionResult result =
                graphSpecExecutor.execute(graphSpecJson, normalizeRequest(request));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", result.success());
        body.put("code", result.code());
        body.put("answer", result.answer());
        body.put("nodeId", result.nodeId());
        body.put("nodeType", result.nodeType());
        body.put("steps", result.steps());
        body.put("metadata", result.metadata());
        body.put("composition", Map.of(
                "qualifiedName", String.valueOf(composition.getOrDefault("qualifiedName", qualifiedName.trim())),
                "capabilityCode", String.valueOf(composition.getOrDefault("capabilityCode", "")),
                "compositionCode", String.valueOf(composition.getOrDefault("compositionCode", ""))));
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeRequest(Map<String, Object> request) {
        if (request == null || request.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>(request);
        Object params = request.get("params");
        if (params instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    normalized.putIfAbsent(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        Object input = request.get("input");
        if (input instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    normalized.putIfAbsent(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        return normalized;
    }

    private Map<String, Object> failure(String code, String answer, String qualifiedName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("answer", answer);
        body.put("qualifiedName", qualifiedName);
        return body;
    }

    private boolean isFalse(Object value) {
        return Boolean.FALSE.equals(value) || "false".equalsIgnoreCase(text(value));
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
    }
}
