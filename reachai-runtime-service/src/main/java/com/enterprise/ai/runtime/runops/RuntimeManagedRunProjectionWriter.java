package com.enterprise.ai.runtime.runops;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/** RunOps owns persistence; the caller projects its locked aggregate in the same transaction. */
@Service
@RequiredArgsConstructor
public class RuntimeManagedRunProjectionWriter {
    private static final String RUN_TYPE = "MANAGED_EXECUTION";
    private final RuntimeRunMapper runs;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.MANDATORY)
    public void sync(Projection projection) {
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(projection.executionId(), "executionId");
        RuntimeRunStatus.parse(projection.status());
        var existing = runs.selectOne(Wrappers.<RuntimeRunEntity>lambdaQuery()
                .eq(RuntimeRunEntity::getTraceId, projection.executionId()));
        if (existing == null) {
            try {
                runs.insert(toEntity(projection));
                return;
            } catch (DuplicateKeyException raced) {
                // Read the winning row with current-read semantics before checking its identity.
            }
        }
        // Lock only an existing/winning row; avoid an initial missing-row gap lock before insert.
        existing = runs.selectManagedProjectionForUpdate(projection.executionId());
        requireIdentity(existing, projection);
        runs.update(null, Wrappers.<RuntimeRunEntity>lambdaUpdate()
                .eq(RuntimeRunEntity::getId, existing.getId())
                .eq(RuntimeRunEntity::getTraceId, projection.executionId())
                .eq(RuntimeRunEntity::getRunType, RUN_TYPE)
                .set(RuntimeRunEntity::getStatus, projection.status())
                .set(RuntimeRunEntity::getSuspensionReason, projection.suspensionReason())
                .set(RuntimeRunEntity::getOutputSummary, projection.outputSummary())
                .set(RuntimeRunEntity::getErrorCode, projection.errorCode())
                .set(RuntimeRunEntity::getErrorMessage, projection.errorMessage())
                .set(RuntimeRunEntity::getLatencyMs, projection.latencyMs())
                .set(RuntimeRunEntity::getApprovalCount, projection.approvalCount())
                .set(RuntimeRunEntity::getMetadataJson, projection.metadataJson())
                .set(RuntimeRunEntity::getEndedAt, projection.endedAt())
                .set(RuntimeRunEntity::getUpdatedAt, projection.updatedAt()));
    }

    private void requireIdentity(RuntimeRunEntity run, Projection projection) {
        if (run == null || !RUN_TYPE.equals(run.getRunType())
                || !Objects.equals(run.getTraceId(), projection.executionId())
                || !"CODEX_HARNESS".equals(run.getRuntimeType())
                || !Objects.equals(run.getTenantId(), projection.tenantId())
                || !Objects.equals(run.getProjectCode(), projection.projectCode())
                || !Objects.equals(run.getEntryType(), projection.sourceType())
                || !Objects.equals(run.getUserId(), projection.requestedByUserId())
                || !Objects.equals(run.getExternalUserId(), projection.requestedByUserId())
                || !Objects.equals(run.getGlobalUserId(), projection.requestedByUserId())
                || !sameJson(run.getSnapshotJson(), projection.snapshotJson())
                || !sameJson(run.getInputSummary(), projection.inputSummary())) {
            throw new IllegalStateException("RUNOPS_MANAGED_PROJECTION_IDENTITY_CONFLICT: root identity differs");
        }
    }

    private boolean sameJson(String stored, String projected) {
        if (stored == null || projected == null) return Objects.equals(stored, projected);
        try {
            return Objects.equals(json.readTree(stored), json.readTree(projected));
        } catch (Exception invalidSnapshot) {
            return false;
        }
    }

    private RuntimeRunEntity toEntity(Projection projection) {
        var run = new RuntimeRunEntity();
        run.setTraceId(projection.executionId());
        run.setRunType(RUN_TYPE);
        run.setEntryType(projection.sourceType());
        run.setStatus(projection.status());
        run.setSuspensionReason(projection.suspensionReason());
        run.setProjectCode(projection.projectCode());
        run.setTenantId(projection.tenantId());
        run.setUserId(projection.requestedByUserId());
        run.setExternalUserId(projection.requestedByUserId());
        run.setGlobalUserId(projection.requestedByUserId());
        run.setRuntimeType("CODEX_HARNESS");
        run.setInputSummary(projection.inputSummary());
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
        run.setApprovalCount(projection.approvalCount());
        run.setSnapshotJson(projection.snapshotJson());
        run.setMetadataJson(projection.metadataJson());
        run.setStartedAt(projection.createdAt());
        run.setEndedAt(projection.endedAt());
        run.setCreatedAt(projection.createdAt());
        run.setUpdatedAt(projection.updatedAt());
        return run;
    }

    @Builder
    public record Projection(String executionId, String sourceType, String projectCode, String tenantId,
                             String requestedByUserId, String status, String suspensionReason,
                             String inputSummary, String outputSummary, String errorCode, String errorMessage,
                             Integer latencyMs, int approvalCount, String snapshotJson, String metadataJson,
                             LocalDateTime createdAt, LocalDateTime endedAt, LocalDateTime updatedAt) { }
}
