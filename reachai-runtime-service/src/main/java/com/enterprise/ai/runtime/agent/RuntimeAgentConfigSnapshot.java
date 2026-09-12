package com.enterprise.ai.runtime.agent;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/** Immutable Agent-owned execution data. Persistence rows never leave the owner query. */
@Value
@Builder(toBuilder = true)
public class RuntimeAgentConfigSnapshot {
    Long id;
    String agentId;
    Integer versionNo;
    String status;
    String runtimeType;
    String systemPrompt;
    String modelInstanceId;
    Integer maxPlanSteps;
    Integer maxWorkflowCalls;
    Integer maxReplans;
    Integer totalTimeoutMs;
    Integer workflowTimeoutMs;
    Integer pageBridgeTimeoutMs;
    Boolean parallelReadOnly;
    String policyProfile;
    String toolCatalogMode;
    String configJson;
    String publishedBy;
    LocalDateTime publishedAt;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;

    public static RuntimeAgentConfigSnapshot fromEntity(RuntimeAgentConfigVersionEntity entity) {
        if (entity == null) return null;
        return RuntimeAgentConfigSnapshot.builder()
                .id(entity.getId())
                .agentId(entity.getAgentId())
                .versionNo(entity.getVersionNo())
                .status(entity.getStatus())
                .runtimeType(entity.getRuntimeType())
                .systemPrompt(entity.getSystemPrompt())
                .modelInstanceId(entity.getModelInstanceId())
                .maxPlanSteps(entity.getMaxPlanSteps())
                .maxWorkflowCalls(entity.getMaxWorkflowCalls())
                .maxReplans(entity.getMaxReplans())
                .totalTimeoutMs(entity.getTotalTimeoutMs())
                .workflowTimeoutMs(entity.getWorkflowTimeoutMs())
                .pageBridgeTimeoutMs(entity.getPageBridgeTimeoutMs())
                .parallelReadOnly(entity.getParallelReadOnly())
                .policyProfile(entity.getPolicyProfile())
                .toolCatalogMode(entity.getToolCatalogMode())
                .configJson(entity.getConfigJson())
                .publishedBy(entity.getPublishedBy())
                .publishedAt(entity.getPublishedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
