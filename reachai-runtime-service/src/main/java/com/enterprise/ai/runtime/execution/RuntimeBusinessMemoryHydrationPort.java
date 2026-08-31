package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;

import java.util.Map;

/** Execution-owned port for resolving business-memory references in node output. */
public interface RuntimeBusinessMemoryHydrationPort {

    HydrationBatch hydrate(Object input,
                           WorkflowExecutionIdentity identity,
                           Map<String, Object> executionContext);

    record HydrationBatch(Object output,
                          boolean detected,
                          int referenceCount,
                          int resolvedCount,
                          int blockedCount,
                          int versionChangedCount) {
    }
}
