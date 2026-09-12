package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import java.util.Map;

/** A protocol entry executes its already-pinned configuration without depending on a Supervisor implementation. */
public interface RuntimePublishedAgentExecutionPort {
    Map<String, Object> executePublishedConfig(String agentId,
                                               Long configVersionId,
                                               Map<String, Object> request,
                                               boolean detailed,
                                               RuntimeAgentExecutionEventSink eventSink,
                                               RuntimeAgentExecutionCancellation cancellation,
                                               WorkflowExecutionIdentity trustedIdentity);
}
