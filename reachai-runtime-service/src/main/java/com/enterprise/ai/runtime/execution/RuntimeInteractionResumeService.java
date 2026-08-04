package com.enterprise.ai.runtime.execution;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resumes GraphSpec-native Workflow interaction sessions.
 * Prefer snapshot on the session; compositionQualifiedName is legacy fallback only.
 */
@Service
@RequiredArgsConstructor
public class RuntimeInteractionResumeService {

    private static final String WAITING_USER = "WAITING_USER";
    private static final String RESUMING = "RESUMING";
    private static final String COMPLETED = "COMPLETED";
    private static final String CANCELLED = "CANCELLED";
    private static final String EXPIRED = "EXPIRED";
    private static final String FAILED = "FAILED";

    private final RuntimeInteractionSessionMapper sessionMapper;
    private final RuntimeWorkflowInteractionSessionService sessionService;
    private final RuntimeCapabilityCatalogClient capabilityClient;
    private final RuntimeGraphSpecExecutor graphSpecExecutor;
    private final ObjectMapper objectMapper;
    private final RuntimeInteractionExpiryProcessor expiryProcessor;

    public Map<String, Object> resume(String sessionId, Map<String, Object> request) {
        return resume(sessionId, request, null);
    }

    public Map<String, Object> resume(String sessionId,
                                      Map<String, Object> request,
                                      Ownership ownership) {
        if (!StringUtils.hasText(sessionId)) {
            return failure("RUNTIME_INTERACTION_SESSION_REQUIRED", "Interaction sessionId is required", sessionId);
        }
        RuntimeInteractionSessionEntity session;
        try {
            session = sessionService.requireById(sessionId.trim());
        } catch (IllegalArgumentException ex) {
            return failure("RUNTIME_INTERACTION_NOT_FOUND", ex.getMessage(), sessionId.trim());
        }

        if (requiresOwnership(session)) {
            if (ownership == null || !ownershipMatches(session, ownership)) {
                return failure("RUNTIME_INTERACTION_FORBIDDEN",
                        "interaction does not belong to the current session/user", session.getId());
            }
        } else if (ownership != null && !ownershipMatches(session, ownership)) {
            return failure("RUNTIME_INTERACTION_FORBIDDEN",
                    "interaction does not belong to the current session/user", session.getId());
        }

        if (session.getExpiresAt() != null && !session.getExpiresAt().isAfter(LocalDateTime.now())) {
            if (!expiryProcessor.expireOne(session, LocalDateTime.now())) {
                return failure("RUNTIME_INTERACTION_CONFLICT",
                        "interaction session changed while expiry was being reconciled", session.getId());
            }
            return failure("RUNTIME_INTERACTION_EXPIRED", "interaction session expired: " + session.getId(),
                    session.getId());
        }

        if (CANCELLED.equalsIgnoreCase(text(session.getStatus()))) {
            return failure("RUNTIME_INTERACTION_CANCELLED", "interaction session cancelled: " + session.getId(),
                    session.getId());
        }

        Map<String, Object> submittedPayload = submittedPayload(request);
        String action = firstText(text(request == null ? null : request.get("action")),
                text(submittedPayload.get("action")), "submit");
        String idempotencyKey = firstText(
                text(request == null ? null : request.get("idempotencyKey")),
                text(request == null ? null : request.get("idempotency_key")));
        String operatorId = firstText(ownershipUser(ownership),
                text(request == null ? null : request.get("operatorId")));

        if ("cancel".equalsIgnoreCase(action)) {
            if (!claimResuming(session, idempotencyKey, submittedPayload, operatorId)) {
                return conflictOrReplay(session, idempotencyKey, submittedPayload);
            }
            markStatus(session, CANCELLED, submittedPayload, Map.of("cancelled", true), idempotencyKey);
            sessionService.writeEvent(session.getId(), CANCELLED, Map.of("action", "cancel"), operatorId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("code", "RUNTIME_GRAPH_CANCELLED");
            body.put("answer", "Interaction cancelled");
            body.put("interactionSessionId", session.getId());
            body.put("interactionId", session.getId());
            body.put("status", CANCELLED);
            putIdentity(body, session);
            return body;
        }

        if (!WAITING_USER.equalsIgnoreCase(text(session.getStatus()))) {
            if (idempotencyKey != null && idempotencyKey.equals(session.getIdempotencyKey())
                    && payloadMatches(session.getSubmittedPayloadJson(), submittedPayload)) {
                return replayResult(session);
            }
            return failure("RUNTIME_INTERACTION_NOT_WAITING",
                    "interaction session is not waiting: " + sessionId.trim(), sessionId.trim());
        }

        if (StringUtils.hasText(idempotencyKey)
                && idempotencyKey.equals(session.getIdempotencyKey())
                && payloadMatches(session.getSubmittedPayloadJson(), submittedPayload)) {
            return replayResult(session);
        }
        if (StringUtils.hasText(session.getIdempotencyKey())
                && StringUtils.hasText(idempotencyKey)
                && !idempotencyKey.equals(session.getIdempotencyKey())) {
            // allow new key only while waiting; different key with different payload is a new attempt
        } else if (StringUtils.hasText(session.getIdempotencyKey())
                && payloadMatches(session.getSubmittedPayloadJson(), submittedPayload) == false
                && StringUtils.hasText(idempotencyKey)
                && idempotencyKey.equals(session.getIdempotencyKey())) {
            return failure("RUNTIME_INTERACTION_CONFLICT",
                    "duplicate submit with different payload for the same idempotency key",
                    session.getId());
        }

        if (!claimResuming(session, idempotencyKey, submittedPayload, operatorId)) {
            return conflictOrReplay(session, idempotencyKey, submittedPayload);
        }

        String graphSpecJson = resolveGraphSpecJson(session);
        if (!StringUtils.hasText(graphSpecJson)) {
            markStatus(session, FAILED, submittedPayload, Map.of("error", "GRAPH_MISSING"), idempotencyKey);
            return failure("RUNTIME_INTERACTION_GRAPH_MISSING",
                    "Interaction GraphSpec snapshot is missing: " + session.getId(), session.getId());
        }

        Map<String, Object> resumeCheckpoint = new LinkedHashMap<>(
                sessionService.readMap(session.getResumeCheckpointJson()));
        resumeCheckpoint.putAll(safeMap(request == null ? null : request.get("context")));
        resumeCheckpoint.put("runId", firstText(session.getRunId(), text(resumeCheckpoint.get("runId"))));
        resumeCheckpoint.put("traceId", firstText(session.getTraceId(), text(resumeCheckpoint.get("traceId"))));
        resumeCheckpoint.put("workflowId", firstText(session.getWorkflowId(), text(resumeCheckpoint.get("workflowId"))));
        if (session.getWorkflowVersionId() != null) {
            resumeCheckpoint.put("workflowVersionId", session.getWorkflowVersionId());
        }
        resumeCheckpoint.put(WorkflowInteractionCodes.PENDING_INTERACTION_ID_KEY, session.getId());
        resumeCheckpoint.put(WorkflowInteractionCodes.PENDING_INTERACTION_NODE_KEY, session.getNodeId());
        resumeCheckpoint.put(WorkflowInteractionCodes.RESUME_CONTEXT_KEY, Map.of(
                "interactionId", session.getId(),
                "nodeId", session.getNodeId(),
                "action", action,
                "values", submittedPayload,
                "idempotencyKey", idempotencyKey == null ? "" : idempotencyKey));
        // Never leave global submittedPayload for the next INTERACTION.
        resumeCheckpoint.remove("submittedPayload");

        RuntimeGraphSpecExecutionResult result =
                graphSpecExecutor.executeFromNode(graphSpecJson, resumeCheckpoint, session.getNodeId());
        Map<String, Object> nextResumeCheckpoint = result.resumeCheckpoint() == null || result.resumeCheckpoint().isEmpty()
                ? resumeCheckpoint
                : new LinkedHashMap<>(result.resumeCheckpoint());
        nextResumeCheckpoint.remove("submittedPayload");
        nextResumeCheckpoint.remove(WorkflowInteractionCodes.RESUME_CONTEXT_KEY);

        if (result.isWaitingUser()) {
            Object uiRequest = result.uiRequest();
            boolean sameInteraction = session.getId().equals(result.interactionId())
                    && session.getNodeId() != null
                    && session.getNodeId().equals(result.nodeId());
            if (sameInteraction) {
                // 校验失败：回滚 WAITING_USER，不消费幂等键，不新建 session
                rollbackToWaiting(session, nextResumeCheckpoint, uiRequest, operatorId);
                Map<String, Object> body = successBody(result, session, WAITING_USER, uiRequest);
                body.put("success", false);
                body.put("validationFailed", true);
                return body;
            }

            String nextInteractionId = firstText(result.interactionId(),
                    WorkflowInteractionCodes.ID_PREFIX + java.util.UUID.randomUUID().toString().replace("-", ""));
            markStatus(session, COMPLETED, submittedPayload, Map.of(
                    "nextInteractionId", nextInteractionId,
                    "code", result.code()), idempotencyKey);
            sessionService.writeEvent(session.getId(), "RESUMED", Map.of("next", "WAITING_USER"), operatorId);
            sessionService.writeEvent(session.getId(), COMPLETED, Map.of("nextInteractionId", nextInteractionId),
                    operatorId);

            RuntimeInteractionSessionEntity next = sessionService.createWaitingSession(
                    new RuntimeWorkflowInteractionSessionService.CreateRequest(
                            nextInteractionId,
                            session.getSourceType(),
                            session.getRunId(),
                            session.getTraceId(),
                            session.getWorkflowId(),
                            session.getWorkflowVersionId(),
                            session.getCompositionQualifiedName(),
                            graphSpecJson,
                            result.nodeId(),
                            interactionType(result),
                            nextResumeCheckpoint,
                            uiRequest,
                            sessionService.readMap(session.getContinuationJson()),
                            session.getAppId(),
                            session.getTenantId(),
                            session.getSessionId(),
                            session.getUserId(),
                            ttlSeconds(uiRequest)));
            return successBody(result, next, next.getStatus(), uiRequest);
        }

        if ("RUNTIME_GRAPH_CANCELLED".equals(result.code())) {
            markStatus(session, CANCELLED, submittedPayload, Map.of("code", result.code()), idempotencyKey);
            sessionService.writeEvent(session.getId(), CANCELLED, Map.of("code", result.code()), operatorId);
            return successBody(result, session, CANCELLED, null);
        }

        if (!result.success()) {
            // Validation-style waiting is already handled above; other failures mark FAILED.
            // If executor returned WAITING again via validation, isWaitingUser covers it.
            markStatus(session, FAILED, submittedPayload, Map.of(
                    "code", result.code(),
                    "answer", nullToEmpty(result.answer())), idempotencyKey);
            sessionService.writeEvent(session.getId(), FAILED, Map.of("code", result.code()), operatorId);
            return successBody(result, session, FAILED, null);
        }

        // Validation failures that remain waiting: if code is WAITING we already branched.
        // Soft reject completion (success with reject route, no next node) -> COMPLETED.
        markStatus(session, COMPLETED, submittedPayload, Map.of(
                "code", result.code(),
                "answer", nullToEmpty(result.answer())), idempotencyKey);
        sessionService.writeEvent(session.getId(), "RESUMED", Map.of("code", result.code()), operatorId);
        sessionService.writeEvent(session.getId(), COMPLETED, Map.of("code", result.code()), operatorId);
        Map<String, Object> completed = successBody(result, session, COMPLETED, result.uiRequest());
        Map<String, Object> continuation = sessionService.readMap(session.getContinuationJson());
        if (continuation != null && !continuation.isEmpty()) {
            completed.put("continuation", continuation);
        }
        return completed;
    }

    private boolean claimResuming(RuntimeInteractionSessionEntity session,
                                  String idempotencyKey,
                                  Map<String, Object> submittedPayload,
                                  String operatorId) {
        int revision = session.getRevision() == null ? 0 : session.getRevision();
        UpdateWrapper<RuntimeInteractionSessionEntity> update = new UpdateWrapper<>();
        update.eq("id", session.getId())
                .eq("status", WAITING_USER)
                .eq("revision", revision)
                .set("status", RESUMING)
                .set("revision", revision + 1)
                .set("submitted_payload_json", sessionService.writeJson(submittedPayload))
                .set("update_time", LocalDateTime.now());
        if (StringUtils.hasText(idempotencyKey)) {
            update.set("idempotency_key", idempotencyKey);
        }
        int rows = sessionMapper.update(null, update);
        if (rows == 1) {
            session.setStatus(RESUMING);
            session.setRevision(revision + 1);
            if (StringUtils.hasText(idempotencyKey)) {
                session.setIdempotencyKey(idempotencyKey);
            }
            session.setSubmittedPayloadJson(sessionService.writeJson(submittedPayload));
            sessionService.writeEvent(session.getId(), "SUBMITTED", Map.of(
                    "keys", submittedPayload.keySet()), operatorId);
            return true;
        }
        return false;
    }

    private Map<String, Object> conflictOrReplay(RuntimeInteractionSessionEntity session,
                                                 String idempotencyKey,
                                                 Map<String, Object> submittedPayload) {
        RuntimeInteractionSessionEntity latest = sessionService.requireById(session.getId());
        if (StringUtils.hasText(idempotencyKey)
                && idempotencyKey.equals(latest.getIdempotencyKey())
                && payloadMatches(latest.getSubmittedPayloadJson(), submittedPayload)) {
            return replayResult(latest);
        }
        if (StringUtils.hasText(idempotencyKey)
                && idempotencyKey.equals(latest.getIdempotencyKey())
                && !payloadMatches(latest.getSubmittedPayloadJson(), submittedPayload)) {
            return failure("RUNTIME_INTERACTION_CONFLICT",
                    "duplicate submit with different payload for the same idempotency key",
                    latest.getId());
        }
        return failure("RUNTIME_INTERACTION_CONFLICT",
                "concurrent submit rejected; another request holds the resume lock",
                latest.getId());
    }

    private void rollbackToWaiting(RuntimeInteractionSessionEntity session,
                                   Map<String, Object> resumeCheckpoint,
                                   Object uiRequest,
                                   String operatorId) {
        UpdateWrapper<RuntimeInteractionSessionEntity> update = new UpdateWrapper<>();
        update.eq("id", session.getId())
                .eq("status", RESUMING)
                .set("status", WAITING_USER)
                .set("revision", (session.getRevision() == null ? 0 : session.getRevision()) + 1)
                .set("idempotency_key", null)
                .set("resume_checkpoint_json", sessionService.writeJson(resumeCheckpoint))
                .set("ui_request_json", sessionService.writeJson(uiRequest))
                .set("update_time", LocalDateTime.now());
        sessionMapper.update(null, update);
        session.setStatus(WAITING_USER);
        sessionService.writeEvent(session.getId(), "REQUESTED", Map.of(
                "reason", "validation_failed",
                "interactionId", session.getId()), operatorId);
    }

    private void markStatus(RuntimeInteractionSessionEntity session,
                            String status,
                            Map<String, Object> submittedPayload,
                            Map<String, Object> result,
                            String idempotencyKey) {
        RuntimeInteractionSessionEntity update = new RuntimeInteractionSessionEntity();
        update.setId(session.getId());
        update.setStatus(status);
        update.setRevision((session.getRevision() == null ? 0 : session.getRevision()) + 1);
        if (submittedPayload != null) {
            update.setSubmittedPayloadJson(sessionService.writeJson(submittedPayload));
        }
        if (result != null) {
            update.setResultJson(sessionService.writeJson(result));
        }
        if (StringUtils.hasText(idempotencyKey)) {
            update.setIdempotencyKey(idempotencyKey);
        }
        // Persist latest context when still resumable next interaction was created separately.
        update.setUpdateTime(LocalDateTime.now());
        sessionMapper.updateById(update);
        session.setStatus(status);
    }

    private String resolveGraphSpecJson(RuntimeInteractionSessionEntity session) {
        if (StringUtils.hasText(session.getGraphSpecSnapshotJson())) {
            return session.getGraphSpecSnapshotJson();
        }
        // Legacy composition path only.
        if (!StringUtils.hasText(session.getCompositionQualifiedName())) {
            return null;
        }
        try {
            Map<String, Object> composition =
                    capabilityClient.getCompositionDefinition(session.getCompositionQualifiedName());
            if (composition == null) {
                return null;
            }
            Object graph = composition.get("graphSpecJson");
            return graph == null ? null : String.valueOf(graph);
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, Object> successBody(RuntimeGraphSpecExecutionResult result,
                                            RuntimeInteractionSessionEntity session,
                                            String status,
                                            Object uiRequest) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", result.success() || result.isWaitingUser()
                || "RUNTIME_GRAPH_CANCELLED".equals(result.code()));
        body.put("code", result.code());
        body.put("answer", result.answer());
        body.put("nodeId", result.nodeId());
        body.put("nodeType", result.nodeType());
        body.put("steps", result.steps());
        body.put("metadata", publicMetadata(result.metadata()));
        body.put("interactionSessionId", session.getId());
        body.put("interactionId", session.getId());
        body.put("status", status);
        putIdentity(body, session);
        // Internal-only: never expose raw executor context on public resume responses.
        if (uiRequest != null) {
            body.put("uiRequest", uiRequest);
        } else if (result.uiRequest() != null) {
            body.put("uiRequest", result.uiRequest());
        }
        body.put("waiting", result.isWaitingUser());
        return body;
    }

    private void putIdentity(Map<String, Object> body, RuntimeInteractionSessionEntity session) {
        if (session == null) {
            return;
        }
        if (StringUtils.hasText(session.getRunId())) {
            body.put("runId", session.getRunId());
        }
        if (StringUtils.hasText(session.getTraceId())) {
            body.put("traceId", session.getTraceId());
        }
        if (StringUtils.hasText(session.getWorkflowId())) {
            body.put("workflowId", session.getWorkflowId());
        }
        if (session.getWorkflowVersionId() != null) {
            body.put("workflowVersionId", session.getWorkflowVersionId());
        }
    }

    private Map<String, Object> publicMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>(metadata);
        copy.remove("resumeCheckpoint");
        copy.remove(WorkflowInteractionCodes.RESUME_CONTEXT_KEY);
        return copy;
    }

