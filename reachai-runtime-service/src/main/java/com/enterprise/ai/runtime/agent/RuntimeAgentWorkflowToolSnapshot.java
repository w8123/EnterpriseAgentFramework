package com.enterprise.ai.runtime.agent;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/** Immutable Agent-owned execution data. Persistence rows never leave the owner query. */
@Value
@Builder(toBuilder = true)
public class RuntimeAgentWorkflowToolSnapshot {
    Long id;
    String agentId;
    Long agentConfigVersionId;
    String workflowId;
    Long workflowVersionId;
    String toolName;
    String descriptionOverride;
    String inputSchemaOverrideJson;
    String outputSchemaOverrideJson;
    String riskLevel;
    String permissionKey;
    Boolean readOnly;
    Boolean enabled;
    Integer priority;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;

    public static RuntimeAgentWorkflowToolSnapshot fromEntity(RuntimeAgentWorkflowToolEntity entity) {
        if (entity == null) return null;
        return RuntimeAgentWorkflowToolSnapshot.builder()
                .id(entity.getId())
                .agentId(entity.getAgentId())
                .agentConfigVersionId(entity.getAgentConfigVersionId())
                .workflowId(entity.getWorkflowId())
                .workflowVersionId(entity.getWorkflowVersionId())
                .toolName(entity.getToolName())
                .descriptionOverride(entity.getDescriptionOverride())
                .inputSchemaOverrideJson(entity.getInputSchemaOverrideJson())
                .outputSchemaOverrideJson(entity.getOutputSchemaOverrideJson())
                .riskLevel(entity.getRiskLevel())
                .permissionKey(entity.getPermissionKey())
                .readOnly(entity.getReadOnly())
                .enabled(entity.getEnabled())
                .priority(entity.getPriority())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
