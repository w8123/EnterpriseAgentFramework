package com.enterprise.ai.runtime.runops;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

/** RunOps owns Guard persistence; producers provide immutable, sanitized decision facts. */
@Service
@RequiredArgsConstructor
public class RuntimeGuardDecisionWriter {
    private final RuntimeGuardDecisionLogMapper logs;

    public void append(Decision decision) {
        Objects.requireNonNull(decision, "decision");
        var row = new RuntimeGuardDecisionLogEntity();
        row.setTraceId(decision.traceId());
        row.setProjectId(decision.projectId());
        row.setProjectCode(decision.projectCode());
        row.setEnvironment(decision.environment());
        row.setTenantId(decision.tenantId());
        row.setDecisionType(decision.decisionType());
        row.setTargetKind(decision.targetKind());
        row.setTargetName(decision.targetName());
        row.setDecision(decision.decision());
        row.setReason(decision.reason());
        row.setMetadataJson(decision.metadataJson());
        row.setCreatedAt(decision.createdAt());
        logs.insert(row);
    }

    public record Decision(String traceId, Long projectId, String projectCode, String environment, String tenantId,
                           String decisionType, String targetKind, String targetName, String decision,
                           String reason, String metadataJson, LocalDateTime createdAt) { }
}