    private boolean requiresOwnership(RuntimeInteractionSessionEntity session) {
        if (session == null) {
            return true;
        }
        if ("WORKFLOW".equalsIgnoreCase(text(session.getSourceType()))) {
            return true;
        }
        String id = text(session.getId());
        return id != null && id.startsWith(WorkflowInteractionCodes.ID_PREFIX);
    }

    private Map<String, Object> replayResult(RuntimeInteractionSessionEntity session) {
        if (RESUMING.equalsIgnoreCase(text(session.getStatus()))) {
            Map<String, Object> inProgress = failure(
                    "RUNTIME_INTERACTION_CONFLICT",
                    "identical interaction submit is still being processed",
                    session.getId());
            inProgress.put("status", RESUMING);
            inProgress.put("idempotentReplay", true);
            inProgress.put("retryable", true);
            putIdentity(inProgress, session);
            return inProgress;
        }
        Map<String, Object> result = sessionService.readMap(session.getResultJson());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("code", firstText(text(result.get("code")), "RUNTIME_GRAPH_EXECUTED"));
        body.put("answer", firstText(text(result.get("answer")), "idempotent replay"));
        body.put("interactionSessionId", session.getId());
        body.put("interactionId", session.getId());
        body.put("status", session.getStatus());
        body.put("idempotentReplay", true);
        putIdentity(body, session);
        Map<String, Object> ui = sessionService.readMap(session.getUiRequestJson());
        if (!ui.isEmpty() && WAITING_USER.equalsIgnoreCase(session.getStatus())) {
            body.put("uiRequest", ui);
            body.put("waiting", true);
        }
        return body;
    }

