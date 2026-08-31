package com.enterprise.ai.runtime.execution.event;

/** Canonical Runtime execution lifecycle events. */
public enum RuntimeExecutionEventType {
    RUN_STARTED,
    NODE_STARTED,
    NODE_DELTA,
    NODE_COMPLETED,
    NODE_SUSPENDED,
    NODE_FAILED,
    RUN_SUSPENDED,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_CANCELLED
}
