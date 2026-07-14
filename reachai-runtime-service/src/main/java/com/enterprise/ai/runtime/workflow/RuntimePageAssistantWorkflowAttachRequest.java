package com.enterprise.ai.runtime.workflow;

public record RuntimePageAssistantWorkflowAttachRequest(
        Long projectId,
        String projectCode,
        String agentId,
        String modelInstanceId,
        String publishedBy
) {
}
