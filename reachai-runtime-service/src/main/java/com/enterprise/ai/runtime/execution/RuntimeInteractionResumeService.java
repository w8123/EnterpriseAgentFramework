package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.execution.checkpoint.WorkflowCheckpointCodec;
import com.enterprise.ai.runtime.execution.checkpoint.WorkflowCheckpointException;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
@Slf4j
@RequiredArgsConstructor
public class RuntimeInteractionResumeService {

    private static final String WAITING_USER = "WAITING_USER";
    private static final String RESUMING = "RESUMING";
    private static final String COMPLETED = "COMPLETED";
    private static final String CANCELLED = "CANCELLED";
    private static final String EXPIRED = "EXPIRED";
    private static final String FAILED = "FAILED";

    private final RuntimeWorkflowInteractionSessionService sessionService;
    private final RuntimeCapabilityCatalogClient capabilityClient;
    private final RuntimeGraphSpecExecutor graphSpecExecutor;
    private final ObjectMapper objectMapper;
    private final RuntimeInteractionExpiryProcessor expiryProcessor;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Object> resume(String sessionId, Map<String, Object> request) {
        return resume(sessionId, request, null);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Object> resume(String sessionId, Map<String, Object> request, Ownership ownership) {
        if (!StringUtils.hasText(sessionId)) {
            return failure("RUNTIME_INTERACTION_SESSION_REQUIRED", "Interaction sessionId is required", sessionId);
        }
        RuntimeInteractionSessionEntity session;
        try {
            session = sessionService.requireById(sessionId.trim());
        } catch (IllegalArgumentException ex) {
            return failure("RUNTIME_INTERACTION_NOT_FOUND", ex.getMessage(), sessionId.trim());
        }
        if ((requiresOwnership(session) || ownership != null) && !ownershipMatches(session, ownership)) {
            return failure("RUNTIME_INTERACTION_FORBIDDEN",
                    "interaction does not belong to the current session/user", session.getId());
        }
        session = reconcileOverdueResume(session);
        Map<String, Object> values = submittedPayload(request);
        Object uiAction = request != null && request.get("uiSubmit") instanceof Map<?, ?> ui ? ui.get("action") : null;
        String action = firstText(text(request == null ? null : request.get("action")), text(uiAction),
                text(values.get("action")), "submit").toLowerCase(java.util.Locale.ROOT);
        String idempotencyKey = firstText(text(request == null ? null : request.get("idempotencyKey")),
                text(request == null ? null : request.get("idempotency_key")));
        String operatorId = firstText(ownershipUser(ownership), text(request == null ? null : request.get("operatorId")));
        Map<String, Object> submission = Map.of("submissionSchemaVersion", 1, "action", action, "values", values);
        boolean sameKey = idempotencyKey != null && idempotencyKey.equals(session.getIdempotencyKey());
        if (sameKey && !payloadMatches(session.getSubmittedPayloadJson(), action, values)) {
            return conflict(session, "duplicate submit changed its action or values for the same idempotency key");
        }
        if (!WAITING_USER.equals(session.getStatus())) {
            if (isUnknownOutcome(session)) return replayResult(session);
            if (sameKey) return replayResult(session);
            if (RESUMING.equals(session.getStatus())) {
                return conflict(session, "another interaction submission is still being processed");
            }
            if (EXPIRED.equals(session.getStatus())) {
                return failure("RUNTIME_INTERACTION_EXPIRED", "interaction session expired", session.getId());
            }
            if (CANCELLED.equals(session.getStatus())) {
                return failure("RUNTIME_INTERACTION_CANCELLED", "interaction session cancelled", session.getId());
            }
            return failure("RUNTIME_INTERACTION_NOT_WAITING", "interaction session is not waiting", session.getId());
        }
        if (session.getExpiresAt() != null && !session.getExpiresAt().isAfter(LocalDateTime.now())) {
            if (!expiryProcessor.expireOne(session, LocalDateTime.now())) {
                return conflict(session, "interaction session changed while expiry was being reconciled");
            }
            return failure("RUNTIME_INTERACTION_EXPIRED", "interaction session expired", session.getId());
        }
        if (!sessionService.claimResume(session, idempotencyKey, submission, operatorId)) {
            RuntimeInteractionSessionEntity latest = sessionService.requireById(session.getId());
            if (idempotencyKey != null && idempotencyKey.equals(latest.getIdempotencyKey())
                    && payloadMatches(latest.getSubmittedPayloadJson(), action, values)) {
                return replayResult(latest);
            }
            return conflict(latest, "concurrent submit rejected; interaction state changed");
        }
        // The owner transaction has committed; only now can this request execute a Workflow.
        session.setStatus(RESUMING);
        session.setRevision((session.getRevision() == null ? 0 : session.getRevision()) + 1);
        session.setIdempotencyKey(idempotencyKey);
        session.setSubmittedPayloadJson(sessionService.writeJson(submission));
        try {
            return resumeClaimed(session, action, values, idempotencyKey, operatorId);
        } catch (RuntimeWorkflowInteractionSessionService.ResumeConflictException conflict) {
            RuntimeInteractionSessionEntity latest = reconcileOverdueResume(sessionService.requireById(session.getId()));
            if (isUnknownOutcome(latest)) return replayResult(latest);
            return conflict(session, "interaction changed before the resume result could be committed");
        }
    }

    private Map<String, Object> resumeClaimed(RuntimeInteractionSessionEntity session,
                                              String action, Map<String, Object> values, String idempotencyKey,
                                              String operatorId) {
        if ("cancel".equals(action)) {
            Map<String, Object> response = failure("RUNTIME_GRAPH_CANCELLED", "Interaction cancelled", session.getId());
            response.put("success", true);
            response.put("waiting", false);
            response.put("status", CANCELLED);
            putIdentity(response, session);
            return finish(session, CANCELLED, response, operatorId, CANCELLED);
        }
        String graphSpecJson;
        try {
            graphSpecJson = resolveGraphSpecJson(session);
        } catch (IllegalArgumentException invalidGraph) {
            return failClaimed(session, "RUNTIME_INTERACTION_GRAPH_INVALID", "Interaction GraphSpec snapshot is invalid", operatorId);
        }
        if (!StringUtils.hasText(graphSpecJson)) {
            return failClaimed(session, "RUNTIME_INTERACTION_GRAPH_MISSING", "Interaction GraphSpec snapshot is missing", operatorId);
        }
        WorkflowCheckpointCodec.DecodedCheckpoint decoded;
        try {
            decoded = sessionService.decodeCheckpoint(session, graphSpecJson);
        } catch (WorkflowCheckpointException rejected) {
            return failClaimed(session, rejected.code(), rejected.getMessage(), operatorId, "CHECKPOINT_REJECTED");
        }
        Map<String, Object> checkpoint = new LinkedHashMap<>(decoded.state());
        // Resume state belongs to the persisted checkpoint; submitted values enter through the interaction node.
        checkpoint.put("runId", firstText(session.getRunId(), text(checkpoint.get("runId"))));
        checkpoint.put("traceId", firstText(session.getTraceId(), text(checkpoint.get("traceId"))));
        checkpoint.put("workflowId", firstText(session.getWorkflowId(), text(checkpoint.get("workflowId"))));
        if (session.getWorkflowVersionId() != null) checkpoint.put("workflowVersionId", session.getWorkflowVersionId());
        checkpoint.put(WorkflowInteractionCodes.PENDING_INTERACTION_ID_KEY, session.getId());
        checkpoint.put(WorkflowInteractionCodes.PENDING_INTERACTION_NODE_KEY, session.getNodeId());
        checkpoint.put(WorkflowInteractionCodes.RESUME_CONTEXT_KEY, Map.of("interactionId", session.getId(),
                "nodeId", session.getNodeId(), "action", action, "values", values,
                "idempotencyKey", idempotencyKey == null ? "" : idempotencyKey));
        checkpoint.remove("submittedPayload");
        WorkflowExecutionIdentity identity = requiresOwnership(session)
                ? WorkflowExecutionIdentity.fromAgent(session.getTenantId(), null, session.getAppId(), session.getUserId())
                : WorkflowExecutionIdentity.untrustedComposition();
        RuntimeGraphSpecExecutionResult result;
        try {
            result = graphSpecExecutor.executeFromCheckpoint(graphSpecJson, checkpoint, session.getNodeId(),
                    decoded.schemaVersion(), decoded.engineVersion(), identity);
        } catch (RuntimeException executionFailure) {
            log.warn("Workflow interaction execution failed: interactionId={}, type={}",
                    session.getId(), executionFailure.getClass().getName());
            return failClaimed(session, "RUNTIME_INTERACTION_EXECUTION_FAILED", "Workflow interaction execution failed", operatorId);
        }
        if (result == null) {
            return failClaimed(session, "RUNTIME_INTERACTION_EXECUTION_FAILED", "Workflow interaction returned no result", operatorId);
        }
        if (result.isWaitingUser()) {
            Map<String, Object> nextCheckpoint = new LinkedHashMap<>(result.resumeCheckpoint().isEmpty()
                    ? checkpoint : result.resumeCheckpoint());
            nextCheckpoint.remove("submittedPayload");
            nextCheckpoint.remove(WorkflowInteractionCodes.RESUME_CONTEXT_KEY);
            Object uiRequest = result.uiRequest();
            if (session.getId().equals(result.interactionId()) && session.getNodeId().equals(result.nodeId())) {
                sessionService.returnToWaiting(session, nextCheckpoint, uiRequest, operatorId, graphSpecJson);
                Map<String, Object> response = successBody(result, session, WAITING_USER, uiRequest);
                response.put("success", false);
                response.put("validationFailed", true);
                return response;
            }
            String nextId = firstText(result.interactionId(), WorkflowInteractionCodes.ID_PREFIX
                    + java.util.UUID.randomUUID().toString().replace("-", ""));
            Map<String, Object> response = successBody(result, session, WAITING_USER, uiRequest);
            response.put("interactionSessionId", nextId);
            response.put("interactionId", nextId);
            sessionService.completeAndCreateNext(session, response, operatorId,
                    new RuntimeWorkflowInteractionSessionService.CreateRequest(nextId, session.getSourceType(),
                            session.getRunId(), session.getTraceId(), session.getWorkflowId(), session.getWorkflowVersionId(),
                            session.getCompositionQualifiedName(), graphSpecJson, result.nodeId(), interactionType(result),
                            nextCheckpoint, uiRequest, sessionService.readMap(session.getContinuationJson()),
                            session.getAppId(), session.getTenantId(), session.getSessionId(), session.getUserId(), ttlSeconds(uiRequest)));
            return response;
        }
        String status = "RUNTIME_GRAPH_CANCELLED".equals(result.code()) ? CANCELLED : result.success() ? COMPLETED : FAILED;
        Map<String, Object> response = successBody(result, session, status, result.uiRequest());
        if (COMPLETED.equals(status)) {
            Map<String, Object> continuation = sessionService.readMap(session.getContinuationJson());
            if (!continuation.isEmpty()) response.put("continuation", continuation);
            return finish(session, status, response, operatorId, "RESUMED", COMPLETED);
        }
        return finish(session, status, response, operatorId, status);
    }

    private Map<String, Object> failClaimed(RuntimeInteractionSessionEntity session, String code, String answer,
                                            String operatorId, String... additionalEvents) {
        Map<String, Object> response = failure(code, answer, session.getId());
        response.put("status", FAILED);
        response.put("waiting", false);
        putIdentity(response, session);
        java.util.List<String> events = new java.util.ArrayList<>(java.util.List.of(additionalEvents));
        events.add(FAILED);
        return finish(session, FAILED, response, operatorId, events.toArray(String[]::new));
    }

    private Map<String, Object> finish(RuntimeInteractionSessionEntity session, String status,
                                       Map<String, Object> response, String operatorId, String... events) {
        sessionService.finishResume(session, status, response, operatorId, events);
        return response;
    }

    private Map<String, Object> conflict(RuntimeInteractionSessionEntity session, String answer) {
        Map<String, Object> response = failure("RUNTIME_INTERACTION_CONFLICT", answer, session.getId());
        putIdentity(response, session);
        return response;
    }

    private String resolveGraphSpecJson(RuntimeInteractionSessionEntity session) {
        if (StringUtils.hasText(session.getGraphSpecSnapshotJson())) {
            if (session.getWorkflowVersionId() != null) {
                return com.enterprise.ai.agent.graph.GraphSpecToolContract.requirePublishedPins(
                        session.getGraphSpecSnapshotJson(), objectMapper);
            }
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
        session = reconcileOverdueResume(session);
        if (RESUMING.equals(session.getStatus())) {
            Map<String, Object> response = conflict(session, "identical interaction submit is still being processed");
            response.put("status", RESUMING);
            response.put("idempotentReplay", true);
            response.put("retryable", true);
            return response;
        }
        Map<String, Object> stored = sessionService.readMap(session.getResultJson());
        Map<String, Object> response;
        if (Integer.valueOf(1).equals(stored.get("resumeResultSchemaVersion"))
                && stored.get("response") instanceof Map<?, ?> saved) {
            response = safeMap(saved);
            // Replaying a Workflow receipt must not dispatch its Supervisor continuation again.
            response.remove("continuation");
        } else {
            response = new LinkedHashMap<>();
            boolean success = COMPLETED.equals(session.getStatus()) || CANCELLED.equals(session.getStatus());
            response.put("success", success);
            response.put("code", firstText(text(stored.get("code")), success ? "RUNTIME_GRAPH_EXECUTED" : "RUNTIME_GRAPH_FAILED"));
            response.put("answer", firstText(text(stored.get("answer")), "idempotent replay"));
            response.put("interactionSessionId", session.getId());
            response.put("interactionId", session.getId());
            response.put("status", session.getStatus());
            response.put("waiting", false);
            putIdentity(response, session);
        }
        response.put("idempotentReplay", true);
        return response;
    }

    private RuntimeInteractionSessionEntity reconcileOverdueResume(RuntimeInteractionSessionEntity session) {
        LocalDateTime now = LocalDateTime.now();
        if (RESUMING.equals(session.getStatus()) && session.getResumeDeadlineAt() != null
                && !session.getResumeDeadlineAt().isAfter(now)) {
            expiryProcessor.expireOne(session, now);
            return sessionService.requireById(session.getId());
        }
        return session;
    }

    private boolean isUnknownOutcome(RuntimeInteractionSessionEntity session) {
        if (!EXPIRED.equals(session.getStatus())) return false;
        Map<String, Object> result = sessionService.readMap(session.getResultJson());
        return Integer.valueOf(1).equals(result.get("resumeResultSchemaVersion"))
                && result.get("response") instanceof Map<?, ?> saved
                && WorkflowInteractionCodes.RESUME_TIMEOUT.equals(saved.get("code"));
    }

    private boolean ownershipMatches(RuntimeInteractionSessionEntity session, Ownership ownership) {
        if (ownership == null) {
            return !requiresOwnership(session);
        }
        if (requiresOwnership(session)) {
            if (ownership.trustedIdentity() == null || !ownership.trustedIdentity().canResolveUserAcl()
                    || !StringUtils.hasText(session.getSessionId())
                    || !StringUtils.hasText(session.getAppId())
                    || !StringUtils.hasText(session.getUserId())) {
                return false;
            }
            return eq(session.getSessionId(), ownership.sessionId())
                    && eq(session.getAppId(), ownership.appId())
                    && eq(session.getUserId(), ownership.userId())
                    && firstText(session.getTenantId(), "default")
                            .equals(firstText(ownership.tenantId(), "default"));
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

    private boolean payloadMatches(String storedJson, String action, Map<String, Object> values) {
        Map<String, Object> stored = sessionService.readMap(storedJson);
        Map<String, Object> normalized = sessionService.readMap(sessionService.writeJson(values));
        if (Integer.valueOf(1).equals(stored.get("submissionSchemaVersion"))) {
            return action.equals(stored.get("action")) && normalized.equals(stored.get("values"));
        }
        // Older Workflow attempts stored only values; they represented the default submit action.
        return "submit".equals(action) && stored.equals(normalized);
    }

    private Map<String, Object> submittedPayload(Map<String, Object> request) {
        if (request == null || request.isEmpty()) {
            return Map.of();
        }
        Object uiSubmit = request.get("uiSubmit");
        if (uiSubmit instanceof Map<?, ?> uiMap) {
            Object values = uiMap.get("values");
            if (values instanceof Map<?, ?> valueMap) {
                return safeMap(valueMap);
            }
        }
        Object raw = firstPresent(request.get("values"),
                firstPresent(request.get("submittedPayload"), request.get("payload")));
        if (raw instanceof Map<?, ?> map) {
            return safeMap(map);
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
        for (String key : java.util.List.of("action", "traceId", "runId", "agentId", "projectCode", "metadata",
                "externalUserId", "globalUserId", "__memoryTurnId", "__memoryUserMessage")) fallback.remove(key);
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

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    /** User and tenant come only from the attested execution identity, never the submit body. */
    public record Ownership(String appId, String sessionId, WorkflowExecutionIdentity trustedIdentity) {
        public String tenantId() {
            return trustedIdentity == null ? null : trustedIdentity.tenantId();
        }

        public String userId() {
            return trustedIdentity != null && trustedIdentity.canResolveUserAcl() ? trustedIdentity.userId() : null;
        }
    }
}
