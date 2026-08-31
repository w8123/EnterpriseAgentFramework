package com.enterprise.ai.runtime.managed;

import java.util.EnumSet;
import java.util.Set;

public enum ManagedExecutionStatus {
    REQUESTED,
    QUEUED,
    PROVISIONING,
    RUNNING,
    WAITING_APPROVAL,
    WAITING_USER,
    FINALIZING,
    CANCELLING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED;

    private static final Set<ManagedExecutionStatus> ACTIVE = EnumSet.of(
            REQUESTED,
            QUEUED,
            PROVISIONING,
            RUNNING,
            WAITING_APPROVAL,
            WAITING_USER,
            FINALIZING,
            CANCELLING);

    public boolean active() {
        return ACTIVE.contains(this);
    }

    public boolean terminal() {
        return !active();
    }

    public static ManagedExecutionStatus parse(String value) {
        try {
            return ManagedExecutionStatus.valueOf(value == null ? "" : value.trim().toUpperCase());
        } catch (IllegalArgumentException invalid) {
            throw new ManagedExecutionException(409, "MANAGED_EXECUTION_STATUS_INVALID",
                    "Managed execution has an invalid persisted status");
        }
    }
}
