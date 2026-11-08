package com.enterprise.ai.runtime.execution;

/**
 * Resolves the pinned, published GraphSpec of a workflow called by a SUB_WORKFLOW node.
 * Implementations throw {@link IllegalArgumentException} with a user-facing message when the
 * version is missing or not executable.
 */
public interface RuntimeSubWorkflowPort {

    String resolvePublishedGraph(String workflowId, Long versionId);
}
