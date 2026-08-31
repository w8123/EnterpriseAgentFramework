package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Execution-owned port for projecting Agent execution lifecycle changes into RunOps.
 *
 * <p>The execution application service owns when a run is rejected, resumed, or
 * finished. RunOps owns how those lifecycle facts are persisted. Keeping this port
 * in the caller module prevents execution from depending on RunOps persistence and
 * query services.</p>
 */
public interface RuntimeAgentRunLifecyclePort {

    void rejectAgent(String traceId,
                     RuntimeAgentView agent,
                     Map<String, Object> input,
                     String code,
                     String message);

    void resumeAgent(String traceId);

    void finishAgent(String traceId,
                     boolean success,
                     String code,
                     String answer,
                     Map<String, Object> metadata,
                     LocalDateTime endedAt,
                     WorkflowExecutionIdentity identity);
}
