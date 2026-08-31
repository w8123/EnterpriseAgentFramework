package com.enterprise.ai.runtime.execution.checkpoint;

import java.util.Map;

/** Versioned durable state for resuming one suspended GraphSpec execution. */
public record WorkflowCheckpointV1(
        int checkpointSchemaVersion,
        String executionEngineVersion,
        String graphDigest,
        String suspendedNodeId,
        long createdAtEpochMs,
        Map<String, Object> state
) {
}
