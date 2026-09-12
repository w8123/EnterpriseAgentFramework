package com.enterprise.ai.runtime.agent;

import lombok.Builder;
import lombok.Value;

/** Immutable execution data exported by its owning module. */
@Value
@Builder
public class RuntimeAgentRemoteBindingView {
    Long id;
    String agentId;
    Long agentConfigVersionId;
    Long principalId;
    Long remoteAgentId;
    Long remoteAgentRevisionId;
    String remoteAgentKeySnapshot;
    String toolName;
    String descriptionSnapshot;
    String allowedSkillIdsJson;
    String inputModesJson;
    String outputModesJson;
    String riskLevel;
    String permissionKey;
    Long timeoutMs;
    Integer priority;
    Boolean enabled;

    public static RuntimeAgentRemoteBindingView fromEntity(RuntimeAgentRemoteBindingEntity entity) {
        if (entity == null) return null;
        return RuntimeAgentRemoteBindingView.builder()
                .id(entity.getId())
                .agentId(entity.getAgentId())
                .agentConfigVersionId(entity.getAgentConfigVersionId())
                .principalId(entity.getPrincipalId())
                .remoteAgentId(entity.getRemoteAgentId())
                .remoteAgentRevisionId(entity.getRemoteAgentRevisionId())
                .remoteAgentKeySnapshot(entity.getRemoteAgentKeySnapshot())
                .toolName(entity.getToolName())
                .descriptionSnapshot(entity.getDescriptionSnapshot())
                .allowedSkillIdsJson(entity.getAllowedSkillIdsJson())
                .inputModesJson(entity.getInputModesJson())
                .outputModesJson(entity.getOutputModesJson())
                .riskLevel(entity.getRiskLevel())
                .permissionKey(entity.getPermissionKey())
                .timeoutMs(entity.getTimeoutMs())
                .priority(entity.getPriority())
                .enabled(entity.getEnabled())
                .build();
    }
}
