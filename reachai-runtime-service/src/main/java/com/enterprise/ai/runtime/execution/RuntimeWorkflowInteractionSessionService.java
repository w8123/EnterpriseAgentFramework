package com.enterprise.ai.runtime.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.execution.checkpoint.WorkflowCheckpointCodec;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;
import com.enterprise.ai.runtime.trace.RuntimeWorkflowInteractionTraceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Creates and loads GraphSpec-native Workflow interaction sessions for production Agent/Embed.
 */
@Service
public class RuntimeWorkflowInteractionSessionService {

    static final java.util.List<String> RESUME_SOURCE_TYPES = java.util.List.of("WORKFLOW", "DEBUG");

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RuntimeInteractionSessionMapper sessionMapper;
    private final RuntimeInteractionEventMapper eventMapper;
    private final ObjectMapper objectMapper;
    private final WorkflowCheckpointCodec checkpointCodec;
    private final int resumeTimeoutSeconds;
    private final RuntimeWorkflowInteractionTraceService interactionTrace;

    public RuntimeWorkflowInteractionSessionService(RuntimeInteractionSessionMapper sessionMapper,
                                                     RuntimeInteractionEventMapper eventMapper,
                                                     ObjectMapper objectMapper) {
        this(sessionMapper, eventMapper, objectMapper,
                WorkflowCheckpointCodec.DEFAULT_MAX_CHECKPOINT_BYTES);
    }

    public RuntimeWorkflowInteractionSessionService(
            RuntimeInteractionSessionMapper sessionMapper,
            RuntimeInteractionEventMapper eventMapper,
            ObjectMapper objectMapper,
            int maxCheckpointBytes) {
        this(sessionMapper, eventMapper, objectMapper, maxCheckpointBytes, 900);
    }

    public RuntimeWorkflowInteractionSessionService(
            RuntimeInteractionSessionMapper sessionMapper,
            RuntimeInteractionEventMapper eventMapper,
            ObjectMapper objectMapper,
            @Value("${reachai.runtime.workflow-checkpoint.max-bytes:262144}") int maxCheckpointBytes,
            @Value("${reachai.runtime.interaction-expiry.resume-timeout-seconds:900}") int resumeTimeoutSeconds) {
        this(sessionMapper, eventMapper, objectMapper, maxCheckpointBytes, resumeTimeoutSeconds, null);
    }

    public RuntimeWorkflowInteractionSessionService(RuntimeInteractionSessionMapper sessionMapper,
            RuntimeInteractionEventMapper eventMapper, ObjectMapper objectMapper,
            RuntimeWorkflowInteractionTraceService interactionTrace) {
        this(sessionMapper, eventMapper, objectMapper, WorkflowCheckpointCodec.DEFAULT_MAX_CHECKPOINT_BYTES, 900, interactionTrace);
    }

    @Autowired
    public RuntimeWorkflowInteractionSessionService(RuntimeInteractionSessionMapper sessionMapper,
            RuntimeInteractionEventMapper eventMapper, ObjectMapper objectMapper,
            @Value("${reachai.runtime.workflow-checkpoint.max-bytes:262144}") int maxCheckpointBytes,
            @Value("${reachai.runtime.interaction-expiry.resume-timeout-seconds:900}") int resumeTimeoutSeconds,
            RuntimeWorkflowInteractionTraceService interactionTrace) {
        if (resumeTimeoutSeconds <= 0) throw new IllegalArgumentException("Workflow resume timeout must be positive");
        this.sessionMapper = sessionMapper;
        this.eventMapper = eventMapper;
        this.objectMapper = objectMapper;
        this.checkpointCodec = new WorkflowCheckpointCodec(objectMapper, maxCheckpointBytes);
        this.resumeTimeoutSeconds = resumeTimeoutSeconds;
        this.interactionTrace = interactionTrace;
    }

    @Transactional
    public WaitingSession createWaitingSession(CreateRequest request) {
        RuntimeInteractionSessionEntity session = createWaitingSessionEntity(request);
        return new WaitingSession(session.getId(), session.getUiRequestJson());
    }

    /** Only the persisted interaction identifier and UI cross the execution-module boundary. */
    public record WaitingSession(String interactionId, String uiRequestJson) { }

