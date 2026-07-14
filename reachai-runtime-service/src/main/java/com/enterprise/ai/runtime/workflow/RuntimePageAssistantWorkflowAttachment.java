package com.enterprise.ai.runtime.workflow;

public record RuntimePageAssistantWorkflowAttachment(
        String agentId,
        String agentKeySlug,
        String workflowId,
        String workflowKeySlug,
        String toolName,
        Long configVersionId,
        Integer configVersionNo,
        String configStatus,
        boolean published
) {
}
