package com.enterprise.ai.runtime.execution;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.execution.RuntimeSupervisorApprovalPort.PolicyApprovalGrant;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Execution owns durable, scoped Supervisor approval claims, receipts and safe pending projections. */
@Service
public class RuntimeSupervisorApprovalService implements RuntimeSupervisorApprovalPort {
    public static final String SOURCE_TYPE = "SUPERVISOR_POLICY";
    public static final String INTERACTION_TYPE = "CONFIRM_ACTION";
    public static final String WAITING_USER = "WAITING_USER";
    public static final String RESUMING = "RESUMING";
    public static final String COMPLETED = "COMPLETED";
    public static final String EXPIRED = "EXPIRED";
    public static final String RESUME_TIMEOUT = "SUPERVISOR_APPROVAL_RESUME_TIMEOUT";
    public static final String RESUME_TIMEOUT_MESSAGE = "确认后的执行超过等待时限，业务结果未知，请核对业务记录；系统不会自动重试";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final RuntimeInteractionSessionMapper sessionMapper;
    private final RuntimeInteractionEventMapper eventMapper;
    private final ObjectMapper objectMapper;
    private final RuntimeInteractionExpiryTracePort tracePort;
    private final int resumeTimeoutSeconds;

    public RuntimeSupervisorApprovalService(RuntimeInteractionSessionMapper sessionMapper,
            RuntimeInteractionEventMapper eventMapper, ObjectMapper objectMapper,
            RuntimeInteractionExpiryTracePort tracePort,
            @Value("${reachai.runtime.interaction-expiry.resume-timeout-seconds:900}") int resumeTimeoutSeconds) {
        if (resumeTimeoutSeconds <= 0) throw new IllegalArgumentException("Supervisor resume timeout must be positive");
        this.sessionMapper = sessionMapper;
        this.eventMapper = eventMapper;
        this.objectMapper = objectMapper;
        this.tracePort = Objects.requireNonNull(tracePort);
        this.resumeTimeoutSeconds = resumeTimeoutSeconds;
    }

    @Transactional
    public void create(Create request) {
        Objects.requireNonNull(request, "request");
        if (!StringUtils.hasText(request.interactionId()) || !StringUtils.hasText(request.agentId())
                || !StringUtils.hasText(request.traceId()) || !StringUtils.hasText(request.nodeId())) {
            throw new IllegalArgumentException("Supervisor approval identity is required");
        }
        configVersion(readMap(request.checkpointJson()));
        var row = new RuntimeInteractionSessionEntity();
        row.setId(request.interactionId());
        row.setSourceType(SOURCE_TYPE);
        row.setAgentId(request.agentId());
        row.setTraceId(request.traceId());
        row.setNodeId(request.nodeId());
        row.setInteractionType(INTERACTION_TYPE);
        row.setStatus(WAITING_USER);
        row.setRevision(0);
        row.setResumeCheckpointJson(request.checkpointJson());
        row.setUiRequestJson(request.uiRequestJson());
        row.setContinuationJson(request.continuationJson());
        row.setSessionId(request.sessionId());
        row.setUserId(request.userId());
        row.setTenantId(request.tenantId());
        row.setCreateTime(request.createdAt());
        row.setUpdateTime(request.createdAt());
        row.setExpiresAt(request.expiresAt());
        sessionMapper.insert(row);
        writeEvent(row.getId(), "CREATED", Map.of("toolName", row.getNodeId(),
                "traceId", row.getTraceId(), "agentId", row.getAgentId()), row.getUserId());
        writeEvent(row.getId(), "REQUESTED", Map.of("interactionId", row.getId(), "component", "confirm"), row.getUserId());
    }

