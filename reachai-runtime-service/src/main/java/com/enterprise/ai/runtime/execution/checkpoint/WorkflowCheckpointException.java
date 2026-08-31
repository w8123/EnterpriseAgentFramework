package com.enterprise.ai.runtime.execution.checkpoint;

/** Fail-closed checkpoint validation error with a stable Runtime code. */
public final class WorkflowCheckpointException extends RuntimeException {

    private final String code;

    public WorkflowCheckpointException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
