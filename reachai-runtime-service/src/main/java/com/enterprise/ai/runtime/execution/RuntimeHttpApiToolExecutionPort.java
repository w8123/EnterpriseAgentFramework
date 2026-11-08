package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;

import java.util.Map;

/** Execution-kernel port; Workflow owns the published API pin and implements this contract. */
public interface RuntimeHttpApiToolExecutionPort {
    Invocation invoke(GraphSpec.Node node, Map<String, Object> input, WorkflowExecutionIdentity identity);

    record Invocation(boolean success, String code, Object output, Integer httpStatus, String inputField,
                      String sideEffect, String dispatchStage) {
        public Invocation(boolean success, String code, Object output, Integer httpStatus, String inputField) {
            this(success, code, output, httpStatus, inputField, null, null);
        }
    }
    /**
     * Thread-confined execution scope (read-only trial draft pins) captured on the calling thread so that
     * parallel branches, parallel iterations and timed node attempts keep exactly the same authority.
     * Returns null when no scope is active.
     */
    default Object captureThreadScope() {
        return null;
    }

    /** Installs a scope captured by {@link #captureThreadScope()} on a worker thread. */
    default void installThreadScope(Object captured) {
    }

    /** Removes any scope installed on the current worker thread. */
    default void clearThreadScope() {
    }
}
