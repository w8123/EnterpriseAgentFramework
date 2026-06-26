package com.enterprise.ai.agent.platform.control.context;

import lombok.experimental.UtilityClass;

@UtilityClass
class ContextViewMapper {

    static ContextNamespaceResponse toNamespaceResponse(ContextNamespaceEntity entity) {
        return ContextNamespaceResponse.builder()
                .id(entity.getId())
                .namespaceKey(entity.getNamespaceKey())
                .namespaceType(entity.getNamespaceType())
                .tenantId(entity.getTenantId())
                .projectId(entity.getProjectId())
                .projectCode(entity.getProjectCode())
                .ownerType(entity.getOwnerType())
                .ownerId(entity.getOwnerId())
                .displayName(entity.getDisplayName())
                .description(entity.getDescription())
                .status(entity.getStatus())
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    static ContextItemResponse toItemResponse(ContextItemEntity entity) {
        return ContextItemResponse.builder()
                .id(entity.getId())
                .itemKey(entity.getItemKey())
                .namespaceId(entity.getNamespaceId())
                .itemType(entity.getItemType())
                .memoryLane(entity.getMemoryLane())
                .title(entity.getTitle())
                .content(entity.getContent())
                .summary(entity.getSummary())
                .metadataJson(entity.getMetadataJson())
                .sourceType(entity.getSourceType())
                .sourceRef(entity.getSourceRef())
                .confidence(entity.getConfidence())
                .trustLevel(entity.getTrustLevel())
                .visibility(entity.getVisibility())
                .status(entity.getStatus())
                .effectiveFrom(entity.getEffectiveFrom())
                .expiresAt(entity.getExpiresAt())
                .lastVerifiedAt(entity.getLastVerifiedAt())
                .staleAfter(entity.getStaleAfter())
                .createdBy(entity.getCreatedBy())
                .updatedBy(entity.getUpdatedBy())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    static ContextBindingResponse toBindingResponse(ContextBindingEntity entity) {
        return ContextBindingResponse.builder()
                .id(entity.getId())
                .itemId(entity.getItemId())
                .bindType(entity.getBindType())
                .bindId(entity.getBindId())
                .bindKey(entity.getBindKey())
                .tenantId(entity.getTenantId())
                .projectId(entity.getProjectId())
                .projectCode(entity.getProjectCode())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    static ContextEvidenceResponse toEvidenceResponse(ContextEvidenceEntity entity) {
        return ContextEvidenceResponse.builder()
                .id(entity.getId())
                .itemId(entity.getItemId())
                .evidenceType(entity.getEvidenceType())
                .evidenceRef(entity.getEvidenceRef())
                .evidenceExcerpt(entity.getEvidenceExcerpt())
                .traceId(entity.getTraceId())
                .confidence(entity.getConfidence())
                .metadataJson(entity.getMetadataJson())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    static ContextAuditEventResponse toAuditResponse(ContextAuditEventEntity entity) {
        return ContextAuditEventResponse.builder()
                .id(entity.getId())
                .eventType(entity.getEventType())
                .itemId(entity.getItemId())
                .namespaceId(entity.getNamespaceId())
                .actorType(entity.getActorType())
                .actorId(entity.getActorId())
                .tenantId(entity.getTenantId())
                .projectId(entity.getProjectId())
                .projectCode(entity.getProjectCode())
                .agentId(entity.getAgentId())
                .workflowId(entity.getWorkflowId())
                .sessionId(entity.getSessionId())
                .traceId(entity.getTraceId())
                .decision(entity.getDecision())
                .reason(entity.getReason())
                .metadataJson(entity.getMetadataJson())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
