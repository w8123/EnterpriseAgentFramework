package com.enterprise.ai.control.a2a.domain;

/** Canonical A2A Protocol 1.0 task states. */
public enum A2aTaskState {
    TASK_STATE_UNSPECIFIED(false, false),
    TASK_STATE_SUBMITTED(false, false),
    TASK_STATE_WORKING(false, false),
    TASK_STATE_INPUT_REQUIRED(false, true),
    TASK_STATE_AUTH_REQUIRED(false, true),
    TASK_STATE_COMPLETED(true, false),
    TASK_STATE_FAILED(true, false),
    TASK_STATE_CANCELED(true, false),
    TASK_STATE_REJECTED(true, false);

    private final boolean terminal;
    private final boolean interrupted;

    A2aTaskState(boolean terminal, boolean interrupted) {
        this.terminal = terminal;
        this.interrupted = interrupted;
    }

    public boolean terminal() {
        return terminal;
    }

    public boolean interrupted() {
        return interrupted;
    }

    public boolean persistable() {
        return this != TASK_STATE_UNSPECIFIED;
    }

    public static A2aTaskState parse(String value) {
        return A2aDomainText.parseEnum(A2aTaskState.class, value, "taskState");
    }
}
