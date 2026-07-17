package com.enterprise.ai.runtime.workflow.authoring;

/**
 * Design-time Workflow authoring boundary.
 *
 * <p>Application services depend on this adapter instead of AgentScope internals.
 * Implementations may use AgentScope ReActAgent + constrained tools, while GraphSpec
 * mutation and validation remain deterministic outside the model.</p>
 */
public interface WorkflowAuthoringAgentAdapter {

    WorkflowAuthoringResult author(WorkflowAuthoringRequest request);
}
