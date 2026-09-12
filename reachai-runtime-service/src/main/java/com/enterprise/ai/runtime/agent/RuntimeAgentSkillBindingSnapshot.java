package com.enterprise.ai.runtime.agent;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/** Immutable Agent-owned execution data. Persistence rows never leave the owner query. */
@Value
@Builder(toBuilder = true)
public class RuntimeAgentSkillBindingSnapshot {
    Long id;
    String agentId;
    Long agentConfigVersionId;
    Long skillId;
    Long skillVersionId;
    String publisher;
    String standardName;
    String displayName;
    String visibility;
    String projectCode;
    String version;
    String sourceSha256;
    String contentTreeSha256;
    String sourceRoot;
    String packageManifestJson;
    String riskReportJson;
    Boolean hasScripts;
    String activationMode;
    String scriptPolicy;
    Boolean required;
    Boolean enabled;
    Integer priority;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;

    public static RuntimeAgentSkillBindingSnapshot fromEntity(RuntimeAgentSkillBindingEntity entity) {
        if (entity == null) return null;
        return RuntimeAgentSkillBindingSnapshot.builder()
                .id(entity.getId())
                .agentId(entity.getAgentId())
                .agentConfigVersionId(entity.getAgentConfigVersionId())
                .skillId(entity.getSkillId())
                .skillVersionId(entity.getSkillVersionId())
                .publisher(entity.getPublisher())
                .standardName(entity.getStandardName())
                .displayName(entity.getDisplayName())
                .visibility(entity.getVisibility())
                .projectCode(entity.getProjectCode())
                .version(entity.getVersion())
                .sourceSha256(entity.getSourceSha256())
                .contentTreeSha256(entity.getContentTreeSha256())
                .sourceRoot(entity.getSourceRoot())
                .packageManifestJson(entity.getPackageManifestJson())
                .riskReportJson(entity.getRiskReportJson())
                .hasScripts(entity.getHasScripts())
                .activationMode(entity.getActivationMode())
                .scriptPolicy(entity.getScriptPolicy())
                .required(entity.getRequired())
                .enabled(entity.getEnabled())
                .priority(entity.getPriority())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
