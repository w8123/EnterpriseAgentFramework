package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;

import java.util.Locale;

/**
 * Lifecycle of one Workflow execution attempt.
 *
 * <p>This status is intentionally independent from Workflow publication status,
 * WorkflowVersion status, interaction-session status, and debug-session status.</p>
 */
public enum WorkflowExecutionStatus {
    RUNNING,
    SUSPENDED,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT;

    public static WorkflowExecutionStatus fromResult(boolean success, String code) {
        if (WorkflowInteractionCodes.WAITING.equals(code)) {
            return SUSPENDED;
        }
        if (success) {
            return COMPLETED;
        }
        if ("RUNTIME_GRAPH_CANCELLED".equalsIgnoreCase(code)) {
            return CANCELLED;
        }
        if (code != null && (code.toUpperCase(Locale.ROOT).contains("TIMEOUT")
                || code.toUpperCase(Locale.ROOT).contains("EXPIRED"))) {
            return TIMED_OUT;
        }
        return FAILED;
    }

    public static WorkflowExecutionStatus parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Workflow execution status is required");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported Workflow execution status: " + value, ex);
        }
    }

    public static WorkflowExecutionStatus fromInteractionStatus(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Interaction status is required");
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "WAITING_USER", "RESUMING" -> SUSPENDED;
            case "COMPLETED" -> COMPLETED;
            case "FAILED" -> FAILED;
            case "CANCELLED" -> CANCELLED;
            case "EXPIRED" -> TIMED_OUT;
            default -> throw new IllegalArgumentException("Unsupported Interaction status: " + value);
        };
    }

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
    }
}
