package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * Run center and evaluation calls through the console's Runtime controller, which checks run-center or evaluation
 * permission on the run's, Agent's or dataset's project and attests the caller to Runtime.
 */
public interface McpPlatformRunOpsPort {
    enum Operation {
        DIAGNOSTICS, COMPARE, REPLAY,
        EVAL_DATASET_CREATE, EVAL_DATASET_VERSION_CREATE, EVAL_DATASET_VERSION_FROM_TRACE,
        EVAL_EXPERIMENT_CREATE, EVAL_EXPERIMENT_CANCEL, EVAL_EXPERIMENT_ITEMS
    }
    ResponseEntity<?> call(PlatformAuthenticatedSession actor, Operation operation, String id, String secondId,
                           Map<String, Object> query, Map<String, Object> body);
}
