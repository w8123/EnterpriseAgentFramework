package com.enterprise.ai.runtime.managed;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.enterprise.ai.runtime.execution.RuntimeInteractionEventEntity;
import com.enterprise.ai.runtime.execution.RuntimeInteractionEventMapper;
import com.enterprise.ai.runtime.execution.RuntimeInteractionSessionEntity;
import com.enterprise.ai.runtime.execution.RuntimeInteractionSessionMapper;
import com.enterprise.ai.runtime.managed.ManagedExecutionPayloadSanitizer.SanitizedEvent;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ApprovalDecisionRequest;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ApprovalDecisionView;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ApprovalView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Runtime-owned, one-shot approval bridge between an untrusted Worker and Control. */
@Service
@RequiredArgsConstructor
public class ManagedExecutionApprovalService {

    static final String SOURCE_TYPE = "MANAGED_EXECUTOR";
    static final String INTERACTION_TYPE = "CONFIRM_ACTION";

    private final RuntimeInteractionSessionMapper sessionMapper;
    private final RuntimeInteractionEventMapper eventMapper;
    private final ManagedExecutionMapper executionMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public ApprovalOpened onRequested(ManagedExecutionEntity execution, SanitizedEvent event) {
        JsonNode data = data(event);
        String kind = enumText(data.path("approvalKind").asText(null),
                List.of("COMMAND", "FILE_CHANGE", "PERMISSIONS"), "approvalKind");
        if ("PERMISSIONS".equals(kind)) {
            return null; // Permission expansion is permanently denied inside the Worker.
        }
        String requestId = boundedIdentifier(data.path("approvalRequestId").asText(null),
                "approvalRequestId", 160);
        String interactionId = "mei_" + sha256(execution.getExecutionId() + "\n" + requestId)
                .substring(0, 32);
        LocalDateTime now = LocalDateTime.now();
        RuntimeInteractionSessionEntity row = new RuntimeInteractionSessionEntity();
        row.setId(interactionId);
        row.setSourceType(SOURCE_TYPE);
        row.setRunId(execution.getExecutionId());
        row.setTraceId(execution.getExecutionId());
        row.setNodeId("approval:" + sha256(requestId).substring(0, 24));
        row.setInteractionType(INTERACTION_TYPE);
        row.setStatus("WAITING_USER");
        row.setRevision(0);
        row.setResumeCheckpointJson(json(Map.of(
                "schema", "reachai.managed-executor.approval-checkpoint.v1",
                "executionId", execution.getExecutionId(),
                "approvalRequestId", requestId,
                "approvalKind", kind)));
        row.setCheckpointSchemaVersion(0);
        row.setExecutionEngineVersion("CODEX_HARNESS");
        row.setUiRequestJson(json(uiRequest(execution, event, data, interactionId, requestId, kind)));
        row.setTenantId(execution.getTenantId());
        row.setUserId(execution.getRequestedByUserId());
        row.setCreateTime(now);
        row.setUpdateTime(now);
        row.setExpiresAt(now.plusSeconds(Math.max(30, execution.getApprovalTimeoutSeconds())));
        try {
            sessionMapper.insert(row);
            writeEvent(interactionId, "CREATED", Map.of(
                    "executionId", execution.getExecutionId(),
                    "approvalRequestId", requestId,
                    "approvalKind", kind), "runtime");
        } catch (DuplicateKeyException duplicate) {
            RuntimeInteractionSessionEntity existing = sessionMapper.selectById(interactionId);
            if (existing == null
                    || !SOURCE_TYPE.equals(existing.getSourceType())
                    || !execution.getExecutionId().equals(existing.getRunId())) {
                throw conflict("MANAGED_APPROVAL_REPLAY_MISMATCH",
                        "Managed Executor approval replay does not match its interaction");
            }
        }
        if (executionMapper.openApproval(
                execution.getExecutionId(), requestId, interactionId, now) != 1) {
            ManagedExecutionEntity latest = findExecution(execution.getExecutionId());
            if (latest == null
                    || !requestId.equals(latest.getPendingApprovalRequestId())
                    || !interactionId.equals(latest.getPendingInteractionId())) {
                throw conflict("MANAGED_APPROVAL_CONFLICT",
                        "Managed Executor already has a different pending approval");
            }
        }
        execution.setPendingApprovalRequestId(requestId);
        execution.setPendingInteractionId(interactionId);
        execution.setApprovalDecision(null);
        execution.setApprovalCount((execution.getApprovalCount() == null
                ? 0 : execution.getApprovalCount()) + 1);
        return new ApprovalOpened(interactionId, requestId, kind);
    }