    private boolean ownershipMatches(RuntimeInteractionSessionEntity session, Ownership ownership) {
        if (ownership == null) {
            return !requiresOwnership(session);
        }
        if (requiresOwnership(session)) {
            if (!StringUtils.hasText(session.getSessionId())
                    || !StringUtils.hasText(session.getAppId())
                    || !StringUtils.hasText(session.getUserId())) {
                return false;
            }
            return eq(session.getSessionId(), ownership.sessionId())
                    && eq(session.getAppId(), ownership.appId())
                    && eq(session.getUserId(), ownership.userId())
                    && (!StringUtils.hasText(session.getTenantId())
                    || eq(session.getTenantId(), ownership.tenantId()));
        }
        if (StringUtils.hasText(session.getSessionId()) && !eq(session.getSessionId(), ownership.sessionId())) {
            return false;
        }
        if (StringUtils.hasText(session.getAppId()) && !eq(session.getAppId(), ownership.appId())) {
            return false;
        }
        if (StringUtils.hasText(session.getTenantId()) && !eq(session.getTenantId(), ownership.tenantId())) {
            return false;
        }
        if (StringUtils.hasText(session.getUserId()) && !eq(session.getUserId(), ownership.userId())) {
            return false;
        }
        return true;
    }

    private boolean eq(String expected, String actual) {
        return StringUtils.hasText(actual) && expected.equals(actual);
    }

