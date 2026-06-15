package com.enterprise.ai.agent.workflow;

public record PageAssistantWorkflowBindingResult(
        String agentId,
        String agentKeySlug,
        String workflowId,
        String workflowKeySlug,
        Long bindingId
) {
}
