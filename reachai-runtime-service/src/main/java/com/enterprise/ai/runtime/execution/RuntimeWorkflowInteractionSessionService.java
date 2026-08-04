package com.enterprise.ai.runtime.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Creates and loads GraphSpec-native Workflow interaction sessions for production Agent/Embed.
 */
@Service
@RequiredArgsConstructor
public class RuntimeWorkflowInteractionSessionService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RuntimeInteractionSessionMapper sessionMapper;
    private final RuntimeInteractionEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    public RuntimeInteractionSessionEntity createWaitingSession(CreateRequest request) {
        String interactionId = firstText(request.interactionId(),
                WorkflowInteractionCodes.ID_PREFIX + java.util.UUID.randomUUID().toString().replace("-", ""));
        LocalDateTime now = LocalDateTime.now();
        int ttlSeconds = request.ttlSeconds() == null || request.ttlSeconds() <= 0 ? 3600 : request.ttlSeconds();

        RuntimeInteractionSessionEntity entity = new RuntimeInteractionSessionEntity();
        entity.setId(interactionId);
        entity.setSourceType(firstText(request.sourceType(), "WORKFLOW"));
        entity.setRunId(request.runId());
        entity.setTraceId(request.traceId());
        entity.setWorkflowId(request.workflowId());
        entity.setWorkflowVersionId(request.workflowVersionId());
        entity.setCompositionQualifiedName(request.compositionQualifiedName());
        entity.setGraphSpecSnapshotJson(request.graphSpecSnapshotJson());
        entity.setNodeId(request.nodeId());
        entity.setInteractionType(firstText(request.interactionType(), "COLLECT_INPUT"));
        entity.setStatus("WAITING_USER");
        entity.setRevision(0);
        entity.setResumeCheckpointJson(writeJson(
                request.resumeCheckpoint() == null ? Map.of() : request.resumeCheckpoint()));
        entity.setUiRequestJson(writeJson(request.uiRequest()));
        entity.setContinuationJson(writeJson(request.continuation()));
        entity.setAppId(request.appId());
        entity.setTenantId(request.tenantId());
        entity.setSessionId(request.sessionId());
        entity.setUserId(request.userId());
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        entity.setExpiresAt(now.plus(ttlSeconds, ChronoUnit.SECONDS));
        sessionMapper.insert(entity);
        writeEvent(interactionId, "CREATED", Map.of(
                "nodeId", nullToEmpty(request.nodeId()),
                "workflowId", nullToEmpty(request.workflowId()),
                "traceId", nullToEmpty(request.traceId())), request.userId());
        writeEvent(interactionId, "REQUESTED", Map.of(
                "interactionId", interactionId,
                "component", uiComponent(request.uiRequest())), request.userId());
        return entity;
    }

    public RuntimeInteractionSessionEntity requireById(String interactionId) {
        if (!StringUtils.hasText(interactionId)) {
            throw new IllegalArgumentException("interactionId is required");
        }
        RuntimeInteractionSessionEntity session = sessionMapper.selectById(interactionId.trim());
        if (session == null) {
            throw new IllegalArgumentException("interaction session not found: " + interactionId.trim());
        }
        return session;
    }

    public RuntimeInteractionSessionEntity findWaitingByChatSession(String chatSessionId) {
        if (!StringUtils.hasText(chatSessionId)) {
            return null;
        }
        return sessionMapper.selectOne(new LambdaQueryWrapper<RuntimeInteractionSessionEntity>()
                .eq(RuntimeInteractionSessionEntity::getSessionId, chatSessionId.trim())
                .eq(RuntimeInteractionSessionEntity::getStatus, "WAITING_USER")
                .orderByDesc(RuntimeInteractionSessionEntity::getUpdateTime)
                .last("LIMIT 1"));
    }

    public Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            throw new IllegalArgumentException("interaction session json parse failed: " + ex.getMessage(), ex);
        }
    }

    public String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("interaction session json serialization failed: " + ex.getMessage(), ex);
        }
    }

    public void writeEvent(String sessionId, String eventType, Object payload, String operatorId) {
        RuntimeInteractionEventEntity event = new RuntimeInteractionEventEntity();
        event.setSessionId(sessionId);
        event.setEventType(eventType);
        event.setPayloadJson(writeJson(redact(payload)));
        event.setOperatorId(operatorId);
        event.setCreateTime(LocalDateTime.now());
        eventMapper.insert(event);
    }

    @SuppressWarnings("unchecked")
    private Object redact(Object payload) {
        if (!(payload instanceof Map<?, ?> map)) {
            return payload;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key = String.valueOf(entry.getKey()).toLowerCase();
            if (key.contains("password") || key.contains("secret") || key.contains("token")
                    || key.contains("apikey") || key.contains("authorization")) {
                copy.put(String.valueOf(entry.getKey()), "***");
            } else if (entry.getValue() instanceof Map<?, ?> nested) {
                copy.put(String.valueOf(entry.getKey()), redact(nested));
            } else {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return copy;
    }

    private String uiComponent(Object uiRequest) {
        if (uiRequest instanceof Map<?, ?> map && map.get("component") != null) {
            return String.valueOf(map.get("component"));
        }
        return "";
    }

    private static String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record CreateRequest(
            String interactionId,
            String sourceType,
            String runId,
            String traceId,
            String workflowId,
            Long workflowVersionId,
            String compositionQualifiedName,
            String graphSpecSnapshotJson,
            String nodeId,
            String interactionType,
            Map<String, Object> resumeCheckpoint,
            Object uiRequest,
            Map<String, Object> continuation,
            String appId,
            String tenantId,
            String sessionId,
            String userId,
            Integer ttlSeconds
    ) {
    }
}
