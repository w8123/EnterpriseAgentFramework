package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowPublishedVersionView;
import com.enterprise.ai.runtime.workflow.RuntimeTrustedWorkflowTarget;

/**
 * One Workflow tool target resolved once per request for Supervisor RunState, system prompt,
 * Toolkit registration and Workflow execution.
 */
public record RuntimeResolvedWorkflowTarget(
        RuntimeAgentWorkflowToolSnapshot tool,
        RuntimeWorkflowExecutionView workflow,
        RuntimeWorkflowPublishedVersionView version) implements RuntimeTrustedWorkflowTarget {
}