    @Transactional
    public ApprovalDecisionView resolve(ManagedExecutionEntity execution,
                                        String actorUserId,
                                        String interactionId,
                                        ApprovalDecisionRequest request) {
        if (execution == null || !StringUtils.hasText(interactionId) || request == null) {
            throw invalid("Managed Executor approval decision is required");
        }
        String normalizedInteractionId = boundedIdentifier(interactionId, "interactionId", 64);
        if (!normalizedInteractionId.equals(execution.getPendingInteractionId())
                || !StringUtils.hasText(execution.getPendingApprovalRequestId())) {
            throw conflict("MANAGED_APPROVAL_NOT_PENDING",
                    "Managed Executor approval is not pending for this execution");
        }
        RuntimeInteractionSessionEntity row = requireInteraction(normalizedInteractionId, execution);
        String normalizedActorUserId = boundedIdentifier(
                actorUserId, "actorUserId", 128);
        if (!normalizedActorUserId.equals(execution.getRequestedByUserId())
                || !normalizedActorUserId.equals(row.getUserId())) {
            throw forbidden("MANAGED_APPROVAL_ACTOR_FORBIDDEN",
                    "Managed Executor approval can only be decided by its requesting user");
        }
        String decision = decision(request.decision());
        String idempotencyKey = StringUtils.hasText(request.idempotencyKey())
                ? boundedIdentifier(request.idempotencyKey(), "idempotencyKey", 128)
                : normalizedInteractionId + ":" + decision;
        LocalDateTime now = LocalDateTime.now();
        boolean expired = row.getExpiresAt() != null && !row.getExpiresAt().isAfter(now);
        if (expired) decision = "decline";
        String submitted = json(Map.of("decision", decision));

        if ("RESUMING".equals(row.getStatus()) || "EXPIRED".equals(row.getStatus())) {
            if (!idempotencyKey.equals(row.getIdempotencyKey())
                    || !submitted.equals(row.getSubmittedPayloadJson())) {
                throw conflict("MANAGED_APPROVAL_IDEMPOTENCY_CONFLICT",
                        "Managed Executor approval was already decided differently");
            }
            return new ApprovalDecisionView(
                    execution.getExecutionId(), normalizedInteractionId,
                    execution.getPendingApprovalRequestId(), decision,
                    execution.getCommandSequence() == null ? 0L : execution.getCommandSequence(),
                    expired, true);
        }
        if (!"WAITING_USER".equals(row.getStatus())) {
            throw conflict("MANAGED_APPROVAL_NOT_PENDING",
                    "Managed Executor approval interaction is already closed");
        }
        String nextInteractionStatus = expired ? "EXPIRED" : "RESUMING";
        int revision = row.getRevision() == null ? 0 : row.getRevision();
        UpdateWrapper<RuntimeInteractionSessionEntity> decisionUpdate = new UpdateWrapper<>();
        decisionUpdate.eq("id", row.getId())
                .eq("status", "WAITING_USER")
                .eq("revision", revision)
                .set("status", nextInteractionStatus)
                .set("revision", revision + 1)
                .set("idempotency_key", idempotencyKey)
                .set("submitted_payload_json", submitted)
                .set("update_time", now);
        int updated = sessionMapper.update(null, decisionUpdate);
        if (updated != 1) {
            throw conflict("MANAGED_APPROVAL_CONFLICT",
                    "Managed Executor approval changed concurrently");
        }
        if (executionMapper.resolveApproval(
                execution.getExecutionId(), execution.getPendingApprovalRequestId(),
                normalizedInteractionId, decision, now) != 1) {
            throw conflict("MANAGED_APPROVAL_CONFLICT",
                    "Managed Executor approval execution fence was lost");
        }
        writeEvent(row.getId(), expired ? "EXPIRED" : "SUBMITTED", Map.of(
                "decision", decision,
                "executionId", execution.getExecutionId()),
                normalizedActorUserId);
        long nextSequence = (execution.getCommandSequence() == null ? 0L : execution.getCommandSequence()) + 1L;
        execution.setCommandSequence(nextSequence);
        execution.setApprovalDecision(decision);
        execution.setApprovalDecidedAt(now);
        return new ApprovalDecisionView(
                execution.getExecutionId(), normalizedInteractionId,
                execution.getPendingApprovalRequestId(), decision,
                nextSequence, expired, false);
    }