    private RuntimeInteractionSessionEntity createWaitingSessionEntity(CreateRequest request) {
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
        entity.setGraphSpecSnapshotJson(request.graphSpecSnapshotJson());
        entity.setNodeId(request.nodeId());
        entity.setInteractionType(firstText(request.interactionType(), "COLLECT_INPUT"));
        entity.setStatus("WAITING_USER");
        entity.setRevision(0);
        WorkflowCheckpointCodec.EncodedCheckpoint checkpoint = encodeCheckpoint(
                request.resumeCheckpoint(), request.graphSpecSnapshotJson(), request.nodeId());
        applyCheckpoint(entity, checkpoint);
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

    /**
     * Atomically consumes the current wait and persists the next durable wait. No UI event is
     * returned to the caller until both rows and their audit events commit.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RuntimeInteractionSessionEntity completeAndCreateNext(
            RuntimeInteractionSessionEntity current,
            Map<String, Object> result,
            String operatorId,
            CreateRequest nextRequest) {
        UpdateWrapper<RuntimeInteractionSessionEntity> update = resumeUpdate(current, "COMPLETED")
                .set("result_json", writeResumeResult(result));
        requireResumeUpdate(sessionMapper.update(null, update));
        writeEvent(current.getId(), "RESUMED", Map.of("next", "WAITING_USER"), operatorId);
        writeEvent(current.getId(), "COMPLETED", Map.of(
                "nextInteractionId", nextRequest.interactionId()), operatorId);
        RuntimeInteractionSessionEntity next = createWaitingSessionEntity(nextRequest);
        recordResumeTrace(current, "WAITING_USER", result);
        return next;
    }

    /** The claim and its audit event commit before any Workflow execution is allowed to start. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimResume(RuntimeInteractionSessionEntity current, String idempotencyKey,
                               Map<String, Object> submission, String operatorId) {
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<RuntimeInteractionSessionEntity> update = new UpdateWrapper<>();
        update.eq("id", current.getId()).eq("status", "WAITING_USER").eq("revision", revision(current))
                .in("source_type", RESUME_SOURCE_TYPES)
                .and(expiry -> expiry.isNull("expires_at").or().gt("expires_at", now))
                .set("status", "RESUMING").set("revision", revision(current) + 1)
                .set("resume_deadline_at", now.plusSeconds(resumeTimeoutSeconds))
                .set("idempotency_key", idempotencyKey)
                .set("submitted_payload_json", writeJson(submission)).set("result_json", null)
                .set("update_time", now);
        if (sessionMapper.update(null, update) != 1) return false;
        Map<?, ?> values = submission.get("values") instanceof Map<?, ?> supplied ? supplied : Map.of();
        writeEvent(current.getId(), "SUBMITTED", Map.of("action", submission.get("action"), "keys", values.keySet()), operatorId);
        return true;
    }

    /** A stale worker must not overwrite another attempt or commit only part of its evidence. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishResume(RuntimeInteractionSessionEntity current, String status,
                              Map<String, Object> result, String operatorId, String... eventTypes) {
        if (!java.util.Set.of("COMPLETED", "FAILED", "CANCELLED").contains(status)) {
            throw new IllegalArgumentException("A Workflow resume must finish with a terminal status");
        }
        requireResumeUpdate(sessionMapper.update(null,
                resumeUpdate(current, status).set("result_json", writeResumeResult(result))));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        if (result.get("code") != null) payload.put("code", result.get("code"));
        if ("CANCELLED".equals(status)) payload.put("action", "cancel");
        for (String eventType : eventTypes) {
            writeEvent(current.getId(), eventType, payload, operatorId);
        }
        recordResumeTrace(current, status, result);
    }

    private void recordResumeTrace(RuntimeInteractionSessionEntity current, String status, Map<String, Object> result) {
        if (interactionTrace == null || !"WORKFLOW".equals(current.getSourceType())) return;
        Map<String, Object> metadata = result.get("metadata") instanceof Map<?, ?> raw
                ? objectMapper.convertValue(raw, MAP_TYPE) : Map.of();
        interactionTrace.record(new RuntimeWorkflowInteractionTraceService.Receipt(current.getTraceId(), current.getId(),
                current.getNodeId(), current.getWorkflowId(), current.getWorkflowVersionId(), current.getTenantId(),
                current.getAppId(), status, result.get("code") == null ? null : String.valueOf(result.get("code")),
                metadata, LocalDateTime.now()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void returnToWaiting(RuntimeInteractionSessionEntity current, Map<String, Object> checkpoint,
                                 Object uiRequest, String operatorId, String graphSpecJson) {
        WorkflowCheckpointCodec.EncodedCheckpoint encoded = encodeCheckpoint(checkpoint, graphSpecJson, current.getNodeId());
        UpdateWrapper<RuntimeInteractionSessionEntity> update = resumeUpdate(current, "WAITING_USER")
                .set("resume_deadline_at", null)
                .set("idempotency_key", null).set("submitted_payload_json", null).set("result_json", null)
                .set("resume_checkpoint_json", encoded.json()).set("checkpoint_schema_version", encoded.schemaVersion())
                .set("execution_engine_version", encoded.engineVersion()).set("checkpoint_digest", encoded.digest())
                .set("checkpoint_size_bytes", encoded.sizeBytes()).set("ui_request_json", writeJson(uiRequest));
        requireResumeUpdate(sessionMapper.update(null, update));
        writeEvent(current.getId(), "REQUESTED", Map.of("reason", "validation_failed", "interactionId", current.getId()), operatorId);
    }

    private UpdateWrapper<RuntimeInteractionSessionEntity> resumeUpdate(RuntimeInteractionSessionEntity current, String status) {
        if (!"RESUMING".equals(current.getStatus())) throw new ResumeConflictException();
        LocalDateTime now = LocalDateTime.now();
        return new UpdateWrapper<RuntimeInteractionSessionEntity>()
                .eq("id", current.getId()).eq("status", "RESUMING").eq("revision", revision(current))
                .in("source_type", RESUME_SOURCE_TYPES).gt("resume_deadline_at", now)
                .set("status", status).set("revision", revision(current) + 1).set("update_time", now);
    }

    private int revision(RuntimeInteractionSessionEntity session) { return session.getRevision() == null ? 0 : session.getRevision(); }

    private String writeResumeResult(Map<String, Object> response) {
        return writeJson(Map.of("resumeResultSchemaVersion", 1, "response", response));
    }

    private void requireResumeUpdate(int rows) { if (rows != 1) throw new ResumeConflictException(); }

    public static final class ResumeConflictException extends IllegalStateException {
        public ResumeConflictException() { super("Workflow interaction resume revision changed"); }
    }

    public WorkflowCheckpointCodec.EncodedCheckpoint encodeCheckpoint(Map<String, Object> state,
                                                                       String graphSpecJson,
                                                                       String suspendedNodeId) {
        return checkpointCodec.encode(state, graphSpecJson, suspendedNodeId);
    }

    public WorkflowCheckpointCodec.DecodedCheckpoint decodeCheckpoint(
            RuntimeInteractionSessionEntity session,
            String graphSpecJson) {
        if (session == null) {
            throw new IllegalArgumentException("interaction session is required");
        }
        return checkpointCodec.decode(
                session.getResumeCheckpointJson(),
                session.getCheckpointSchemaVersion(),
                session.getExecutionEngineVersion(),
                session.getCheckpointDigest(),
                session.getCheckpointSizeBytes(),
                graphSpecJson,
                session.getNodeId());
    }

    public void applyCheckpoint(RuntimeInteractionSessionEntity entity,
                                WorkflowCheckpointCodec.EncodedCheckpoint checkpoint) {
        entity.setResumeCheckpointJson(checkpoint.json());
        entity.setCheckpointSchemaVersion(checkpoint.schemaVersion());
        entity.setExecutionEngineVersion(checkpoint.engineVersion());
        entity.setCheckpointDigest(checkpoint.digest());
        entity.setCheckpointSizeBytes(checkpoint.sizeBytes());
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
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

    /** 取消同一 Trace 下仍在等待的会话；状态和事件在同一事务内提交。 */
    @Transactional
    public boolean cancelWaiting(Cancellation command) {
        if (!StringUtils.hasText(command.interactionId()) || !StringUtils.hasText(command.traceId())) {
            return false;
        }
        if (!StringUtils.hasText(command.code()) || !StringUtils.hasText(command.reason())) {
            throw new IllegalArgumentException("Interaction cancellation requires a code and reason");
        }
        LocalDateTime cancelledAt = Objects.requireNonNull(command.cancelledAt(), "cancelledAt");
        String interactionId = command.interactionId().trim();
        int updated = sessionMapper.update(null, Wrappers.<RuntimeInteractionSessionEntity>lambdaUpdate()
                .eq(RuntimeInteractionSessionEntity::getId, interactionId)
                .eq(RuntimeInteractionSessionEntity::getTraceId, command.traceId())
                .eq(RuntimeInteractionSessionEntity::getStatus, "WAITING_USER")
                .set(RuntimeInteractionSessionEntity::getStatus, "CANCELLED")
                .setSql("revision = COALESCE(revision, 0) + 1")
                .set(RuntimeInteractionSessionEntity::getResultJson, writeJson(Map.of(
                        "code", command.code(), "status", "CANCELLED", "reason", command.reason())))
                .set(RuntimeInteractionSessionEntity::getUpdateTime, cancelledAt));
        if (updated != 1) {
            return false;
        }
        writeEvent(interactionId, "CANCELLED", Map.of(
                "code", command.code(), "reason", command.reason(), "traceId", command.traceId()),
                command.operatorId(), cancelledAt);
        return true;
    }

    public record Cancellation(String interactionId, String traceId, String code, String reason,
                               String operatorId, LocalDateTime cancelledAt) { }

    public void writeEvent(String sessionId, String eventType, Object payload, String operatorId) {
        writeEvent(sessionId, eventType, payload, operatorId, LocalDateTime.now());
    }

    private void writeEvent(String sessionId, String eventType, Object payload, String operatorId,
                            LocalDateTime createdAt) {
        RuntimeInteractionEventEntity event = new RuntimeInteractionEventEntity();
        event.setSessionId(sessionId);
        event.setEventType(eventType);
        event.setPayloadJson(writeJson(redact(payload)));
        event.setOperatorId(operatorId);
        event.setCreateTime(createdAt);
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
