package com.enterprise.ai.runtime.runops;

/** Why a Runtime root run is suspended; not a lifecycle status. */
public enum RuntimeRunSuspensionReason {
    USER_INPUT,
    APPROVAL;

    public static RuntimeRunSuspensionReason fromCode(String code) {
        if ("SUPERVISOR_CONFIRMATION_REQUIRED".equalsIgnoreCase(code)) return APPROVAL;
        if ("RUNTIME_GRAPH_INTERACTION_WAITING".equalsIgnoreCase(code)) return USER_INPUT;
        return null;
    }
}