    public ApprovalView view(ManagedExecutionEntity execution) {
        if (execution == null || !StringUtils.hasText(execution.getPendingInteractionId())) {
            return null;
        }
        RuntimeInteractionSessionEntity row = requireInteraction(
                execution.getPendingInteractionId(), execution);
        JsonNode ui;
        try {
            ui = objectMapper.readTree(row.getUiRequestJson());
        } catch (Exception failure) {
            throw new ManagedExecutionException(500, "MANAGED_APPROVAL_VIEW_INVALID",
                    "Managed Executor approval view is unavailable");
        }
        if (ui == null || !ui.isObject()) {
            throw new ManagedExecutionException(500, "MANAGED_APPROVAL_VIEW_INVALID",
                    "Managed Executor approval view is unavailable");
        }
        return new ApprovalView(
                "reachai.managed-executor.approval.v1",
                execution.getExecutionId(),
                row.getId(),
                execution.getPendingApprovalRequestId(),
                row.getStatus(),
                ui,
                row.getExpiresAt(),
                row.getUpdateTime());
    }

    @Transactional
    public ApprovalClosed onResolved(ManagedExecutionEntity execution, SanitizedEvent event) {
        JsonNode data = data(event);
        String requestId = boundedIdentifier(data.path("approvalRequestId").asText(null),
                "approvalRequestId", 160);
        String workerDecision = workerDecision(data.path("decision").asText(null));
        if (!requestId.equals(execution.getPendingApprovalRequestId())) {
            return null; // Includes the permanently denied PERMISSIONS request path.
        }
        if (StringUtils.hasText(execution.getApprovalDecision())
                && !execution.getApprovalDecision().equals(workerDecision)) {
            throw conflict("MANAGED_APPROVAL_DECISION_MISMATCH",
                    "Worker approval result does not match the Runtime decision");
        }
        RuntimeInteractionSessionEntity row = sessionMapper.selectById(execution.getPendingInteractionId());
        LocalDateTime now = LocalDateTime.now();
        if (row != null && SOURCE_TYPE.equals(row.getSourceType())) {
            if ("WAITING_USER".equals(row.getStatus()) || "RESUMING".equals(row.getStatus())) {
                int revision = row.getRevision() == null ? 0 : row.getRevision();
                UpdateWrapper<RuntimeInteractionSessionEntity> completion = new UpdateWrapper<>();
                completion.eq("id", row.getId())
                        .eq("revision", revision)
                        .in("status", "WAITING_USER", "RESUMING")
                        .set("status", "COMPLETED")
                        .set("revision", revision + 1)
                        .set("result_json", json(Map.of("decision", workerDecision)))
                        .set("update_time", now);
                sessionMapper.update(null, completion);
            }
            writeEvent(row.getId(), "COMPLETED", Map.of(
                    "decision", workerDecision,
                    "executionId", execution.getExecutionId()), "worker");
        }
        executionMapper.closeApproval(execution.getExecutionId(), requestId, now);
        ApprovalClosed closed = new ApprovalClosed(
                execution.getPendingInteractionId(), requestId, workerDecision);
        execution.setPendingInteractionId(null);
        execution.setPendingApprovalRequestId(null);
        execution.setApprovalDecision(null);
        execution.setApprovalDecidedAt(null);
        return closed;
    }

    @Transactional
    public void onExecutionTerminal(ManagedExecutionEntity execution) {
        if (execution == null
                || !StringUtils.hasText(execution.getPendingInteractionId())
                || !StringUtils.hasText(execution.getPendingApprovalRequestId())) {
            return;
        }
        RuntimeInteractionSessionEntity row = sessionMapper.selectById(execution.getPendingInteractionId());
        LocalDateTime now = LocalDateTime.now();
        if (row != null && SOURCE_TYPE.equals(row.getSourceType())) {
            int revision = row.getRevision() == null ? 0 : row.getRevision();
            UpdateWrapper<RuntimeInteractionSessionEntity> cancellation = new UpdateWrapper<>();
            cancellation.eq("id", row.getId())
                    .eq("revision", revision)
                    .in("status", "WAITING_USER", "RESUMING")
                    .set("status", "CANCELLED")
                    .set("revision", revision + 1)
                    .set("update_time", now);
            int updated = sessionMapper.update(null, cancellation);
            if (updated == 1) {
                writeEvent(row.getId(), "CANCELLED", Map.of(
                        "executionId", execution.getExecutionId(),
                        "reason", "execution_terminal"), "runtime");
            }
        }
        executionMapper.closeApproval(
                execution.getExecutionId(), execution.getPendingApprovalRequestId(), now);
        execution.setPendingInteractionId(null);
        execution.setPendingApprovalRequestId(null);
        execution.setApprovalDecision(null);
        execution.setApprovalDecidedAt(null);
    }

