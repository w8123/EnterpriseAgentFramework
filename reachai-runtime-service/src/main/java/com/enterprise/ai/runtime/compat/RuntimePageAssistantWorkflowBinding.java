package com.enterprise.ai.runtime.compat;

public record RuntimePageAssistantWorkflowBinding(
        String agentId,
        String agentKeySlug,
        String workflowId,
        String workflowKeySlug,
        Long bindingId
) {
}
