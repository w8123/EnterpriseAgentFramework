package com.enterprise.ai.runtime.managed;

import com.enterprise.ai.runtime.managed.ManagedExecutionPayloadSanitizer.SanitizedEvent;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ApprovalDecisionRequest;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ApprovalDecisionView;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ApprovalView;
import com.enterprise.ai.runtime.execution.RuntimeManagedApprovalInteractionStore;
import com.enterprise.ai.runtime.execution.RuntimeManagedApprovalInteractionStore.Scope;
import com.enterprise.ai.runtime.execution.RuntimeManagedApprovalInteractionStore.Snapshot;
import com.enterprise.ai.runtime.execution.RuntimeManagedApprovalInteractionStore.Start;
import com.enterprise.ai.runtime.execution.RuntimeManagedApprovalInteractionStore.Event;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
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

    private final RuntimeManagedApprovalInteractionStore interactions;
    private final ManagedExecutionMapper executionMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public ApprovalOpened onRequested(ManagedExecutionEntity execution, SanitizedEvent event) {
        JsonNode data = data(event);
        String kind = enumText(data.path("approvalKind").asText(null),
                List.of("COMMAND", "FILE_CHANGE", "PERMISSIONS"), "approvalKind");
        if ("PERMISSIONS".equals(kind)) return null;
        String requestId = boundedIdentifier(data.path("approvalRequestId").asText(null), "approvalRequestId", 160);
        ManagedExecutionEntity current = locked(execution);
        String interactionId = "mei_" + sha256(current.getExecutionId() + "\n" + requestId).substring(0, 32);
        boolean pending = StringUtils.hasText(current.getPendingInteractionId())
                || StringUtils.hasText(current.getPendingApprovalRequestId());
        if (pending && (!requestId.equals(current.getPendingApprovalRequestId())
                || !interactionId.equals(current.getPendingInteractionId()))) {
            throw conflict("MANAGED_APPROVAL_CONFLICT", "Managed Executor already has a different pending approval");
        }
        if (!"WAITING_APPROVAL".equals(current.getStatus())) {
            throw conflict("MANAGED_APPROVAL_NOT_PENDING", "Managed Executor is not waiting for approval");
        }
        LocalDateTime now = LocalDateTime.now();
        var opened = interactions.open(new Start(scope(current, interactionId, requestId),
                json(Map.of("schema", "reachai.managed-executor.approval-checkpoint.v1",
                        "executionId", current.getExecutionId(), "approvalRequestId", requestId, "approvalKind", kind)),
                json(uiRequest(current, event, data, interactionId, requestId, kind)),
                now.plusSeconds(Math.max(30, current.getApprovalTimeoutSeconds())),
                new Event(json(Map.of("executionId", current.getExecutionId(), "approvalRequestId", requestId,
                        "approvalKind", kind)), "runtime", now)));
        boolean matchesExistingBinding = opened.created() ? !pending : pending;
        if (opened.snapshot() == null || !matchesExistingBinding
                || !List.of("WAITING_USER", "RESUMING", "EXPIRED").contains(opened.snapshot().status())) {
            throw conflict("MANAGED_APPROVAL_REPLAY_MISMATCH", "Managed approval replay does not match its open request");
        }
        if (opened.created() && executionMapper.openApproval(current.getExecutionId(), requestId, interactionId, now) != 1) {
            throw conflict("MANAGED_APPROVAL_CONFLICT", "Managed approval execution fence was lost");
        }
        copyApprovalState(executionMapper.selectForUpdate(current.getExecutionId()), execution);
        return new ApprovalOpened(interactionId, requestId, kind);
    }

    @Transactional
    public ApprovalDecisionView resolve(ManagedExecutionEntity execution, String actorUserId,
                                        String interactionId, ApprovalDecisionRequest request) {
        if (request == null) throw invalid("Managed Executor approval decision is required");
        ManagedExecutionEntity current = locked(execution);
        String id = boundedIdentifier(interactionId, "interactionId", 64);
        if (!id.equals(current.getPendingInteractionId()) || !StringUtils.hasText(current.getPendingApprovalRequestId())) {
            throw conflict("MANAGED_APPROVAL_NOT_PENDING", "Managed Executor approval is not pending for this execution");
        }
        Snapshot row = requireInteraction(id, current);
        String actor = boundedIdentifier(actorUserId, "actorUserId", 128);
        if (!actor.equals(current.getRequestedByUserId())) {
            throw forbidden("MANAGED_APPROVAL_ACTOR_FORBIDDEN", "Managed approval can only be decided by its requesting user");
        }
        String requestedDecision = decision(request.decision());
        String key = StringUtils.hasText(request.idempotencyKey())
                ? boundedIdentifier(request.idempotencyKey(), "idempotencyKey", 128) : id + ":" + requestedDecision;
        if ("RESUMING".equals(row.status()) || "EXPIRED".equals(row.status())) {
            var replay = replayDecision(current, row, key, requestedDecision);
            copyApprovalState(current, execution);
            return replay;
        }
        if (!"WAITING_USER".equals(row.status())) {
            throw conflict("MANAGED_APPROVAL_NOT_PENDING", "Managed approval interaction is already closed");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean expired = row.expiresAt() != null && !row.expiresAt().isAfter(now);
        String effectiveDecision = expired ? "decline" : requestedDecision;
        long commandSequence = Math.addExact(current.getCommandSequence() == null ? 0L : current.getCommandSequence(), 1L);
        String submitted = json(Map.of("schema", "reachai.managed-executor.approval-decision.v1",
                "requestedDecision", requestedDecision, "decision", effectiveDecision,
                "commandSequence", commandSequence, "expired", expired));
        if (!interactions.submit(scope(current, id, current.getPendingApprovalRequestId()), row.revision(), key,
                submitted, expired, new Event(json(Map.of("decision", effectiveDecision,
                        "executionId", current.getExecutionId())), actor, now))) {
            throw conflict("MANAGED_APPROVAL_CONFLICT", "Managed approval changed concurrently");
        }
        if (executionMapper.resolveApproval(current.getExecutionId(), current.getPendingApprovalRequestId(),
                id, effectiveDecision, now) != 1) {
            throw conflict("MANAGED_APPROVAL_CONFLICT", "Managed approval execution fence was lost");
        }
        copyApprovalState(executionMapper.selectForUpdate(current.getExecutionId()), execution);
        return new ApprovalDecisionView(current.getExecutionId(), id, current.getPendingApprovalRequestId(),
                effectiveDecision, commandSequence, expired, false);
    }

    private ApprovalDecisionView replayDecision(ManagedExecutionEntity current, Snapshot row,
                                                 String key, String requestedDecision) {
        try {
            JsonNode submitted = objectMapper.readTree(row.submittedPayloadJson());
            String effective = submitted.path("decision").asText();
            long sequence = submitted.path("commandSequence").asLong(-1);
            boolean expired = submitted.path("expired").asBoolean();
            if (!"reachai.managed-executor.approval-decision.v1".equals(submitted.path("schema").asText())
                    || !key.equals(row.idempotencyKey())
                    || !requestedDecision.equals(submitted.path("requestedDecision").asText())
                    || !effective.equals(current.getApprovalDecision())
                    || sequence < 1 || sequence > (current.getCommandSequence() == null ? 0L : current.getCommandSequence())
                    || expired != "EXPIRED".equals(row.status())) {
                throw new IllegalArgumentException("Decision receipt mismatch");
            }
            return new ApprovalDecisionView(current.getExecutionId(), row.id(), current.getPendingApprovalRequestId(),
                    effective, sequence, expired, true);
        } catch (Exception invalidReceipt) {
            throw conflict("MANAGED_APPROVAL_IDEMPOTENCY_CONFLICT", "Managed approval was already decided differently");
        }
    }

    public ApprovalView view(ManagedExecutionEntity execution) {
        if (execution == null || !StringUtils.hasText(execution.getPendingInteractionId())) return null;
        Snapshot row = checkedInteraction(interactions.find(pendingScope(execution.getPendingInteractionId(), execution)));
        JsonNode ui;
        try { ui = objectMapper.readTree(row.uiRequestJson()); }
        catch (Exception failure) {
            throw new ManagedExecutionException(500, "MANAGED_APPROVAL_VIEW_INVALID", "Managed approval view is unavailable");
        }
        if (ui == null || !ui.isObject()) {
            throw new ManagedExecutionException(500, "MANAGED_APPROVAL_VIEW_INVALID", "Managed approval view is unavailable");
        }
        return new ApprovalView("reachai.managed-executor.approval.v1", execution.getExecutionId(), row.id(),
                execution.getPendingApprovalRequestId(), row.status(), ui, row.expiresAt(), row.updateTime());
    }

    @Transactional
    public ApprovalClosed onResolved(ManagedExecutionEntity execution, SanitizedEvent event) {
        JsonNode data = data(event);
        String requestId = boundedIdentifier(data.path("approvalRequestId").asText(null), "approvalRequestId", 160);
        String workerDecision = workerDecision(data.path("decision").asText(null));
        ManagedExecutionEntity current = locked(execution);
        if (!requestId.equals(current.getPendingApprovalRequestId())) {
            copyApprovalState(current, execution);
            return null;
        }
        if ("accept".equals(workerDecision) && !StringUtils.hasText(current.getApprovalDecision())) {
            throw conflict("MANAGED_APPROVAL_DECISION_MISSING", "Worker acceptance requires a recorded Runtime approval");
        }
        if (StringUtils.hasText(current.getApprovalDecision()) && !current.getApprovalDecision().equals(workerDecision)) {
            throw conflict("MANAGED_APPROVAL_DECISION_MISMATCH", "Worker approval result does not match the Runtime decision");
        }
        Snapshot row = requireInteraction(current.getPendingInteractionId(), current);
        LocalDateTime now = LocalDateTime.now();
        if ("WAITING_USER".equals(row.status()) || "RESUMING".equals(row.status())) {
            if ("accept".equals(workerDecision) && !"RESUMING".equals(row.status())) {
                throw conflict("MANAGED_APPROVAL_CONFLICT", "Runtime approval interaction is not submitted");
            }
            if (!interactions.complete(scope(current, row.id(), requestId), row.revision(),
                    json(Map.of("decision", workerDecision)), new Event(json(Map.of("decision", workerDecision,
                            "executionId", current.getExecutionId())), "worker", now))) {
                throw conflict("MANAGED_APPROVAL_CONFLICT", "Managed approval changed concurrently");
            }
        } else if ("COMPLETED".equals(row.status())) {
            try {
                if (!workerDecision.equals(objectMapper.readTree(row.resultJson()).path("decision").asText())) {
                    throw new IllegalArgumentException("Result mismatch");
                }
            } catch (Exception mismatch) {
                throw conflict("MANAGED_APPROVAL_DECISION_MISMATCH", "Worker result differs from the completed interaction");
            }
        } else if (!"decline".equals(workerDecision)) {
            throw conflict("MANAGED_APPROVAL_CONFLICT", "A closed interaction cannot acknowledge acceptance");
        }
        String id = current.getPendingInteractionId();
        closePending(current, now);
        copyApprovalState(executionMapper.selectForUpdate(current.getExecutionId()), execution);
        return new ApprovalClosed(id, requestId, workerDecision);
    }

    @Transactional
    public void onExecutionTerminal(ManagedExecutionEntity execution) {
        if (execution == null) return;
        ManagedExecutionEntity current = locked(execution);
        if (!ManagedExecutionStatus.parse(current.getStatus()).terminal()) return;
        if (!StringUtils.hasText(current.getPendingInteractionId()) || !StringUtils.hasText(current.getPendingApprovalRequestId())) {
            copyApprovalState(current, execution);
            return;
        }
        Scope scope = scope(current, current.getPendingInteractionId(), current.getPendingApprovalRequestId());
        LocalDateTime now = LocalDateTime.now();
        interactions.cancel(scope, new Event(json(Map.of("executionId", current.getExecutionId(),
                "reason", "execution_terminal")), "runtime", now));
        closePending(current, now);
        copyApprovalState(executionMapper.selectForUpdate(current.getExecutionId()), execution);
    }

    private void closePending(ManagedExecutionEntity current, LocalDateTime now) {
        if (executionMapper.closeApproval(current.getExecutionId(), current.getPendingApprovalRequestId(),
                current.getPendingInteractionId(), now) != 1) {
            throw conflict("MANAGED_APPROVAL_CONFLICT", "Managed approval execution fence was lost");
        }
    }

    private Snapshot requireInteraction(String interactionId, ManagedExecutionEntity execution) {
        return checkedInteraction(interactions.lock(pendingScope(interactionId, execution)));
    }

    private Scope pendingScope(String interactionId, ManagedExecutionEntity execution) {
        if (!StringUtils.hasText(interactionId) || !StringUtils.hasText(execution.getPendingApprovalRequestId())) {
            throw conflict("MANAGED_APPROVAL_NOT_FOUND", "Managed approval interaction was not found");
        }
        return scope(execution, interactionId, execution.getPendingApprovalRequestId());
    }

    private Snapshot checkedInteraction(Snapshot row) {
        if (row == null) throw conflict("MANAGED_APPROVAL_NOT_FOUND", "Managed approval interaction was not found");
        return row;
    }

    private Scope scope(ManagedExecutionEntity execution, String interactionId, String requestId) {
        return new Scope(interactionId, execution.getExecutionId(), execution.getTenantId(),
                execution.getRequestedByUserId(), "approval:" + sha256(requestId).substring(0, 24));
    }

    private ManagedExecutionEntity locked(ManagedExecutionEntity supplied) {
        if (supplied == null) throw invalid("Managed execution is required for approval");
        String id = boundedIdentifier(supplied.getExecutionId(), "executionId", 64);
        ManagedExecutionEntity current = executionMapper.selectForUpdate(id);
        if (current == null || !java.util.Objects.equals(current.getTenantId(), supplied.getTenantId())
                || !java.util.Objects.equals(current.getProjectCode(), supplied.getProjectCode())
                || !java.util.Objects.equals(current.getRequestedByUserId(), supplied.getRequestedByUserId())) {
            throw conflict("MANAGED_APPROVAL_NOT_FOUND", "Managed execution approval owner was not found");
        }
        return current;
    }

    private void copyApprovalState(ManagedExecutionEntity current, ManagedExecutionEntity target) {
        if (current == null) throw conflict("MANAGED_APPROVAL_CONFLICT", "Managed execution disappeared during approval");
        target.setPendingApprovalRequestId(current.getPendingApprovalRequestId());
        target.setPendingInteractionId(current.getPendingInteractionId());
        target.setApprovalDecision(current.getApprovalDecision());
        target.setApprovalDecidedAt(current.getApprovalDecidedAt());
        target.setApprovalCount(current.getApprovalCount());
        target.setCommandSequence(current.getCommandSequence());
        target.setVersion(current.getVersion());
        target.setUpdatedAt(current.getUpdatedAt());
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
