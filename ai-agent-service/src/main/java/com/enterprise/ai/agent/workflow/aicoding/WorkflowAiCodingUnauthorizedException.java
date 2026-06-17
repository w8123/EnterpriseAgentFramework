package com.enterprise.ai.agent.workflow.aicoding;

/**
 * Raised when a Workflow AI Coding request is missing the required aiCodingKey.
 */
public class WorkflowAiCodingUnauthorizedException extends RuntimeException {

    public WorkflowAiCodingUnauthorizedException(String message) {
        super(message);
    }
}
