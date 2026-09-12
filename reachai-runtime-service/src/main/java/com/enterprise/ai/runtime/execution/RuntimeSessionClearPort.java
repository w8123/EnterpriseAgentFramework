package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;

/** Execution-owned port for clearing a trusted user's durable Agent session. */
public interface RuntimeSessionClearPort {

    void clear(String publicSessionId, WorkflowExecutionIdentity identity);
}
