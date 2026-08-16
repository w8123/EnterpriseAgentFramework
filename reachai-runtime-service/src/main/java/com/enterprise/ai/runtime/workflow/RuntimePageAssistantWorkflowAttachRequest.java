package com.enterprise.ai.runtime.workflow;

public record RuntimePageAssistantWorkflowAttachRequest(
        Long projectId,
        String projectCode,
        String agentId,
        String modelInstanceId,
        String publishedBy,
        String replaceWorkflowId
) {
    public RuntimePageAssistantWorkflowAttachRequest(
            Long projectId,
            String projectCode,
            String agentId,
            String modelInstanceId,
            String publishedBy) {
        this(projectId, projectCode, agentId, modelInstanceId, publishedBy, null);
    }
}
