package com.enterprise.ai.runtime.runops;

import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import java.util.Map;

/** Executes the exact Agent configuration chosen by RunOps with explicit substitute input. */
public interface RuntimeRunReplayExecutionPort {
    Map<String, Object> executePublishedAgent(String agentId, Long configVersionId, Map<String, Object> input);

    Map<String, Object> executePublishedAgent(String agentId, Long configVersionId,
                                             Map<String, Object> input, WorkflowExecutionIdentity identity);
}