    private boolean payloadMatches(String storedJson, Map<String, Object> submittedPayload) {
        Map<String, Object> stored = sessionService.readMap(storedJson);
        return stored.equals(submittedPayload == null ? Map.of() : submittedPayload);
    }

    private Map<String, Object> submittedPayload(Map<String, Object> request) {
        if (request == null || request.isEmpty()) {
            return Map.of();
        }
        Object uiSubmit = request.get("uiSubmit");
        if (uiSubmit instanceof Map<?, ?> uiMap) {
            Object values = uiMap.get("values");
            if (values instanceof Map<?, ?> valueMap) {
                Map<String, Object> mapped = safeMap(valueMap);
                Object action = uiMap.get("action");
                if (action != null) {
                    mapped = new LinkedHashMap<>(mapped);
                    mapped.putIfAbsent("action", action);
                }
                return mapped;
            }
        }
        Object raw = firstPresent(request.get("values"),
                firstPresent(request.get("submittedPayload"), request.get("payload")));
        Map<String, Object> map = safeMap(raw);
        if (!map.isEmpty()) {
            return map;
        }
        Map<String, Object> fallback = new LinkedHashMap<>(request);
        fallback.remove("operatorId");
        fallback.remove("context");
        fallback.remove("idempotencyKey");
        fallback.remove("idempotency_key");
        fallback.remove("interactionId");
        fallback.remove("uiSubmit");
        fallback.remove("sessionId");
        fallback.remove("appId");
        fallback.remove("tenantId");
        fallback.remove("userId");
        return fallback;
    }

    private String interactionType(RuntimeGraphSpecExecutionResult result) {
        if (result.metadata() != null && result.metadata().get("interactionType") != null) {
            return String.valueOf(result.metadata().get("interactionType"));
        }
        return "COLLECT_INPUT";
    }

    private Integer ttlSeconds(Object uiRequest) {
        if (uiRequest instanceof Map<?, ?> map && map.get("ttlSeconds") instanceof Number number) {
            return number.intValue();
        }
        return 3600;
    }

    private Map<String, Object> failure(String code, String answer, String sessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("answer", answer);
        body.put("interactionSessionId", sessionId);
        body.put("interactionId", sessionId);
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return copy;
    }

    private Object firstPresent(Object first, Object fallback) {
        return first != null ? first : fallback;
    }

    private static String ownershipUser(Ownership ownership) {
        return ownership == null ? null : ownership.userId();
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

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    public record Ownership(String appId, String tenantId, String sessionId, String userId) {
    }
}
