package com.enterprise.ai.runtime.execution;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/** Interaction owns scoped persistence and atomic session/event transitions for Managed approvals. */
@Service
@RequiredArgsConstructor
public class RuntimeManagedApprovalInteractionStore {
    private static final String SOURCE = "MANAGED_EXECUTOR";
    private static final String TYPE = "CONFIRM_ACTION";
    private static final String ENGINE = "CODEX_HARNESS";
    private final RuntimeInteractionSessionMapper sessions;
    private final RuntimeInteractionEventMapper events;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.MANDATORY)
    public OpenResult open(Start start) {
        Scope scope = start.scope();
        var row = new RuntimeInteractionSessionEntity();
        row.setId(scope.interactionId());
        row.setSourceType(SOURCE);
        row.setRunId(scope.executionId());
        row.setTraceId(scope.executionId());
        row.setNodeId(scope.nodeId());
        row.setInteractionType(TYPE);
        row.setStatus("WAITING_USER");
        row.setRevision(0);
        row.setResumeCheckpointJson(start.checkpointJson());
        row.setCheckpointSchemaVersion(0);
        row.setExecutionEngineVersion(ENGINE);
        row.setUiRequestJson(start.uiRequestJson());
        row.setTenantId(scope.tenantId());
        row.setUserId(scope.userId());
        row.setCreateTime(start.created().at());
        row.setUpdateTime(start.created().at());
        row.setExpiresAt(start.expiresAt());
        try {
            sessions.insert(row);
        } catch (DuplicateKeyException duplicateSession) {
            var existing = sessions.selectForUpdate(scope.interactionId());
            if (!matches(existing, scope)
                    || !sameJson(existing.getResumeCheckpointJson(), start.checkpointJson())
                    || !sameJson(existing.getUiRequestJson(), start.uiRequestJson())) {
                return new OpenResult(null, false);
            }
            return new OpenResult(snapshot(existing), false);
        }
        // Event failures must propagate; they are not duplicate Session replays.
        writeEvent(scope, "CREATED", start.created());
        return new OpenResult(snapshot(row), true);
    }

    public Snapshot find(Scope scope) {
        var row = sessions.selectById(scope.interactionId());
        return matches(row, scope) ? snapshot(row) : null;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Snapshot lock(Scope scope) {
        var row = sessions.selectForUpdate(scope.interactionId());
        return matches(row, scope) ? snapshot(row) : null;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean submit(Scope scope, int revision, String idempotencyKey, String submittedJson,
                          boolean expired, Event event) {
        int changed = sessions.update(null, guarded(scope, revision)
                .eq("status", "WAITING_USER")
                .set("status", expired ? "EXPIRED" : "RESUMING")
                .set("idempotency_key", idempotencyKey)
                .set("submitted_payload_json", submittedJson)
                .set("update_time", event.at()));
        return recordChange(changed, scope, expired ? "EXPIRED" : "SUBMITTED", event);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean complete(Scope scope, int revision, String resultJson, Event event) {
        int changed = sessions.update(null, guarded(scope, revision)
                .in("status", "WAITING_USER", "RESUMING")
                .set("status", "COMPLETED")
                .set("result_json", resultJson)
                .set("update_time", event.at()));
        return recordChange(changed, scope, "COMPLETED", event);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancel(Scope scope, Event event) {
        int changed = sessions.update(null, scoped(scope)
                .in("status", "WAITING_USER", "RESUMING")
                .set("status", "CANCELLED")
                .setSql("revision = revision + 1")
                .set("update_time", event.at()));
        return recordChange(changed, scope, "CANCELLED", event);
    }

    private UpdateWrapper<RuntimeInteractionSessionEntity> guarded(Scope scope, int revision) {
        return scoped(scope).eq("revision", revision).set("revision", revision + 1);
    }

    private UpdateWrapper<RuntimeInteractionSessionEntity> scoped(Scope scope) {
        return new UpdateWrapper<RuntimeInteractionSessionEntity>()
                .eq("id", scope.interactionId()).eq("source_type", SOURCE)
                .eq("interaction_type", TYPE).eq("execution_engine_version", ENGINE)
                .eq("run_id", scope.executionId()).eq("trace_id", scope.executionId())
                .eq("tenant_id", scope.tenantId()).eq("user_id", scope.userId())
                .eq("node_id", scope.nodeId());
    }

    private boolean recordChange(int changed, Scope scope, String type, Event event) {
        if (changed != 1) return false;
        writeEvent(scope, type, event);
        return true;
    }

    private void writeEvent(Scope scope, String type, Event request) {
        var event = new RuntimeInteractionEventEntity();
        event.setSessionId(scope.interactionId());
        event.setEventType(type);
        event.setPayloadJson(request.payloadJson());
        event.setOperatorId(request.operatorId());
        event.setCreateTime(request.at());
        events.insert(event);
    }

    private boolean matches(RuntimeInteractionSessionEntity row, Scope scope) {
        return row != null && SOURCE.equals(row.getSourceType()) && TYPE.equals(row.getInteractionType())
                && ENGINE.equals(row.getExecutionEngineVersion())
                && Objects.equals(scope.interactionId(), row.getId())
                && Objects.equals(scope.executionId(), row.getRunId())
                && Objects.equals(scope.executionId(), row.getTraceId())
                && Objects.equals(scope.tenantId(), row.getTenantId())
                && Objects.equals(scope.userId(), row.getUserId())
                && Objects.equals(scope.nodeId(), row.getNodeId());
    }

    private boolean sameJson(String left, String right) {
        if (left == null || right == null) return Objects.equals(left, right);
        try { return Objects.equals(json.readTree(left), json.readTree(right)); }
        catch (Exception invalid) { return false; }
    }

    private Snapshot snapshot(RuntimeInteractionSessionEntity row) {
        return new Snapshot(row.getId(), row.getStatus(), row.getRevision() == null ? 0 : row.getRevision(),
                row.getIdempotencyKey(), row.getSubmittedPayloadJson(), row.getResultJson(),
                row.getUiRequestJson(), row.getExpiresAt(), row.getUpdateTime());
    }

    public record Scope(String interactionId, String executionId, String tenantId, String userId, String nodeId) { }
    public record Event(String payloadJson, String operatorId, LocalDateTime at) { }
    public record Start(Scope scope, String checkpointJson, String uiRequestJson, LocalDateTime expiresAt, Event created) { }
    public record Snapshot(String id, String status, int revision, String idempotencyKey,
                           String submittedPayloadJson, String resultJson, String uiRequestJson,
                           LocalDateTime expiresAt, LocalDateTime updateTime) { }
    public record OpenResult(Snapshot snapshot, boolean created) { }
}
