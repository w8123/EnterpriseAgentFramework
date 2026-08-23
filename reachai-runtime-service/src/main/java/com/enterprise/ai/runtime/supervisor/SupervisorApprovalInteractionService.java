package com.enterprise.ai.runtime.supervisor;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.execution.RuntimeInteractionEventEntity;
import com.enterprise.ai.runtime.execution.RuntimeInteractionEventMapper;
import com.enterprise.ai.runtime.execution.RuntimeInteractionSessionEntity;
import com.enterprise.ai.runtime.execution.RuntimeInteractionSessionMapper;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.supervisor.SupervisorRuntimeAdapter.PolicyApprovalGrant;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SupervisorApprovalInteractionService {

    public static final String INTERACTION_PREFIX = "spv_";
    public static final String SOURCE_TYPE = "SUPERVISOR_POLICY";
    public static final String INTERACTION_TYPE = "CONFIRM_ACTION";
    public static final String WAITING_USER = "WAITING_USER";
    public static final String RESUMING = "RESUMING";
    public static final String COMPLETED = "COMPLETED";
    public static final String EXPIRED = "EXPIRED";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RuntimeInteractionSessionMapper sessionMapper;
    private final RuntimeInteractionEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public ApprovalRequest create(SupervisorExecutionTraceService.TraceHandle trace,
                                  RuntimeAgentView agent,
                                  RuntimeAgentConfigVersionEntity config,
                                  RuntimeAgentWorkflowToolEntity tool,
                                  Map<String, Object> input,
                                  Map<String, Object> args,
                                  String reason,
                                  WorkflowExecutionIdentity trustedIdentity) {
        LocalDateTime now = LocalDateTime.now();
        String interactionId = INTERACTION_PREFIX + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("phase", "CONFIRM");
        checkpoint.put("agentId", agent.id());
        checkpoint.put("agentConfigVersionId", config.getId());
        checkpoint.put("toolName", tool.getToolName());
        checkpoint.put("permissionKey", tool.getPermissionKey());
        checkpoint.put("riskLevel", tool.getRiskLevel());
        checkpoint.put("args", args == null ? Map.of() : args);
        checkpoint.put("input", input == null ? Map.of() : input);

        Map<String, Object> displayArgs = displayArgs(args);

        Map<String, Object> uiRequest = new LinkedHashMap<>();
        uiRequest.put("type", INTERACTION_TYPE);
        uiRequest.put("component", "confirm");
        uiRequest.put("interactionId", interactionId);
        uiRequest.put("title", "确认执行：" + firstText(tool.getToolName(), "Workflow Tool"));
        uiRequest.put("message", confirmationMessage(reason, displayArgs));
        Map<String, Object> cardData = new LinkedHashMap<>();
        cardData.put("toolName", firstText(tool.getToolName(), ""));
        cardData.put("permissionKey", firstText(tool.getPermissionKey(), ""));
        cardData.put("riskLevel", firstText(tool.getRiskLevel(), "WRITE"));
        cardData.put("arguments", displayArgs);
        uiRequest.put("data", cardData);
        uiRequest.put("actions", List.of(
                Map.of("key", "reject", "label", "拒绝", "tone", "danger"),
                Map.of("key", "confirm", "label", "确认执行", "tone", "primary")));
        uiRequest.put("extension", Map.of(
                "kind", "SUPERVISOR_POLICY_APPROVAL",
                "resumeVia", "AGENT_EXECUTE",
                "permissionKey", firstText(tool.getPermissionKey(), ""),
                "riskLevel", firstText(tool.getRiskLevel(), "WRITE")));

        Map<String, Object> continuation = new LinkedHashMap<>();
        continuation.put("agentId", agent.id());
        continuation.put("agentConfigVersionId", config.getId());
        continuation.put("toolName", firstText(tool.getToolName(), ""));
        continuation.put("permissionKey", firstText(tool.getPermissionKey(), ""));
        continuation.put("riskLevel", firstText(tool.getRiskLevel(), "WRITE"));
        continuation.put("resumeVia", "AGENT_EXECUTE");

        RuntimeInteractionSessionEntity row = new RuntimeInteractionSessionEntity();
        row.setId(interactionId);
        row.setSourceType(SOURCE_TYPE);
        row.setAgentId(agent.id());
        row.setTraceId(trace.traceId());
        row.setNodeId(firstText(tool.getToolName(), "workflow"));
        row.setInteractionType(INTERACTION_TYPE);
        row.setStatus(WAITING_USER);
        row.setRevision(0);
        row.setResumeCheckpointJson(writeJson(checkpoint));
        row.setUiRequestJson(writeJson(uiRequest));
        row.setContinuationJson(writeJson(continuation));
        row.setSessionId(text(input, "sessionId"));
        row.setUserId(trustedUserId(trustedIdentity));
        row.setCreateTime(now);
        row.setUpdateTime(now);
        row.setExpiresAt(now.plusMinutes(15));
        sessionMapper.insert(row);
        writeEvent(interactionId, "CREATED", Map.of(
                "toolName", firstText(tool.getToolName(), ""),
                "traceId", firstText(trace.traceId(), ""),
                "agentId", agent.id()), row.getUserId());
        writeEvent(interactionId, "REQUESTED", Map.of(
                "interactionId", interactionId,
                "component", "confirm"), row.getUserId());
        return new ApprovalRequest(interactionId, uiRequest);
    }

    @Transactional
    public ResumeDecision prepareResume(String interactionId,
                                        Map<String, Object> submission,
                                        WorkflowExecutionIdentity trustedIdentity) {
        RuntimeInteractionSessionEntity row = requireApproval(interactionId);
        Map<String, Object> uiSubmit = mapValue(submission == null ? null : submission.get("uiSubmit"));
        Map<String, Object> values = mapValue(uiSubmit.get("values"));
        boolean confirmed = requireDecision(text(uiSubmit.get("action")), values.get("confirm"));

        String submittedSessionId = text(submission == null ? null : submission.get("sessionId"));
        if (StringUtils.hasText(row.getSessionId())
                && !row.getSessionId().equals(submittedSessionId)) {
            throw new IllegalArgumentException("Supervisor approval session mismatch");
        }
        String actorUserId = trustedUserId(trustedIdentity);
        if (StringUtils.hasText(row.getUserId()) && !row.getUserId().equals(actorUserId)) {
            throw new IllegalArgumentException("Supervisor approval user mismatch");
        }
        actorUserId = firstText(actorUserId, row.getUserId(), "anonymous");

        String idempotencyKey = firstText(
                text(submission == null ? null : submission.get("idempotencyKey")),
                interactionId + ":" + (confirmed ? "approved" : "rejected"));
        Map<String, Object> submittedPayload = new LinkedHashMap<>();
        submittedPayload.put("action", confirmed ? "confirm" : "reject");
        submittedPayload.put("values", values);

        if (!WAITING_USER.equalsIgnoreCase(row.getStatus())) {
            return resumeExisting(row, confirmed, idempotencyKey, submittedPayload);
        }
        if (row.getExpiresAt() != null && row.getExpiresAt().isBefore(LocalDateTime.now())) {
            expire(row);
            throw new IllegalArgumentException("Supervisor approval expired: " + interactionId.trim());
        }

        Map<String, Object> checkpoint = readMap(row.getResumeCheckpointJson());
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> decisionState = new LinkedHashMap<>(checkpoint);
        decisionState.put("phase", RESUMING);
        decisionState.put("decision", confirmed ? "approved" : "rejected");
        decisionState.put("submittedAt", now.toString());
        decisionState.put("submittedBy", actorUserId);

        int revision = row.getRevision() == null ? 0 : row.getRevision();
        UpdateWrapper<RuntimeInteractionSessionEntity> update = new UpdateWrapper<>();
        update.eq("id", row.getId())
                .eq("status", WAITING_USER)
                .eq("revision", revision)
                .set("status", RESUMING)
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
                "decision", confirmed ? "approved" : "rejected"),
                actorUserId);

        return toDecision(row, decisionState, confirmed, submittedSessionId, actorUserId,
                idempotencyKey, submittedPayload);
    }

    @Transactional
    public Map<String, Object> completeResume(String interactionId,
                                              String idempotencyKey,
                                              Map<String, Object> submittedPayload,
                                              Map<String, Object> result) {
        RuntimeInteractionSessionEntity row = requireApproval(interactionId);
        assertSameAttempt(row, idempotencyKey, submittedPayload);
        if (COMPLETED.equalsIgnoreCase(row.getStatus())) {
            return replayResult(row);
        }
        if (!RESUMING.equalsIgnoreCase(row.getStatus())) {
            throw new IllegalArgumentException("Supervisor approval is not resuming: " + row.getId());
        }

        LocalDateTime now = LocalDateTime.now();
        int revision = row.getRevision() == null ? 0 : row.getRevision();
        Map<String, Object> storedResult = result == null ? Map.of() : new LinkedHashMap<>(result);
        UpdateWrapper<RuntimeInteractionSessionEntity> update = new UpdateWrapper<>();
        update.eq("id", row.getId())
                .eq("status", RESUMING)
                .eq("revision", revision)
                .eq("idempotency_key", idempotencyKey)
                .set("status", COMPLETED)
                .set("revision", revision + 1)
                .set("result_json", writeJson(storedResult))
                .set("update_time", now);
        if (sessionMapper.update(null, update) != 1) {
            RuntimeInteractionSessionEntity latest = requireApproval(interactionId);
            assertSameAttempt(latest, idempotencyKey, submittedPayload);
            if (COMPLETED.equalsIgnoreCase(latest.getStatus())) {
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
        if (!RESUMING.equalsIgnoreCase(row.getStatus()) && !COMPLETED.equalsIgnoreCase(row.getStatus())) {
            throw new IllegalArgumentException("Supervisor approval is already closed: " + row.getId());
        }
        assertSameAttempt(row, idempotencyKey, submittedPayload);
        Map<String, Object> checkpoint = readMap(row.getResumeCheckpointJson());
        String storedDecision = text(checkpoint.get("decision"));
        if (("approved".equalsIgnoreCase(storedDecision)) != confirmed) {
            throw new IllegalArgumentException("Supervisor approval idempotency conflict: " + row.getId());
        }
        Map<String, Object> replay = COMPLETED.equalsIgnoreCase(row.getStatus())
                ? replayResult(row)
                : resumingResult(row);
        return new ResumeDecision(false, false, agentId(row, checkpoint), Map.of(), null,
                "", idempotencyKey, submittedPayload, replay);
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
            return new ResumeDecision(false, true, agentId(row, state), originalInput, null,
                    "用户已拒绝执行该 Workflow Tool", idempotencyKey, submittedPayload, null);
        }
        if (StringUtils.hasText(submittedSessionId)) originalInput.put("sessionId", submittedSessionId);
        String permissionKey = text(state.get("permissionKey"));
        PolicyApprovalGrant grant = new PolicyApprovalGrant(
                row.getId(), permissionKey, text(state.get("toolName")), mapValue(state.get("args")),
                firstText(submittedUserId, row.getUserId(), "anonymous"));
        return new ResumeDecision(true, false, agentId(row, state), originalInput, grant,
                "审批已通过，继续执行原请求", idempotencyKey, submittedPayload, null);
    }

    private RuntimeInteractionSessionEntity requireApproval(String interactionId) {
        if (!StringUtils.hasText(interactionId)) {
            throw new IllegalArgumentException("interactionId is required");
        }
        RuntimeInteractionSessionEntity row = sessionMapper.selectById(interactionId.trim());
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
        UpdateWrapper<RuntimeInteractionSessionEntity> update = new UpdateWrapper<>();
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

    private Map<String, Object> displayArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> display = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            if (display.size() >= 6 || !StringUtils.hasText(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            String key = entry.getKey().trim();
            String lower = key.toLowerCase(Locale.ROOT);
            Object value = entry.getValue();
            if (lower.contains("secret") || lower.contains("token") || lower.contains("password")
                    || lower.contains("authorization") || lower.contains("credential")
                    || lower.contains("apikey") || lower.contains("api_key") || lower.contains("cookie")) {
                display.put(key, "[已隐藏]");
            } else if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                String rendered = String.valueOf(value);
                display.put(key, rendered.length() <= 100 ? value : rendered.substring(0, 100) + "…");
            }
        }
        return display;
    }

    private String confirmationMessage(String reason, Map<String, Object> displayArgs) {
        String base = firstText(reason, "该操作需要用户确认");
        if (displayArgs == null || displayArgs.isEmpty()) {
            return base;
        }
        StringJoiner joiner = new StringJoiner("，");
        displayArgs.forEach((key, value) -> joiner.add(key + "=" + value));
        return base + "。操作参数：" + joiner;
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

    public record ApprovalRequest(String interactionId, Map<String, Object> uiRequest) {
    }

    public record ResumeDecision(boolean approved,
                                 boolean rejected,
                                 String agentId,
                                 Map<String, Object> originalInput,
                                 PolicyApprovalGrant grant,
                                 String message,
                                 String idempotencyKey,
                                 Map<String, Object> submittedPayload,
                                 Map<String, Object> replayResult) {

        public ResumeDecision(boolean approved,
                              boolean rejected,
                              String agentId,
                              Map<String, Object> originalInput,
                              PolicyApprovalGrant grant,
                              String message) {
            this(approved, rejected, agentId, originalInput, grant, message,
                    null, Map.of(), null);
        }
    }
}
