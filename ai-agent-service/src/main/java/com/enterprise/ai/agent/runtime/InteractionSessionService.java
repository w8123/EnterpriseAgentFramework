package com.enterprise.ai.agent.runtime;

import com.enterprise.ai.agent.capability.InteractionEventEntity;
import com.enterprise.ai.agent.capability.InteractionEventMapper;
import com.enterprise.ai.agent.capability.InteractionSessionEntity;
import com.enterprise.ai.agent.capability.InteractionSessionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class InteractionSessionService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final InteractionSessionMapper sessionMapper;
    private final InteractionEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    public InteractionSessionService(InteractionSessionMapper sessionMapper,
                                     InteractionEventMapper eventMapper,
                                     ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.eventMapper = eventMapper;
        this.objectMapper = objectMapper;
    }

    public InteractionSessionEntity createWaitingSession(String compositionQualifiedName,
                                                         String nodeId,
                                                         String interactionType,
                                                         Map<String, Object> state,
                                                         Map<String, Object> uiRequest) {
        LocalDateTime now = LocalDateTime.now();
        InteractionSessionEntity session = new InteractionSessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setRunId(String.valueOf(state.getOrDefault("runId", UUID.randomUUID().toString())));
        session.setCompositionQualifiedName(compositionQualifiedName);
        session.setNodeId(nodeId);
        session.setInteractionType(interactionType);
        session.setStatus("WAITING_USER");
        session.setStateJson(writeJson(state));
        session.setUiRequestJson(writeJson(uiRequest));
        session.setCreateTime(now);
        session.setUpdateTime(now);
        session.setExpiresAt(now.plusHours(24));
        sessionMapper.insert(session);
        writeEvent(session.getId(), "WAITING_USER", uiRequest);
        return session;
    }

    public Optional<InteractionSessionEntity> findSession(String sessionId) {
        return Optional.ofNullable(sessionMapper.selectById(sessionId));
    }

    public Map<String, Object> readState(InteractionSessionEntity session) {
        return readMap(session.getStateJson());
    }

    public void markSubmitted(String sessionId, Map<String, Object> submittedPayload) {
        InteractionSessionEntity update = new InteractionSessionEntity();
        update.setId(sessionId);
        update.setStatus("SUBMITTED");
        update.setSubmittedPayloadJson(writeJson(submittedPayload));
        update.setUpdateTime(LocalDateTime.now());
        sessionMapper.updateById(update);
        writeEvent(sessionId, "SUBMITTED", submittedPayload);
    }

    public void markCompleted(String sessionId, Object output) {
        InteractionSessionEntity update = new InteractionSessionEntity();
        update.setId(sessionId);
        update.setStatus("COMPLETED");
        update.setUpdateTime(LocalDateTime.now());
        sessionMapper.updateById(update);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("output", output);
        writeEvent(sessionId, "COMPLETED", payload);
    }

    private void writeEvent(String sessionId, String eventType, Object payload) {
        if (eventMapper == null) {
            return;
        }
        InteractionEventEntity event = new InteractionEventEntity();
        event.setSessionId(sessionId);
        event.setEventType(eventType);
        event.setPayloadJson(writeJson(payload));
        event.setCreateTime(LocalDateTime.now());
        eventMapper.insert(event);
    }

    private Map<String, Object> readMap(String json) {
        try {
            if (json == null || json.isBlank()) {
                return new java.util.LinkedHashMap<>();
            }
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
}
