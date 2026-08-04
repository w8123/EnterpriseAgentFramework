package com.enterprise.ai.runtime.debug;

import com.enterprise.ai.runtime.execution.WorkflowExecutionStatus;

import java.util.Locale;

/** Debug-session lifecycle; separate from the Workflow execution outcome it observes. */
public enum RuntimeDebugSessionStatus {
    RUNNING,
    SUSPENDED,
    RESUMING,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED;

    public static RuntimeDebugSessionStatus fromExecutionStatus(WorkflowExecutionStatus status) {
        if (status == null) return FAILED;
        return switch (status) {
            case RUNNING -> RUNNING;
            case SUSPENDED -> SUSPENDED;
            case COMPLETED -> COMPLETED;
            case CANCELLED -> CANCELLED;
            case TIMED_OUT -> EXPIRED;
            case FAILED -> FAILED;
        };
    }

    public static RuntimeDebugSessionStatus parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Debug session status is required");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported Debug session status: " + value, ex);
        }
    }
}