    public List<PendingApproval> pending(String agentId, String userId, int limit) {
        var query = new QueryWrapper<RuntimeInteractionSessionEntity>()
                .select("id", "trace_id", "session_id", "user_id", "agent_id", "node_id", "status",
                        "create_time", "update_time", "expires_at", "ui_request_json", "continuation_json")
                .eq("source_type", SOURCE_TYPE).eq("interaction_type", INTERACTION_TYPE).eq("status", WAITING_USER)
                .and(open -> open.isNull("expires_at").or().gt("expires_at", LocalDateTime.now()));
        if (StringUtils.hasText(agentId)) query.eq("agent_id", agentId.trim());
        if (StringUtils.hasText(userId)) query.eq("user_id", userId.trim());
        query.orderByDesc("create_time").orderByDesc("id").last("LIMIT " + Math.max(1, Math.min(limit, 200)));
        return sessionMapper.selectList(query).stream().map(row -> new PendingApproval(
                row.getId(), row.getTraceId(), row.getSessionId(), row.getUserId(), row.getAgentId(), row.getNodeId(),
                row.getStatus(), row.getCreateTime(), row.getUpdateTime(), row.getExpiresAt(), row.getUiRequestJson(),
                publicState(row.getContinuationJson(), row.getAgentId()))).toList();
    }

    private String publicState(String continuationJson, String agentId) {
        Map<String, Object> continuation;
        try { continuation = readMap(continuationJson); }
        catch (IllegalArgumentException invalid) { continuation = Map.of(); }
        Map<String, Object> state = new LinkedHashMap<>();
        for (String key : List.of("agentConfigVersionId", "toolKind", "toolName", "permissionKey", "riskLevel", "resumeVia")) {
            Object value = continuation.get(key);
            if (value instanceof String || value instanceof Number || value instanceof Boolean) state.put(key, value);
        }
        state.put("agentId", agentId);
        return writeJson(state);
    }

    private void requireOwner(RuntimeInteractionSessionEntity row, Map<String, Object> checkpoint, WorkflowExecutionIdentity identity) {
        if (identity == null || !identity.userTrusted() || !identity.projectTrusted()) {
            throw new IllegalArgumentException("Supervisor approval requires a trusted user");
        }
        if (!Objects.equals(row.getTenantId(), identity.getTenantId())) {
            throw new IllegalArgumentException("Supervisor approval tenant mismatch");
        }
        if (!Objects.equals(row.getAgentId(), text(checkpoint.get("agentId")))) {
            throw new IllegalArgumentException("Supervisor approval Agent mismatch");
        }
        String project = text(checkpoint.get("projectCode"));
        // The signed Control gateway attests tenant/user before an Agent project is resolved.
        // This durable approval supplies the target; prepareResume still requires its exact
        // tenant, user and session. If a caller already carries a project scope, enforce it.
        // Never fill either project field from the submitted business input.
        if (StringUtils.hasText(identity.getProjectCode()) && !Objects.equals(project, identity.getProjectCode())) {
            throw new IllegalArgumentException("Supervisor approval project mismatch");
        }
        Object projectId = checkpoint.get("projectId");
        if (identity.getProjectId() != null
                && (!(projectId instanceof Number number) || !Objects.equals(number.longValue(), identity.getProjectId()))) {
            throw new IllegalArgumentException("Supervisor approval project mismatch");
        }
    }

    private Long configVersion(Map<String, Object> checkpoint) {
        Object value = checkpoint.get("agentConfigVersionId");
        if (!(value instanceof Number number) || number.longValue() < 1
                || number.doubleValue() != number.longValue()) {
            throw new IllegalArgumentException("Supervisor approval requires its pinned Agent configuration");
        }
        return number.longValue();
    }

    private UpdateWrapper<RuntimeInteractionSessionEntity> scoped(RuntimeInteractionSessionEntity row) {
        var update = new UpdateWrapper<RuntimeInteractionSessionEntity>()
                .eq("source_type", SOURCE_TYPE).eq("interaction_type", INTERACTION_TYPE);
        nullableEquals(update, "agent_id", row.getAgentId());
        nullableEquals(update, "trace_id", row.getTraceId());
        nullableEquals(update, "node_id", row.getNodeId());
        nullableEquals(update, "tenant_id", row.getTenantId());
        nullableEquals(update, "user_id", row.getUserId());
        nullableEquals(update, "session_id", row.getSessionId());
        return update;
    }

