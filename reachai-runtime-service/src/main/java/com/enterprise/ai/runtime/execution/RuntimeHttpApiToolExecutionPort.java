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
}
