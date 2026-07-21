package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;

/**
 * One Workflow tool target resolved once per request for Supervisor RunState, system prompt,
 * Toolkit registration and Workflow execution.
 */
public record RuntimeResolvedWorkflowTarget(
        RuntimeAgentWorkflowToolEntity tool,
        RuntimeWorkflowDefinitionEntity workflow,
        RuntimeWorkflowVersionEntity version) {
}
