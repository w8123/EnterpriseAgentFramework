package com.enterprise.ai.agent.workflow.aicoding;

/**
 * Raised when the current platform principal cannot access the workflow's project scope.
 */
public class WorkflowAccessDeniedException extends RuntimeException {

    public WorkflowAccessDeniedException(String message) {
        super(message);
    }
}
