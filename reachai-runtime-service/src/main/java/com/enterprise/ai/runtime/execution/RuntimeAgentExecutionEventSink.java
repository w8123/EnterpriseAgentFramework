package com.enterprise.ai.runtime.execution;

/** Execution events shared by protocol entry points and the configured Agent orchestration runtime. */
@FunctionalInterface
public interface RuntimeAgentExecutionEventSink {
    RuntimeAgentExecutionEventSink NOOP = (event, data) -> { };

    void emit(String event, Object data);
}
