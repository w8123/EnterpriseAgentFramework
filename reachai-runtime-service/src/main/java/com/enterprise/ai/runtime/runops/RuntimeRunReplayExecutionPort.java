package com.enterprise.ai.runtime.runops;

import java.util.Map;

/** Executes the exact Agent configuration chosen by RunOps with explicit substitute input. */
public interface RuntimeRunReplayExecutionPort {
    Map<String, Object> executePublishedAgent(String agentId, Long configVersionId, Map<String, Object> input);
}
