package com.enterprise.ai.agent.runtime.host;


import com.enterprise.ai.agent.runtime.*;
import com.enterprise.ai.agent.capability.CapabilityAssetService;
import com.enterprise.ai.agent.capability.InteractionDefinitionEntity;
import com.enterprise.ai.agent.capability.InteractionSessionEntity;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class InteractionRuntimeExecutor {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CapabilityAssetService assetService;
    private final InteractionSessionService sessionService;
    private final ObjectMapper objectMapper;

    public InteractionRuntimeExecutor(CapabilityAssetService assetService,
                                      InteractionSessionService sessionService,
                                      ObjectMapper objectMapper) {
        this.assetService = assetService;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> execute(GraphSpec.Node node,
                                       Map<String, Object> state,
                                       CapabilityRuntimeRequest request,
                                       String compositionQualifiedName,
                                       Map<String, Object> metadata) {
        Map<String, Object> spec = resolveSpec(node);
        String interactionType = normalize(firstNonBlank(asString(spec.get("interactionType")), asString(spec.get("type"))));
        return switch (interactionType) {
            case "PRESENT_OUTPUT" -> presentOutput(node, spec, state);
            case "USER_CHOICE" -> collectInput(node, spec, state, request, compositionQualifiedName, metadata, "CHOICE");
            case "CONFIRM_ACTION" -> collectInput(node, spec, state, request, compositionQualifiedName, metadata, "CONFIRM");
            case "COLLECT_INPUT" -> collectInput(node, spec, state, request, compositionQualifiedName, metadata, "FORM");
            default -> throw new IllegalArgumentException("unsupported interaction type: " + interactionType);
        };
    }

    public Map<String, Object> stateFromSession(InteractionSessionEntity session) {
        return sessionService.readState(session);
    }

    public InteractionSessionEntity requireWaitingSession(String sessionId) {
        InteractionSessionEntity session = sessionService.findSession(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("interaction session not found: " + sessionId));
        if (!"WAITING_USER".equalsIgnoreCase(session.getStatus())) {
            throw new IllegalArgumentException("interaction session is not waiting: " + sessionId);
        }
        return session;
    }

    public void markSubmitted(String sessionId, Map<String, Object> submittedPayload) {
        sessionService.markSubmitted(sessionId, submittedPayload == null ? Map.of() : submittedPayload);
    }

    public void markCompleted(String sessionId, Object output) {
        sessionService.markCompleted(sessionId, output);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> collectInput(GraphSpec.Node node,
                                             Map<String, Object> spec,
                                             Map<String, Object> state,
                                             CapabilityRuntimeRequest request,
                                             String compositionQualifiedName,
                                             Map<String, Object> metadata,
                                             String component) {
        Map<String, Object> incoming = new LinkedHashMap<>(safeMap(state.get("params")));
        incoming.putAll(safeMap(state.get("submittedPayload")));
        incoming.putAll(request == null || request.params() == null ? Map.of() : request.params());

        List<Map<String, Object>> fields = fields(spec);
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            String key = fieldKey(field);
            if (key.isBlank()) {
                continue;
            }
            Object value = incoming.get(key);
            if (Boolean.TRUE.equals(field.get("required")) && isBlank(value)) {
                missing.add(key);
            }
            if (!isBlank(value)) {
                values.put(key, value);
            }
        }
        incoming.forEach(values::putIfAbsent);
        if (!missing.isEmpty()) {
            Map<String, Object> uiRequest = uiRequest(node, spec, component, fields, values, missing);
            InteractionSessionEntity session = sessionService.createWaitingSession(
                    compositionQualifiedName,
                    node.getId(),
                    normalize(asString(spec.getOrDefault("interactionType", "COLLECT_INPUT"))),
                    state,
                    uiRequest);
            throw InteractionSuspendException.waiting(compositionQualifiedName, session.getId(), node.getId(), uiRequest, metadata);
        }
        Map<String, Object> update = baseSuccess(values);
        update.put("params", values);
        writeAlias(node, update, values);
        return update;
    }

    private Map<String, Object> presentOutput(GraphSpec.Node node, Map<String, Object> spec, Map<String, Object> state) {
        Object data = resolveExpression(asString(spec.getOrDefault("data", "lastOutput")), state);
        if (data == null) {
            data = state.get("lastOutput");
        }
        Map<String, Object> uiRequest = new LinkedHashMap<>();
        uiRequest.put("type", "PRESENT_OUTPUT");
        uiRequest.put("component", asString(spec.getOrDefault("component", "DETAIL")).toUpperCase(Locale.ROOT));
        uiRequest.put("title", firstNonBlank(asString(spec.get("title")), node.getName()));
        uiRequest.put("data", data);
        uiRequest.put("schema", safeMap(spec.get("schema")));
        uiRequest.put("actions", spec.getOrDefault("actions", List.of()));
        Map<String, Object> update = baseSuccess(data);
        update.put("uiRequest", uiRequest);
        writeAlias(node, update, data);
        return update;
    }

    private Map<String, Object> uiRequest(GraphSpec.Node node,
                                          Map<String, Object> spec,
                                          String component,
                                          List<Map<String, Object>> fields,
                                          Map<String, Object> values,
                                          List<String> missing) {
        Map<String, Object> ui = safeMap(spec.get("ui"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", normalize(asString(spec.getOrDefault("interactionType", "COLLECT_INPUT"))));
        out.put("component", component);
        out.put("interactionId", node.getId());
        out.put("title", firstNonBlank(asString(spec.get("title")), asString(ui.get("title"))));
        out.put("fields", fields);
        out.put("prefilled", values);
        out.put("missing", missing);
        out.put("datasources", spec.getOrDefault("datasources", Map.of()));
        out.put("behavior", spec.getOrDefault("behavior", Map.of()));
        return out;
    }

    private Map<String, Object> resolveSpec(GraphSpec.Node node) {
        Map<String, Object> config = new LinkedHashMap<>(safeMap(node.getConfig()));
        String qualifiedName = node.getRef() == null ? "" : firstNonBlank(node.getRef().getQualifiedName(), node.getRef().getName());
        if (!qualifiedName.isBlank()) {
            assetService.findInteractionByQualifiedName(qualifiedName)
                    .filter(def -> Boolean.TRUE.equals(def.getEnabled()))
                    .map(this::readSpec)
                    .ifPresent(config::putAll);
        }
        config.putIfAbsent("interactionType", "COLLECT_INPUT");
        return config;
    }

    private Map<String, Object> readSpec(InteractionDefinitionEntity definition) {
        Map<String, Object> spec = readMap(definition.getSpecJson());
        spec.putIfAbsent("interactionType", definition.getInteractionType());
        spec.putIfAbsent("title", definition.getName());
        return spec;
    }

    private Map<String, Object> readMap(String json) {
        try {
            if (json == null || json.isBlank()) {
                return new LinkedHashMap<>();
            }
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            throw new IllegalArgumentException("interaction spec json parse failed: " + ex.getMessage(), ex);
        }
    }

    private List<Map<String, Object>> fields(Map<String, Object> spec) {
        Object raw = spec.get("fields");
        if (!(raw instanceof List<?>)) {
            raw = safeMap(spec.get("ui")).get("fields");
        }
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> field = new LinkedHashMap<>(safeMap(item));
            String key = fieldKey(field);
            if (!key.isBlank()) {
                field.putIfAbsent("key", key);
                field.putIfAbsent("name", key);
                field.putIfAbsent("type", "string");
                field.putIfAbsent("label", key);
                field.putIfAbsent("required", false);
                out.add(field);
            }
        }
        return out;
    }

    private Object resolveExpression(String expression, Map<String, Object> state) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        Object current = state;
        for (String part : expression.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> safeMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static Map<String, Object> baseSuccess(Object output) {
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("lastOutput", output);
        update.put("lastSuccess", true);
        update.put("lastError", "");
        return update;
    }

    private static void writeAlias(GraphSpec.Node node, Map<String, Object> update, Object value) {
        String alias = asString(safeMap(node.getConfig()).get("outputAlias"));
        if (!alias.isBlank()) {
            update.put(alias, value);
        }
    }

    private static String fieldKey(Map<String, Object> field) {
        return firstNonBlank(asString(field.get("key")), asString(field.get("name")));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean isBlank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }
}
