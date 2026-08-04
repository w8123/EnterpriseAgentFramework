package com.enterprise.ai.runtime.runops;

import java.util.Locale;

/** Lifecycle of one durable Runtime root run, independent from its pause reason. */
public enum RuntimeRunStatus {
    RUNNING,
    SUSPENDED,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT;

    public static RuntimeRunStatus fromResult(boolean success, String code) {
        if (success) return COMPLETED;
        if ("SUPERVISOR_CONFIRMATION_REQUIRED".equalsIgnoreCase(code)
                || "RUNTIME_GRAPH_INTERACTION_WAITING".equalsIgnoreCase(code)) {
            return SUSPENDED;
        }
        if (code != null && (upper(code).contains("TIMEOUT") || upper(code).contains("EXPIRED"))) {
            return TIMED_OUT;
        }
        if (code != null && (upper(code).contains("CANCEL") || upper(code).contains("ACTION_REJECTED"))) {
            return CANCELLED;
        }
        return FAILED;
    }

    public static RuntimeRunStatus parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Runtime run status is required");
        }
        try {
            return valueOf(upper(value));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported Runtime run status: " + value, ex);
        }
    }

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
    }

    private static String upper(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
