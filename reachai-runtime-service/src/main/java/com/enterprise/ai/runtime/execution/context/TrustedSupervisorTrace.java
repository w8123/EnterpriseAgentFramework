package com.enterprise.ai.runtime.execution.context;

/** Runtime-owned marker; JSON maps and Workflow variables cannot construct this type. */
public final class TrustedSupervisorTrace {
    public static final String CONTEXT_KEY = "__trustedSupervisorTrace";
    private final String traceId;

    private TrustedSupervisorTrace(String traceId) {
        this.traceId = traceId;
    }

    public static TrustedSupervisorTrace fromRuntime(String traceId) {
        return traceId != null && traceId.matches("[0-9a-f]{32}")
                ? new TrustedSupervisorTrace(traceId) : null;
    }

    public String traceId() {
        return traceId;
    }
}
