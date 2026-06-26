package com.enterprise.ai.agent.platform.control.context.memory;

final class ContextMemoryCandidateViewMapper {

    private ContextMemoryCandidateViewMapper() {
    }

    static ContextMemoryCandidateResponse toResponse(ContextMemoryCandidateEntity entity) {
        if (entity == null) {
            return null;
        }
        return ContextMemoryCandidateResponse.builder()
                .id(entity.getId())
                .candidateKey(entity.getCandidateKey())
                .tenantId(entity.getTenantId())
                .projectId(entity.getProjectId())
                .projectCode(entity.getProjectCode())
                .namespaceId(entity.getNamespaceId())
                .namespaceKey(entity.getNamespaceKey())
                .memoryLane(entity.getMemoryLane())
                .candidateType(entity.getCandidateType())
                .title(entity.getTitle())
                .content(entity.getContent())
                .summary(entity.getSummary())
                .reason(entity.getReason())
                .sourceType(entity.getSourceType())
                .sourceRef(entity.getSourceRef())
                .traceId(entity.getTraceId())
                .sessionId(entity.getSessionId())
                .userId(entity.getUserId())
                .externalUserId(entity.getExternalUserId())
                .globalUserId(entity.getGlobalUserId())
                .agentId(entity.getAgentId())
                .agentKey(entity.getAgentKey())
                .workflowId(entity.getWorkflowId())
                .workflowKey(entity.getWorkflowKey())
                .pageInstanceId(entity.getPageInstanceId())
                .origin(entity.getOrigin())
                .confidence(entity.getConfidence())
                .trustLevel(entity.getTrustLevel())
                .visibility(entity.getVisibility())
                .status(entity.getStatus())
                .proposedBy(entity.getProposedBy())
                .reviewedBy(entity.getReviewedBy())
                .reviewedAt(entity.getReviewedAt())
                .reviewReason(entity.getReviewReason())
                .approvedItemId(entity.getApprovedItemId())
                .metadataJson(entity.getMetadataJson())
                .expiresAt(entity.getExpiresAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