    private void nullableEquals(UpdateWrapper<RuntimeInteractionSessionEntity> update, String column, String value) {
        if (value == null) update.isNull(column); else update.eq(column, value);
    }

    public static final class ApprovalExpiredException extends IllegalArgumentException {
        public ApprovalExpiredException(String id) { super("Supervisor approval expired: " + id); }
    }

    public record Create(String interactionId, String agentId, String traceId, String nodeId, String sessionId,
                         String userId, String tenantId, String checkpointJson, String uiRequestJson,
                         String continuationJson, LocalDateTime createdAt, LocalDateTime expiresAt) { }
    public record PendingApproval(String id, String traceId, String sessionId, String userId, String agentId,
                                  String nodeId, String status, LocalDateTime createTime, LocalDateTime updateTime,
                                  LocalDateTime expiresAt, String uiRequestJson, String publicStateJson) { }

    @Override
    @Transactional(noRollbackFor = ApprovalExpiredException.class)
    public ResumeDecision prepareResume(String interactionId,
                                        Map<String, Object> submission,
                                        WorkflowExecutionIdentity trustedIdentity) {
        RuntimeInteractionSessionEntity row = requireApproval(interactionId);
        Map<String, Object> checkpoint = readMap(row.getResumeCheckpointJson());
        requireOwner(row, checkpoint, trustedIdentity);
        configVersion(checkpoint);
        Map<String, Object> uiSubmit = mapValue(submission == null ? null : submission.get("uiSubmit"));
        Map<String, Object> values = mapValue(uiSubmit.get("values"));
        boolean confirmed = requireDecision(text(uiSubmit.get("action")), values.get("confirm"));

        String submittedSessionId = text(submission == null ? null : submission.get("sessionId"));
        if (StringUtils.hasText(row.getSessionId())
                && !row.getSessionId().equals(submittedSessionId)) {
            throw new IllegalArgumentException("Supervisor approval session mismatch");
        }
        String actorUserId = trustedUserId(trustedIdentity);
        // A non-human/anonymous request has no transferable user approval. Authentication
        // of a later caller must never turn an ownerless session into that caller's grant.
        if (!StringUtils.hasText(row.getUserId()) || !row.getUserId().equals(actorUserId)) {
            throw new IllegalArgumentException("Supervisor approval user mismatch");
        }
        actorUserId = firstText(actorUserId, row.getUserId(), "anonymous");

        String idempotencyKey = firstText(
                text(submission == null ? null : submission.get("idempotencyKey")),
                interactionId + ":" + (confirmed ? "approved" : "rejected"));
        Map<String, Object> submittedPayload = new LinkedHashMap<>();
        submittedPayload.put("action", confirmed ? "confirm" : "reject");
        submittedPayload.put("values", Map.of("confirm", confirmed));

        if (!WAITING_USER.equalsIgnoreCase(row.getStatus())) {
            return resumeExisting(row, confirmed, idempotencyKey, submittedPayload);
        }
        if (row.getExpiresAt() != null && row.getExpiresAt().isBefore(LocalDateTime.now())) {
            expire(row);
            throw new ApprovalExpiredException(interactionId.trim());
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime resumeDeadline = now.plusSeconds(resumeTimeoutSeconds);
        Map<String, Object> decisionState = new LinkedHashMap<>(checkpoint);
        decisionState.put("phase", RESUMING);
        decisionState.put("decision", confirmed ? "approved" : "rejected");
        decisionState.put("submittedAt", now.toString());
        decisionState.put("submittedBy", actorUserId);

        int revision = row.getRevision() == null ? 0 : row.getRevision();
        UpdateWrapper<RuntimeInteractionSessionEntity> update = scoped(row);
        update.eq("id", row.getId())
                .eq("status", WAITING_USER)
                .eq("revision", revision)
                .set("status", RESUMING)
                .set("resume_deadline_at", resumeDeadline)
                .set("revision", revision + 1)
                .set("idempotency_key", idempotencyKey)
                .set("resume_checkpoint_json", writeJson(decisionState))
                .set("submitted_payload_json", writeJson(redact(submittedPayload)))
                .set("update_time", now);
        if (sessionMapper.update(null, update) != 1) {
            RuntimeInteractionSessionEntity latest = requireApproval(interactionId);
            return resumeExisting(latest, confirmed, idempotencyKey, submittedPayload);
        }
        row.setStatus(RESUMING);
        row.setResumeDeadlineAt(resumeDeadline);
        row.setRevision(revision + 1);
        row.setIdempotencyKey(idempotencyKey);
        row.setResumeCheckpointJson(writeJson(decisionState));
        row.setSubmittedPayloadJson(writeJson(redact(submittedPayload)));
        row.setUpdateTime(now);
        writeEvent(row.getId(), "SUBMITTED", Map.of(
                "decision", confirmed ? "approved" : "rejected",
                "toolName", firstText(text(checkpoint.get("toolName")), row.getNodeId())),
                actorUserId);
        writeEvent(row.getId(), RESUMING, Map.of(
                "decision", confirmed ? "approved" : "rejected", "resumeDeadlineAt", resumeDeadline.toString()),
                actorUserId);

        return toDecision(row, decisionState, confirmed, submittedSessionId, actorUserId,
                idempotencyKey, submittedPayload);
    }

    @Override
    @Transactional
    public Map<String, Object> completeResume(String interactionId,
                                              String idempotencyKey,
                                              Map<String, Object> submittedPayload,
                                              Map<String, Object> result) {
        RuntimeInteractionSessionEntity row = requireApproval(interactionId);
        assertSameAttempt(row, idempotencyKey, submittedPayload);
        expireResume(row, LocalDateTime.now());
        if (COMPLETED.equalsIgnoreCase(row.getStatus()) || isResumeTimeout(row)) {
            return replayResult(row);
        }
        if (!RESUMING.equalsIgnoreCase(row.getStatus())) {
            throw new IllegalArgumentException("Supervisor approval is not resuming: " + row.getId());
        }

        LocalDateTime now = LocalDateTime.now();
        int revision = row.getRevision() == null ? 0 : row.getRevision();
        Map<String, Object> storedResult = result == null ? Map.of() : new LinkedHashMap<>(result);
        UpdateWrapper<RuntimeInteractionSessionEntity> update = scoped(row);
        update.eq("id", row.getId())
                .eq("status", RESUMING)
                .eq("revision", revision)
                .eq("idempotency_key", idempotencyKey)
                .gt("resume_deadline_at", now)
                .set("status", COMPLETED)
                .set("revision", revision + 1)
                .set("result_json", writeJson(storedResult))
                .set("update_time", now);
        if (sessionMapper.update(null, update) != 1) {
            RuntimeInteractionSessionEntity latest = requireApproval(interactionId);
            assertSameAttempt(latest, idempotencyKey, submittedPayload);
            expireResume(latest, LocalDateTime.now());
            if (COMPLETED.equalsIgnoreCase(latest.getStatus()) || isResumeTimeout(latest)) {
                return replayResult(latest);
            }
            throw new IllegalStateException("Supervisor approval completion lost its state claim: " + interactionId);
        }
        row.setStatus(COMPLETED);
        row.setRevision(revision + 1);
        row.setResultJson(writeJson(storedResult));
        row.setUpdateTime(now);
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("success", storedResult.get("success"));
        eventPayload.put("code", mapValue(storedResult.get("metadata")).get("code"));
        writeEvent(row.getId(), COMPLETED, eventPayload, firstText(row.getUserId(), "runtime"));
        return storedResult;
    }

    private ResumeDecision resumeExisting(RuntimeInteractionSessionEntity row,
                                          boolean confirmed,
                                          String idempotencyKey,
                                          Map<String, Object> submittedPayload) {
        if (!RESUMING.equalsIgnoreCase(row.getStatus()) && !COMPLETED.equalsIgnoreCase(row.getStatus()) && !isResumeTimeout(row)) {
            throw new IllegalArgumentException("Supervisor approval is already closed: " + row.getId());
        }
        assertSameAttempt(row, idempotencyKey, submittedPayload);
        expireResume(row, LocalDateTime.now());
        Map<String, Object> checkpoint = readMap(row.getResumeCheckpointJson());
        String storedDecision = text(checkpoint.get("decision"));
        if (("approved".equalsIgnoreCase(storedDecision)) != confirmed) {
            throw new IllegalArgumentException("Supervisor approval idempotency conflict: " + row.getId());
        }
        Map<String, Object> replay = COMPLETED.equalsIgnoreCase(row.getStatus()) || isResumeTimeout(row)
                ? replayResult(row)
                : resumingResult(row);
        return new ResumeDecision(false, false, agentId(row, checkpoint), configVersion(checkpoint), Map.of(), null,
                "", idempotencyKey, submittedPayload, replay);
    }

    /** The owning claim, receipt, event and trace close together; expiry never grants another execution. */
    @Transactional
    public boolean expireResume(RuntimeInteractionSessionEntity row, LocalDateTime now) {
        if (row == null || !SOURCE_TYPE.equals(row.getSourceType()) || !INTERACTION_TYPE.equals(row.getInteractionType())
                || !RESUMING.equals(row.getStatus()) || row.getResumeDeadlineAt() == null
                || row.getResumeDeadlineAt().isAfter(now)) return false;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("code", RESUME_TIMEOUT);
        metadata.put("interactionId", row.getId());
        metadata.put("agentId", row.getAgentId());
        metadata.put("traceId", row.getTraceId());
        metadata.put("interactionPending", false);
        metadata.put("outcome", "UNKNOWN");
        metadata.put("retryable", false);
        metadata.put("reconciliationRequired", true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("answer", RESUME_TIMEOUT_MESSAGE);
        result.put("sessionId", row.getSessionId());
        result.put("metadata", metadata);
        String resultJson = writeJson(result);
        int revision = row.getRevision() == null ? 0 : row.getRevision();
        int updated = sessionMapper.update(null, scoped(row).eq("id", row.getId()).eq("status", RESUMING)
                .eq("revision", revision).le("resume_deadline_at", now)
                .set("status", EXPIRED).set("revision", revision + 1)
                .set("result_json", resultJson).set("update_time", now));
        if (updated != 1) return false;
        writeEvent(row.getId(), EXPIRED, Map.of("reason", "resume_deadline", "code", RESUME_TIMEOUT,
                "outcome", "UNKNOWN", "resumeDeadlineAt", row.getResumeDeadlineAt().toString()), "runtime-expiry-reconciler");
        tracePort.expireSupervisorApprovalResume(row.getTraceId(), row.getId(), now);
        row.setStatus(EXPIRED);
        row.setRevision(revision + 1);
        row.setResultJson(resultJson);
        row.setUpdateTime(now);
        return true;
    }

    private boolean isResumeTimeout(RuntimeInteractionSessionEntity row) {
        return EXPIRED.equals(row.getStatus()) && row.getResultJson() != null
                && RESUME_TIMEOUT.equals(mapValue(readMap(row.getResultJson()).get("metadata")).get("code"));
    }

    private ResumeDecision toDecision(RuntimeInteractionSessionEntity row,
                                      Map<String, Object> state,
                                      boolean confirmed,
                                      String submittedSessionId,
                                      String submittedUserId,
                                      String idempotencyKey,
                                      Map<String, Object> submittedPayload) {
        Map<String, Object> originalInput = mapValue(state.get("input"));
        if (StringUtils.hasText(row.getTraceId())) {
            originalInput.put("traceId", row.getTraceId());
        }
        if (!confirmed) {
            return new ResumeDecision(false, true, agentId(row, state), configVersion(state), originalInput, null,
                    "用户已拒绝执行该 Workflow Tool", idempotencyKey, submittedPayload, null);
        }
        if (StringUtils.hasText(submittedSessionId)) originalInput.put("sessionId", submittedSessionId);
        String permissionKey = text(state.get("permissionKey"));
        PolicyApprovalGrant grant = new PolicyApprovalGrant(
                row.getId(), permissionKey, text(state.get("toolName")), mapValue(state.get("args")),
                firstText(submittedUserId, row.getUserId(), "anonymous"));
        return new ResumeDecision(true, false, agentId(row, state), configVersion(state), originalInput, grant,
                "审批已通过，继续执行原请求", idempotencyKey, submittedPayload, null);
    }

    private RuntimeInteractionSessionEntity requireApproval(String interactionId) {
        if (!StringUtils.hasText(interactionId)) {
            throw new IllegalArgumentException("interactionId is required");
        }
        RuntimeInteractionSessionEntity row = sessionMapper.selectForUpdate(interactionId.trim());
        if (row == null || !SOURCE_TYPE.equalsIgnoreCase(row.getSourceType())
                || !INTERACTION_TYPE.equalsIgnoreCase(row.getInteractionType())) {
            throw new IllegalArgumentException("Supervisor approval not found: " + interactionId.trim());
        }
        return row;
    }

    private boolean requireDecision(String action, Object confirmValue) {
        Boolean actionDecision = null;
        if (StringUtils.hasText(action)) {
            if ("confirm".equalsIgnoreCase(action) || "approve".equalsIgnoreCase(action)
                    || "submit".equalsIgnoreCase(action)) {
                actionDecision = true;
            } else if ("reject".equalsIgnoreCase(action) || "cancel".equalsIgnoreCase(action)) {
                actionDecision = false;
            } else {
                throw new IllegalArgumentException("Unsupported Supervisor approval action: " + action);
            }
        }
        Boolean valueDecision = null;
        if (confirmValue instanceof Boolean value) {
            valueDecision = value;
        } else if (confirmValue != null && StringUtils.hasText(String.valueOf(confirmValue))) {
            String normalized = String.valueOf(confirmValue).trim();
            if ("true".equalsIgnoreCase(normalized) || "false".equalsIgnoreCase(normalized)) {
                valueDecision = Boolean.parseBoolean(normalized);
            } else {
                throw new IllegalArgumentException("Supervisor approval confirm value must be boolean");
            }
        }
        if (actionDecision != null && valueDecision != null && !actionDecision.equals(valueDecision)) {
            throw new IllegalArgumentException("Supervisor approval action conflicts with confirm value");
        }
        Boolean decision = actionDecision != null ? actionDecision : valueDecision;
        if (decision == null) {
            throw new IllegalArgumentException("Supervisor approval requires an explicit confirm or reject action");
        }
        return decision;
    }

    private void assertSameAttempt(RuntimeInteractionSessionEntity row,
                                   String idempotencyKey,
                                   Map<String, Object> submittedPayload) {
        if (!StringUtils.hasText(idempotencyKey) || !idempotencyKey.equals(row.getIdempotencyKey())
                || !payloadMatches(row.getSubmittedPayloadJson(), submittedPayload)) {
            throw new IllegalArgumentException("Supervisor approval idempotency conflict: " + row.getId());
        }
    }

    private boolean payloadMatches(String storedJson, Map<String, Object> submittedPayload) {
        if (!StringUtils.hasText(storedJson)) {
            return false;
        }
        try {
            return objectMapper.readTree(storedJson)
                    .equals(objectMapper.valueToTree(redact(submittedPayload)));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid Supervisor approval submitted payload", ex);
        }
    }

    private Map<String, Object> replayResult(RuntimeInteractionSessionEntity row) {
        Map<String, Object> result = readMap(row.getResultJson());
        if (!result.containsKey("success")) {
            throw new IllegalArgumentException("Supervisor approval completed without a replayable result: " + row.getId());
        }
        Map<String, Object> metadata = mapValue(result.get("metadata"));
        metadata.put("interactionId", row.getId());
        metadata.put("idempotentReplay", true);
        result.put("metadata", metadata);
        return result;
    }

    private Map<String, Object> resumingResult(RuntimeInteractionSessionEntity row) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("code", "SUPERVISOR_APPROVAL_RESUMING");
        metadata.put("interactionId", row.getId());
        metadata.put("interactionPending", true);
        metadata.put("retryable", true);
        return new LinkedHashMap<>(Map.of(
                "success", false,
                "answer", "审批恢复正在处理中，请勿重复提交",
                "metadata", metadata));
    }

    private void expire(RuntimeInteractionSessionEntity row) {
        LocalDateTime now = LocalDateTime.now();
        int revision = row.getRevision() == null ? 0 : row.getRevision();
        UpdateWrapper<RuntimeInteractionSessionEntity> update = scoped(row);
        update.eq("id", row.getId())
                .eq("status", WAITING_USER)
                .eq("revision", revision)
                .set("status", EXPIRED)
                .set("revision", revision + 1)
                .set("update_time", now);
        if (sessionMapper.update(null, update) == 1) {
            row.setStatus(EXPIRED);
            row.setRevision(revision + 1);
            row.setUpdateTime(now);
            writeEvent(row.getId(), EXPIRED, Map.of("reason", "ttl"), "runtime");
        } else {
            throw new IllegalStateException("Supervisor approval expiry lost its state claim: " + row.getId());
        }
    }

    private String agentId(RuntimeInteractionSessionEntity row, Map<String, Object> state) {
        return firstText(row.getAgentId(), text(state.get("agentId")));
    }

    private String trustedUserId(WorkflowExecutionIdentity identity) {
        return identity != null && identity.userTrusted() ? firstText(identity.userId()) : null;
    }

    private void writeEvent(String sessionId, String eventType, Object payload, String operatorId) {
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
            String key = String.valueOf(entry.getKey());
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.contains("password") || lower.contains("secret") || lower.contains("token")
                    || lower.contains("apikey") || lower.contains("api_key") || lower.contains("authorization")
                    || lower.contains("credential") || lower.contains("cookie")) {
                copy.put(key, "[已隐藏]");
            } else if (entry.getValue() instanceof Map<?, ?> nested) {
                copy.put(key, redact(nested));
            } else {
                copy.put(key, entry.getValue());
            }
        }
        return copy;
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) return new LinkedHashMap<>();
        try {
            return new LinkedHashMap<>(objectMapper.readValue(json, MAP_TYPE));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid Supervisor approval state", ex);
        }
    }

    private Map<String, Object> mapValue(Object value) {
        if (value == null) return new LinkedHashMap<>();
        return new LinkedHashMap<>(objectMapper.convertValue(value, MAP_TYPE));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Supervisor approval JSON serialization failed", ex);
        }
    }

    private String text(Map<String, Object> source, String key) {
        return source == null ? null : text(source.get(key));
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String firstText(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return null;
    }

    public record ResumeDecision(boolean approved, boolean rejected, String agentId, Long agentConfigVersionId,
                                 Map<String, Object> originalInput, PolicyApprovalGrant grant, String message,
                                 String idempotencyKey, Map<String, Object> submittedPayload,
                                 Map<String, Object> replayResult) implements RuntimeSupervisorApprovalPort.ResumeDecision { }
}