    private RuntimeInteractionSessionEntity requireInteraction(
            String interactionId, ManagedExecutionEntity execution) {
        RuntimeInteractionSessionEntity row = sessionMapper.selectById(interactionId);
        if (row == null
                || !SOURCE_TYPE.equals(row.getSourceType())
                || !INTERACTION_TYPE.equals(row.getInteractionType())
                || !execution.getExecutionId().equals(row.getRunId())
                || !execution.getTenantId().equals(row.getTenantId())) {
            throw conflict("MANAGED_APPROVAL_NOT_FOUND",
                    "Managed Executor approval interaction was not found");
        }
        return row;
    }

    private ManagedExecutionEntity findExecution(String executionId) {
        return executionMapper.selectOne(new QueryWrapper<ManagedExecutionEntity>()
                .eq("execution_id", executionId));
    }

    private Map<String, Object> uiRequest(ManagedExecutionEntity execution,
                                          SanitizedEvent event,
                                          JsonNode data,
                                          String interactionId,
                                          String requestId,
                                          String kind) {
        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("schema", "reachai.managed-executor.approval-ui.v1");
        ui.put("interactionId", interactionId);
        ui.put("executionId", execution.getExecutionId());
        ui.put("approvalRequestId", requestId);
        ui.put("approvalKind", kind);
        ui.put("message", event.message());
        if (data.has("command") && data.get("command").isArray()) {
            ui.put("command", objectMapper.convertValue(data.get("command"), List.class));
        }
        if (data.has("reason")) ui.put("reason", data.path("reason").asText(""));
        ui.put("actions", List.of("approve_once", "reject"));
        return ui;
    }

    private JsonNode data(SanitizedEvent event) {
        try {
            JsonNode data = objectMapper.readTree(event.dataJson());
            if (data == null || !data.isObject()) throw invalid("Managed approval data is invalid");
            return data;
        } catch (ManagedExecutionException known) {
            throw known;
        } catch (Exception invalid) {
            throw invalid("Managed approval data is invalid");
        }
    }

    private String decision(String value) {
        if (!StringUtils.hasText(value)) throw invalid("Managed approval decision is required");
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "APPROVE", "ACCEPT" -> "accept";
            case "REJECT", "DECLINE", "CANCEL" -> "decline";
            default -> throw invalid("Managed approval decision is invalid");
        };
    }

    private String workerDecision(String value) {
        if (!StringUtils.hasText(value)) throw invalid("Managed approval result is invalid");
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "accept" -> "accept";
            case "decline", "cancel" -> "decline";
            default -> throw invalid("Managed approval result is invalid");
        };
    }

    private String enumText(String value, List<String> allowed, String field) {
        if (!StringUtils.hasText(value)) throw invalid("Managed " + field + " is required");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw invalid("Managed " + field + " is invalid");
        return normalized;
    }

    private String boundedIdentifier(String value, String field, int maximum) {
        if (!StringUtils.hasText(value)) throw invalid("Managed " + field + " is required");
        String normalized = value.trim();
        if (normalized.length() > maximum || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("Managed " + field + " is invalid");
        }
        return normalized;
    }

    private void writeEvent(String sessionId, String type, Map<String, Object> payload, String operator) {
        RuntimeInteractionEventEntity event = new RuntimeInteractionEventEntity();
        event.setSessionId(sessionId);
        event.setEventType(type);
        event.setPayloadJson(json(payload));
        event.setOperatorId(operator);
        event.setCreateTime(LocalDateTime.now());
        eventMapper.insert(event);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception failure) {
            throw invalid("Managed approval JSON is invalid");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private ManagedExecutionException invalid(String message) {
        return new ManagedExecutionException(400, "MANAGED_APPROVAL_INVALID", message);
    }

    private ManagedExecutionException forbidden(String code, String message) {
        return new ManagedExecutionException(403, code, message);
    }

    private ManagedExecutionException conflict(String code, String message) {
        return new ManagedExecutionException(409, code, message);
    }

    public record ApprovalOpened(String interactionId, String approvalRequestId, String approvalKind) {
    }

    public record ApprovalClosed(String interactionId, String approvalRequestId, String decision) {
    }
}
