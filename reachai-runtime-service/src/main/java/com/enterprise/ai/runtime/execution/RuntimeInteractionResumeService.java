package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RuntimeInteractionResumeService {

    private static final String WAITING_USER = "WAITING_USER";
    private static final String SUBMITTED = "SUBMITTED";
    private static final String COMPLETED = "COMPLETED";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RuntimeInteractionSessionMapper sessionMapper;
    private final RuntimeInteractionEventMapper eventMapper;
    private final RuntimeCapabilityCatalogClient capabilityClient;
    private final RuntimeGraphSpecExecutor graphSpecExecutor;
    private final ObjectMapper objectMapper;

    public Map<String, Object> resume(String sessionId, Map<String, Object> request) {
        if (!StringUtils.hasText(sessionId)) {
            return failure("RUNTIME_INTERACTION_SESSION_REQUIRED", "Interaction sessionId is required", sessionId);
        }
        RuntimeInteractionSessionEntity session = sessionMapper.selectById(sessionId.trim());
        if (session == null) {
            return failure("RUNTIME_INTERACTION_NOT_FOUND", "interaction session not found: " + sessionId.trim(),
                    sessionId.trim());
        }
        if (!WAITING_USER.equalsIgnoreCase(text(session.getStatus()))) {
            return failure("RUNTIME_INTERACTION_NOT_WAITING", "interaction session is not waiting: " + sessionId.trim(),
                    sessionId.trim());
        }

        Map<String, Object> submittedPayload = submittedPayload(request);
        String operatorId = text(request == null ? null : request.get("operatorId"));
        markSubmitted(session.getId(), submittedPayload, operatorId);

        Map<String, Object> composition = composition(session.getCompositionQualifiedName());
        String graphSpecJson = text(composition.get("graphSpecJson"));
        if (!StringUtils.hasText(graphSpecJson)) {
            return failure("RUNTIME_INTERACTION_GRAPH_MISSING",
                    "Composition GraphSpec JSON is missing: " + session.getCompositionQualifiedName(),
                    session.getId());
        }

        Map<String, Object> context = new LinkedHashMap<>(readMap(session.getStateJson()));
        context.putAll(safeMap(request == null ? null : request.get("context")));
        context.put("submittedPayload", submittedPayload);
        context.put("values", submittedPayload);
        context.putAll(submittedPayload);

        RuntimeGraphSpecExecutionResult result =
                graphSpecExecutor.executeFromNode(graphSpecJson, context, session.getNodeId());
        if (result.success()) {
            markCompleted(session.getId(), result.answer(), operatorId);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", result.success());
        body.put("code", result.code());
        body.put("answer", result.answer());
        body.put("nodeId", result.nodeId());
        body.put("nodeType", result.nodeType());
        body.put("steps", result.steps());
        body.put("metadata", result.metadata());
        body.put("interactionSessionId", session.getId());
        body.put("compositionQualifiedName", session.getCompositionQualifiedName());
        return body;
    }

    private Map<String, Object> composition(String qualifiedName) {
        try {
            Map<String, Object> composition = capabilityClient.getCompositionDefinition(qualifiedName);
            return composition == null ? Map.of() : composition;
        } catch (Exception ex) {
            throw new IllegalArgumentException("composition not found: " + qualifiedName, ex);
        }
    }

    private void markSubmitted(String sessionId, Map<String, Object> submittedPayload, String operatorId) {
        RuntimeInteractionSessionEntity update = new RuntimeInteractionSessionEntity();
        update.setId(sessionId);
        update.setStatus(SUBMITTED);
        update.setSubmittedPayloadJson(writeJson(submittedPayload));
        update.setUpdateTime(LocalDateTime.now());
        sessionMapper.updateById(update);
        writeEvent(sessionId, SUBMITTED, submittedPayload, operatorId);
    }

    private void markCompleted(String sessionId, Object output, String operatorId) {
        RuntimeInteractionSessionEntity update = new RuntimeInteractionSessionEntity();
        update.setId(sessionId);
        update.setStatus(COMPLETED);
        update.setUpdateTime(LocalDateTime.now());
        sessionMapper.updateById(update);
        writeEvent(sessionId, COMPLETED, Map.of("output", output), operatorId);
    }

    private void writeEvent(String sessionId, String eventType, Object payload, String operatorId) {
        RuntimeInteractionEventEntity event = new RuntimeInteractionEventEntity();
        event.setSessionId(sessionId);
        event.setEventType(eventType);
        event.setPayloadJson(writeJson(payload));
        event.setOperatorId(operatorId);
        event.setCreateTime(LocalDateTime.now());
        eventMapper.insert(event);
    }

    private Map<String, Object> submittedPayload(Map<String, Object> request) {
        if (request == null || request.isEmpty()) {
            return Map.of();
        }
        Object raw = firstPresent(request.get("values"), firstPresent(request.get("submittedPayload"), request.get("payload")));
        Map<String, Object> map = safeMap(raw);
        if (!map.isEmpty()) {
            return map;
        }
        Map<String, Object> fallback = new LinkedHashMap<>(request);
        fallback.remove("operatorId");
        fallback.remove("context");
        return fallback;
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            throw new IllegalArgumentException("interaction session json parse failed: " + ex.getMessage(), ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("interaction session json serialization failed: " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private Object firstPresent(Object first, Object fallback) {
        return first != null ? first : fallback;
    }

    private Map<String, Object> failure(String code, String answer, String sessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("answer", answer);
        body.put("interactionSessionId", sessionId);
        return body;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
    }
}
