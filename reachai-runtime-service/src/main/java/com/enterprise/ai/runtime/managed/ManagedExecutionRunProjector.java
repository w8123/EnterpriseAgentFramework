package com.enterprise.ai.runtime.managed;

import com.enterprise.ai.runtime.runops.RuntimeManagedRunProjectionWriter;
import com.enterprise.ai.runtime.runops.RuntimeRunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Idempotent RunOps root projection for every Managed Execution aggregate. */
@Service
@RequiredArgsConstructor
public class ManagedExecutionRunProjector {

    private final ManagedExecutionMapper executions;
    private final RuntimeManagedRunProjectionWriter writer;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void sync(String executionId) {
        if (executionId == null || executionId.isBlank()) return;
        ManagedExecutionEntity execution = executions.selectForUpdate(executionId.trim());
        if (execution == null) throw new IllegalStateException("Managed Execution is missing during Run projection");
        Projection projection = projection(execution);
        LocalDateTime createdAt = execution.getCreatedAt() == null ? LocalDateTime.now() : execution.getCreatedAt();
        writer.sync(RuntimeManagedRunProjectionWriter.Projection.builder()
                .executionId(execution.getExecutionId()).sourceType(execution.getSourceType())
                .projectCode(execution.getProjectCode()).tenantId(execution.getTenantId())
                .requestedByUserId(execution.getRequestedByUserId())
                .status(projection.status()).suspensionReason(projection.suspensionReason())
                .inputSummary(json(Map.of("objectiveSha256", execution.getObjectiveSha256(),
                        "sourceType", execution.getSourceType())))
                .outputSummary(projection.outputSummary()).errorCode(projection.errorCode())
                .errorMessage(projection.errorMessage()).latencyMs(projection.latencyMs())
                .approvalCount(execution.getApprovalCount() == null ? 0 : execution.getApprovalCount())
                .snapshotJson(snapshot(execution)).metadataJson(metadata(execution))
                .createdAt(createdAt).endedAt(projection.endedAt())
                .updatedAt(execution.getUpdatedAt() == null ? createdAt : execution.getUpdatedAt())
                .build());
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
