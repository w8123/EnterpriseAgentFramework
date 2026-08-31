package com.enterprise.ai.runtime.managed;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.runops.RuntimeRunEntity;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.runops.RuntimeRunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Idempotent RunOps root projection for every Managed Execution aggregate. */
@Service
@RequiredArgsConstructor
public class ManagedExecutionRunProjector {

    private final RuntimeRunMapper runMapper;
    private final ObjectMapper objectMapper;

    public void sync(ManagedExecutionEntity execution) {
        if (execution == null || execution.getExecutionId() == null) return;
        Projection projection = projection(execution);
        RuntimeRunEntity existing = runMapper.selectOne(
                Wrappers.<RuntimeRunEntity>lambdaQuery()
                        .eq(RuntimeRunEntity::getTraceId, execution.getExecutionId()));
        if (existing == null) {
            try {
                runMapper.insert(toEntity(execution, projection));
                return;
            } catch (DuplicateKeyException raced) {
                // Another transaction inserted the same trace; apply the current projection below.
            }
        }
        LocalDateTime updatedAt = execution.getUpdatedAt() == null
                ? LocalDateTime.now() : execution.getUpdatedAt();
        runMapper.updateManagedExecutionProjection(
                execution.getExecutionId(),
                projection.status(),
                projection.suspensionReason(),
                projection.outputSummary(),
                projection.errorCode(),
                projection.errorMessage(),
                projection.latencyMs(),
                execution.getApprovalCount() == null ? 0 : execution.getApprovalCount(),
                metadata(execution),
                projection.endedAt(),
                updatedAt);
    }

    private RuntimeRunEntity toEntity(ManagedExecutionEntity execution, Projection projection) {
        LocalDateTime createdAt = execution.getCreatedAt() == null
                ? LocalDateTime.now() : execution.getCreatedAt();
        RuntimeRunEntity run = new RuntimeRunEntity();
        run.setTraceId(execution.getExecutionId());
        run.setRunType("MANAGED_EXECUTION");
        run.setEntryType(execution.getSourceType());
        run.setStatus(projection.status());
        run.setSuspensionReason(projection.suspensionReason());
        run.setProjectCode(execution.getProjectCode());
        run.setTenantId(execution.getTenantId());
        run.setUserId(execution.getRequestedByUserId());
        run.setExternalUserId(execution.getRequestedByUserId());
        run.setGlobalUserId(execution.getRequestedByUserId());
        run.setRuntimeType("CODEX_HARNESS");
        run.setInputSummary(json(Map.of(
                "objectiveSha256", execution.getObjectiveSha256(),
                "sourceType", execution.getSourceType())));
        run.setOutputSummary(projection.outputSummary());
        run.setErrorCode(projection.errorCode());
        run.setErrorMessage(projection.errorMessage());
        run.setLatencyMs(projection.latencyMs());
        run.setTokenCost(0);
        run.setPlanCount(0);
        run.setReplanCount(0);
        run.setWorkflowCallCount(0);
        run.setToolCallCount(0);
        run.setGuardDenyCount(0);
        run.setApprovalCount(execution.getApprovalCount() == null ? 0 : execution.getApprovalCount());
        run.setSnapshotJson(snapshot(execution));
        run.setMetadataJson(metadata(execution));
        run.setStartedAt(createdAt);
        run.setEndedAt(projection.endedAt());
        run.setCreatedAt(createdAt);
        run.setUpdatedAt(execution.getUpdatedAt() == null ? createdAt : execution.getUpdatedAt());
        return run;
    }

    private Projection projection(ManagedExecutionEntity execution) {
        ManagedExecutionStatus status = ManagedExecutionStatus.parse(execution.getStatus());
        LocalDateTime endedAt = status.terminal()
                ? (execution.getCompletedAt() == null ? execution.getUpdatedAt() : execution.getCompletedAt())
                : null;
        LocalDateTime startedAt = execution.getStartedAt() == null
                ? execution.getCreatedAt() : execution.getStartedAt();
        Integer latency = endedAt == null || startedAt == null ? null
                : (int) Math.min(Integer.MAX_VALUE,
                Math.max(0L, Duration.between(startedAt, endedAt).toMillis()));
        return switch (status) {
            case WAITING_APPROVAL -> new Projection(
                    RuntimeRunStatus.SUSPENDED.name(), "APPROVAL",
                    "Managed Executor is waiting for one-shot approval", null, null, null, null);
            case WAITING_USER -> new Projection(
                    RuntimeRunStatus.SUSPENDED.name(), "USER_INPUT",
                    "Managed Executor is waiting for user input", null, null, null, null);
            case SUCCEEDED -> new Projection(
                    RuntimeRunStatus.COMPLETED.name(), null,
                    "Managed Executor evidence was independently verified", null, null, endedAt, latency);
            case CANCELLED -> new Projection(
                    RuntimeRunStatus.CANCELLED.name(), null,
                    "Managed Executor execution was cancelled", execution.getErrorCode(),
                    execution.getErrorMessage(), endedAt, latency);
            case TIMED_OUT -> new Projection(
                    RuntimeRunStatus.TIMED_OUT.name(), null,
                    "Managed Executor worker lease expired", execution.getErrorCode(),
                    execution.getErrorMessage(), endedAt, latency);
            case FAILED -> new Projection(
                    RuntimeRunStatus.FAILED.name(), null,
                    "Managed Executor execution failed", execution.getErrorCode(),
                    execution.getErrorMessage(), endedAt, latency);
            default -> new Projection(
                    RuntimeRunStatus.RUNNING.name(), null,
                    "Managed Executor status: " + status.name(), null, null, null, null);
        };
    }

    private String snapshot(ManagedExecutionEntity execution) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema", "reachai.managed-execution.run-snapshot.v1");
        value.put("executorProvider", execution.getExecutorProvider());
        value.put("sandboxProfile", execution.getSandboxProfile());
        value.put("acceptanceProfile", execution.getAcceptanceProfile());
        value.put("modelRef", execution.getModelRef());
        value.put("sourceRef", execution.getSourceRef());
        value.put("objectiveSha256", execution.getObjectiveSha256());
        return json(value);
    }

    private String metadata(ManagedExecutionEntity execution) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema", "reachai.managed-execution.run-metadata.v1");
        value.put("managedExecutionId", execution.getExecutionId());
        value.put("managedStatus", execution.getStatus());
        value.put("cleanupStatus", execution.getCleanupStatus());
        value.put("lastEventSequence", execution.getLastEventSequence());
        value.put("pendingInteractionId", execution.getPendingInteractionId());
        return json(value);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("Managed Execution RunOps projection serialization failed", failure);
        }
    }

    private record Projection(
            String status,
            String suspensionReason,
            String outputSummary,
            String errorCode,
            String errorMessage,
            LocalDateTime endedAt,
            Integer latencyMs) {
    }
}
