package com.enterprise.ai.runtime.debug;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Owns short, committed session transitions. Workflow execution never runs in these transactions. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class RuntimeDebugSessionStore {
    private static final List<String> ACTIVE = List.of("RUNNING", "RESUMING");
    private final RuntimeExecutableDebugSessionMapper mapper;
    private final ObjectMapper json;
    private final RuntimeDebugExecutionLifecycle executionLifecycle;
    private final long executionTimeoutSeconds;
    private final Clock clock;

    @Autowired
    public RuntimeDebugSessionStore(RuntimeExecutableDebugSessionMapper mapper, ObjectMapper json,
            RuntimeDebugExecutionLifecycle executionLifecycle,
            @Value("${reachai.runtime.debug-session-expiry.execution-timeout-seconds:900}") long executionTimeoutSeconds) {
        this(mapper, json, executionLifecycle, executionTimeoutSeconds, Clock.systemDefaultZone());
    }

    public RuntimeDebugSessionStore(RuntimeExecutableDebugSessionMapper mapper, ObjectMapper json,
                                   RuntimeDebugExecutionLifecycle executionLifecycle) {
        this(mapper, json, executionLifecycle, 900);
    }

    RuntimeDebugSessionStore(RuntimeExecutableDebugSessionMapper mapper, ObjectMapper json,
                             RuntimeDebugExecutionLifecycle executionLifecycle, long executionTimeoutSeconds, Clock clock) {
        if (executionTimeoutSeconds <= 0) throw new IllegalArgumentException("debug execution timeout must be positive");
        this.mapper = mapper;
        this.json = json;
        this.executionLifecycle = Objects.requireNonNull(executionLifecycle, "executionLifecycle");
        this.executionTimeoutSeconds = executionTimeoutSeconds;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void create(RuntimeDebugSessionOwner owner, RuntimeExecutableDebugSessionEntity row) {
        Objects.requireNonNull(owner, "owner");
        row.setOwnerTenantId(owner.tenantId());
        row.setOwnerUserId(owner.userId());
        row.setExecutionDeadlineAt(now().plusSeconds(executionTimeoutSeconds));
        if (mapper.insert(row) != 1) throw conflict(row.getId(), "initial reservation was not persisted");
    }

    public RuntimeExecutableDebugSessionEntity findCreation(RuntimeDebugSessionOwner owner, String id, String hash) {
        var found=mapper.selectById(id);
        if(found==null)return null;
        requireCreationIdentity(owner,found,hash);
        return requireOwned(owner,id);
    }

    public CreationAdmission reserveCreation(RuntimeDebugSessionOwner owner, RuntimeExecutableDebugSessionEntity row) {
        if(row.getCreationRequestHash()==null)throw new IllegalArgumentException("creation request hash is required");
        try {
            create(owner,row);
            return new CreationAdmission(row,true);
        }catch(org.springframework.dao.DuplicateKeyException raced){
            var existing=locked(row.getId());
            requireCreationIdentity(owner,existing,row.getCreationRequestHash());
            return new CreationAdmission(reconcile(existing,now()),false);
        }
    }

    private void requireCreationIdentity(RuntimeDebugSessionOwner owner, RuntimeExecutableDebugSessionEntity row, String hash) {
        owner.requireMatches(row);
        if(hash==null || !hash.equals(row.getCreationRequestHash()))
            throw new IllegalArgumentException("debug creation idempotencyKey was already used for different content");
    }

    public record CreationAdmission(RuntimeExecutableDebugSessionEntity session, boolean created) { }

    /** A durable completion can be projected by any reader, including one after a process restart. */
    public RuntimeExecutableDebugSessionEntity require(String id) {
        var row = locked(id);
        return reconcile(row, now());
    }

    /** Public access must be checked before receipt projection or expiry changes any state. */
    public RuntimeExecutableDebugSessionEntity requireOwned(RuntimeDebugSessionOwner owner, String id) {
        Objects.requireNonNull(owner, "owner");
        if (!StringUtils.hasText(id)) throw new IllegalArgumentException("sessionId is required");
        var row = mapper.selectByIdForUpdate(id.trim());
        owner.requireMatches(row);
        return reconcile(row, now());
    }

    private RuntimeExecutableDebugSessionEntity reconcile(RuntimeExecutableDebugSessionEntity row, LocalDateTime now) {
        if (!ACTIVE.contains(row.getStatus())) return row;
        if (row.getResultJson() == null) return deadlineReached(row, now) ? expire(row, now) : row;
        CompletionReceipt receipt = receipt(row.getResultJson());
        if (receipt == null) return row;
        if (receipt.expectedRevision() != revision(row)) throw conflict(row.getId(), "completion revision does not match");
        Completion result = receipt.completion();
        var update = expected(row, row.getStatus(), receipt.expectedRevision())
                .eq("result_json", row.getResultJson())
                .set("status", result.status()).set("revision", receipt.expectedRevision() + 1)
                .set("current_node_id", result.currentNodeId())
                .set("state_snapshot_json", result.stateSnapshotJson()).set("messages_json", result.messagesJson())
                .set("steps_json", result.stepsJson()).set("ui_request_json", result.uiRequestJson())
                .set("debug_options_json", result.debugOptionsJson()).set("result_json", result.resultSummaryJson())
                .set("idempotency_key", result.idempotencyKey()).set("submitted_payload_json", result.submittedPayloadJson())
                .set("execution_deadline_at", null)
                .set("update_time", result.updatedAt());
        if (mapper.update(null, update) != 1) throw conflict(row.getId(), "completion projection lost its revision");
        return raw(row.getId());
    }

    /** Claim the exact checkpoint that was validated, never a freshly read revision with older state. */
    public boolean claim(RuntimeDebugSessionOwner owner, RuntimeExecutableDebugSessionEntity row, String idempotencyKey, String payload) {
        int revision = revision(row);
        var current = locked(row.getId());
        owner.requireMatches(current);
        if (!"SUSPENDED".equals(row.getStatus()) || !"SUSPENDED".equals(current.getStatus())
                || revision(current) != revision || !Objects.equals(current.getCurrentNodeId(), row.getCurrentNodeId())) return false;
        LocalDateTime now = now(); // Sample after acquiring the row lock, not before a potentially long wait.
        LocalDateTime deadline = now.plusSeconds(executionTimeoutSeconds);
        var update = expected(row, "SUSPENDED", revision)
                .eq("current_node_id", row.getCurrentNodeId())
                .set("status", "RESUMING").set("revision", revision + 1)
                .set("idempotency_key", StringUtils.hasText(idempotencyKey) ? idempotencyKey : null)
                .set("submitted_payload_json", payload).set("result_json", null)
                .set("execution_deadline_at", deadline).set("update_time", now);
        if (mapper.update(null, update) != 1) return false;
        row.setStatus("RESUMING");
        row.setRevision(revision + 1);
        row.setIdempotencyKey(StringUtils.hasText(idempotencyKey) ? idempotencyKey : null);
        row.setSubmittedPayloadJson(payload);
        row.setResultJson(null);
        row.setExecutionDeadlineAt(deadline);
        row.setUpdateTime(now);
        return true;
    }

    /** Commit the result separately from its display projection; a failed projection must not re-execute. */
    public boolean stageCompletion(RuntimeDebugSessionOwner owner, RuntimeExecutableDebugSessionEntity completed, String expectedStatus, int expectedRevision) {
        if (!ACTIVE.contains(expectedStatus)) throw conflict(completed.getId(), "unsupported completion source state");
        validateCompletionStatus(completed.getStatus());
        String receipt = encode(new CompletionReceipt(1, expectedRevision, new Completion(
                completed.getStatus(), completed.getCurrentNodeId(), completed.getStateSnapshotJson(),
                completed.getMessagesJson(), completed.getStepsJson(), completed.getUiRequestJson(),
                completed.getDebugOptionsJson(), completed.getResultJson(), completed.getIdempotencyKey(),
                completed.getSubmittedPayloadJson(), completed.getUpdateTime())));
        var current = locked(completed.getId());
        owner.requireMatches(current);
        if (!Objects.equals(current.getStatus(), expectedStatus) || revision(current) != expectedRevision) return false;
        // A receipt accepted before its deadline remains recoverable, even when projection happens later.
        if (current.getResultJson() != null) {
            if (Objects.equals(receipt, current.getResultJson())) return true;
            throw conflict(completed.getId(), "completion was not committed");
        }
        LocalDateTime now = now();
        if (deadlineReached(current, now)) {
            expire(current, now);
            return false;
        }
        var update = expected(completed, expectedStatus, expectedRevision).isNull("result_json")
                .gt("execution_deadline_at", now).set("result_json", receipt).set("update_time", now);
        if (mapper.update(null, update) == 1) return true;
        throw conflict(completed.getId(), "completion was not committed");
    }

    public RuntimeExecutableDebugSessionEntity cancel(RuntimeDebugSessionOwner owner, RuntimeExecutableDebugSessionEntity row, String messagesJson) {
        var current = locked(row.getId());
        owner.requireMatches(current);
        LocalDateTime now = now();
        current = reconcile(current, now);
        if ((!ACTIVE.contains(current.getStatus()) && !"SUSPENDED".equals(current.getStatus()))
                || !Objects.equals(row.getStatus(), current.getStatus()) || revision(row) != revision(current)) return current;
        var update = expected(row, row.getStatus(), revision(row));
        // A committed execution receipt wins over a later cancellation; a prior cancellation wins over late execution.
        if (ACTIVE.contains(row.getStatus())) update.isNull("result_json");
        update.set("status", "CANCELLED").set("revision", revision(row) + 1)
                .set("current_node_id", null).set("ui_request_json", null)
                .set("execution_deadline_at", null)
                .set("messages_json", messagesJson).set("update_time", now);
        mapper.update(null, update);
        return require(row.getId());
    }

    /** Candidate reads do not decide expiry; require() rechecks the current attempt under its row lock. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
    public List<String> findExpiredCandidateIds(int batchSize) {
        int limit = Math.max(1, Math.min(batchSize, 500));
        return mapper.selectList(new QueryWrapper<RuntimeExecutableDebugSessionEntity>()
                .select("id").in("status", ACTIVE).isNull("result_json")
                .and(query -> query.le("execution_deadline_at", now()).or().isNull("execution_deadline_at"))
                .orderByAsc("execution_deadline_at", "id").last("LIMIT " + limit))
                .stream().map(RuntimeExecutableDebugSessionEntity::getId).toList();
    }

    private RuntimeExecutableDebugSessionEntity expire(RuntimeExecutableDebugSessionEntity row, LocalDateTime now) {
        boolean missing = row.getExecutionDeadlineAt() == null;
        String code = missing ? "DEBUG_SESSION_EXECUTION_DEADLINE_MISSING" : "DEBUG_SESSION_EXECUTION_TIMEOUT";
        String answer = missing ? "调试执行期限缺失，结果无法确认。请核对运行记录和业务系统，勿直接重复调试。"
                : "调试执行已超时，结果无法确认。请核对运行记录和业务系统，勿直接重复调试。";
        String result = encode(Map.of("code", code, "answer", answer, "status", "EXPIRED", "outcome", "UNKNOWN",
                "retryable", false, "reconciliationRequired", true));
        // Preserve checkpoint, payload and idempotency identity. Expiry never dispatches or cancels business work.
        var update = expected(row, row.getStatus(), revision(row)).isNull("result_json")
                .set("status", "EXPIRED").set("revision", revision(row) + 1).set("ui_request_json", null)
                .set("result_json", result).set("update_time", now);
        if (mapper.update(null, update) != 1) throw conflict(row.getId(), "expiry was not committed");
        executionLifecycle.expire(row.getTraceId(), code, answer, now);
        return raw(row.getId());
    }

    private boolean deadlineReached(RuntimeExecutableDebugSessionEntity row, LocalDateTime now) {
        return row.getExecutionDeadlineAt() == null || !row.getExecutionDeadlineAt().isAfter(now);
    }

    private LocalDateTime now() { return LocalDateTime.now(clock).withNano(0); }

    private RuntimeExecutableDebugSessionEntity locked(String id) {
        if (!StringUtils.hasText(id)) throw new IllegalArgumentException("sessionId is required");
        var row = mapper.selectByIdForUpdate(id.trim());
        if (row == null) throw new IllegalArgumentException("debug session not found: " + id);
        return row;
    }

    private void validateCompletionStatus(String status) {
        var parsed = RuntimeDebugSessionStatus.parse(status);
        if (parsed == RuntimeDebugSessionStatus.RUNNING || parsed == RuntimeDebugSessionStatus.RESUMING)
            throw new IllegalArgumentException("debug completion must suspend or finish execution");
    }

    private UpdateWrapper<RuntimeExecutableDebugSessionEntity> expected(
            RuntimeExecutableDebugSessionEntity row, String status, int revision) {
        return new UpdateWrapper<RuntimeExecutableDebugSessionEntity>()
                .eq("id", row.getId()).eq("status", status).eq("revision", revision);
    }

    private RuntimeExecutableDebugSessionEntity raw(String id) {
        if (!StringUtils.hasText(id)) throw new IllegalArgumentException("sessionId is required");
        var row = mapper.selectById(id.trim());
        if (row == null) throw new IllegalArgumentException("debug session not found: " + id);
        return row;
    }

    private CompletionReceipt receipt(String value) {
        try {
            var node = json.readTree(value);
            // Existing terminal summaries are not incomplete receipts and are never backfilled as such.
            if (node == null || !node.has("completionSchema")) return null;
            // These fields identify a committed attempt. Jackson coercion/defaults must not
            // turn malformed evidence into schema 1 or the initial attempt's revision 0.
            var schema = node.get("completionSchema");
            if (!schema.isIntegralNumber() || !schema.canConvertToInt() || schema.intValue() != 1)
                throw new IllegalArgumentException("unsupported completion schema");
            var expectedRevision = node.get("expectedRevision");
            if (expectedRevision == null || !expectedRevision.isIntegralNumber()
                    || !expectedRevision.canConvertToInt() || expectedRevision.intValue() < 0)
                throw new IllegalArgumentException("completion revision must be a nonnegative integer");
            var result = json.treeToValue(node, CompletionReceipt.class);
            Objects.requireNonNull(result.completion(), "completion");
            validateCompletionStatus(result.completion().status());
            return result;
        } catch (Exception invalid) {
            throw new IllegalStateException("DEBUG_SESSION_COMPLETION_INVALID: stored completion cannot be read", invalid);
        }
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalArgumentException("debug completion serialization failed", invalid); }
    }

    private int revision(RuntimeExecutableDebugSessionEntity row) { return row.getRevision() == null ? 0 : row.getRevision(); }

    private IllegalStateException conflict(String id, String reason) {
        return new IllegalStateException("DEBUG_SESSION_PERSISTENCE_CONFLICT: " + reason + ": " + id);
    }

    private record CompletionReceipt(int completionSchema, int expectedRevision, Completion completion) { }
    private record Completion(String status, String currentNodeId, String stateSnapshotJson, String messagesJson,
                              String stepsJson, String uiRequestJson, String debugOptionsJson, String resultSummaryJson,
                              String idempotencyKey, String submittedPayloadJson, LocalDateTime updatedAt) { }
}
