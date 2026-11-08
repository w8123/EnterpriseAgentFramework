package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;

import java.util.LinkedHashMap;
import java.util.Map;

/** Node trace rows and attempt fields shared by the main path, parallel branches and Debug/RunOps. */
final class RuntimeNodeObservability {

    private RuntimeNodeObservability() {
    }

    static Map<String, Object> buildInternalNodeTrace(GraphSpec.Node node,
                                                       String nodeType,
                                                       RuntimeGraphSpecExecutionResult nodeResult,
                                                       long startedAtMs,
                                                       long endedAtMs,
                                                       long latencyMs) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("nodeId", node.getId());
        trace.put("nodeType", nodeType);
        String status;
        if (nodeResult.isWaitingUser() || WorkflowInteractionCodes.WAITING.equals(nodeResult.code())) {
            status = "WAITING_USER";
        } else if ("RUNTIME_GRAPH_CANCELLED".equals(nodeResult.code())) {
            status = "CANCELLED";
        } else if (isBusinessTerminalResult(nodeResult)) {
            status = "BUSINESS_TERMINAL";
        } else if (nodeResult.success()) {
            status = "SUCCESS";
        } else {
            status = "FAILED";
        }
        trace.put("status", status);
        trace.put("startedAt", startedAtMs);
        if (!"WAITING_USER".equals(status)) {
            trace.put("endedAt", endedAtMs);
        }
        trace.put("latencyMs", latencyMs);
        if (nodeResult.metadata() != null) {
            putIfPresent(trace, "attempt", nodeResult.metadata().get("attempt"));
            putIfPresent(trace, "maxAttempts", nodeResult.metadata().get("maxAttempts"));
            putIfPresent(trace, "errorPolicy", nodeResult.metadata().get("errorPolicyDecision"));
            putIfPresent(trace, "fallbackNodeId", nodeResult.metadata().get("fallbackNodeId"));
            putIfPresent(trace, "outcomeClass", nodeResult.metadata().get("outcomeClass"));
            putIfPresent(trace, "businessOutcome", nodeResult.metadata().get("businessOutcome"));
            putIfPresent(trace, "qualifiedName", nodeResult.metadata().get("qualifiedName"));
            putIfPresent(trace, "failureCategory", nodeResult.metadata().get("failureCategory"));
            putIfPresent(trace, "retryableFailure", nodeResult.metadata().get("retryableFailure"));
            putIfPresent(trace, "sideEffect", nodeResult.metadata().get("sideEffect"));
            putIfPresent(trace, "dispatchStage", nodeResult.metadata().get("dispatchStage"));
            putIfPresent(trace, "nonRecoverableWrite", nodeResult.metadata().get("nonRecoverableWrite"));
            putIfPresent(trace, "httpStatus", nodeResult.metadata().get("httpStatus"));
            putIfPresent(trace, "inputField", nodeResult.metadata().get("inputField"));
            putIfPresent(trace, "interactionType", nodeResult.metadata().get("interactionType"));
            Object summary = nodeResult.metadata().get("traceSummary");
            if (summary instanceof Map<?, ?> map) {
                trace.put("traceSummary", new LinkedHashMap<>((Map<String, Object>) map));
            } else if (summary != null) {
                trace.put("traceSummary", summary);
            }
        }
        if (!nodeResult.success()) {
            putIfPresent(trace, "failureCode", nodeResult.code());
            putIfPresent(trace, "failureReason",
                    RuntimeNodeFailureReasons.safeReason(nodeResult.code(), nodeResult.answer()));
        }
        if (nodeResult.interactionId() != null) {
            trace.put("interactionId", nodeResult.interactionId());
        }
        if (nodeResult.uiRequest() != null) {
            trace.put("uiRequest", nodeResult.uiRequest());
        }
        return trace;
    }


    static void putAttemptObservability(Map<String, Object> payload, RuntimeGraphSpecExecutionResult result) {
        if (result == null || result.metadata() == null || payload == null) {
            return;
        }
        putIfPresent(payload, "attempt", result.metadata().get("attempt"));
        putIfPresent(payload, "attemptCount", result.metadata().get("attemptCount"));
        putIfPresent(payload, "maxAttempts", result.metadata().get("maxAttempts"));
        putIfPresent(payload, "errorPolicyDecision", result.metadata().get("errorPolicyDecision"));
        putIfPresent(payload, "fallbackNodeId", result.metadata().get("fallbackNodeId"));
        putIfPresent(payload, "traceSummary", result.metadata().get("traceSummary"));
        putIfPresent(payload, "outcomeClass", result.metadata().get("outcomeClass"));
        putIfPresent(payload, "businessOutcome", result.metadata().get("businessOutcome"));
        putIfPresent(payload, "qualifiedName", result.metadata().get("qualifiedName"));
        putIfPresent(payload, "failureCategory", result.metadata().get("failureCategory"));
        putIfPresent(payload, "retryableFailure", result.metadata().get("retryableFailure"));
        putIfPresent(payload, "sideEffect", result.metadata().get("sideEffect"));
        putIfPresent(payload, "dispatchStage", result.metadata().get("dispatchStage"));
        putIfPresent(payload, "nonRecoverableWrite", result.metadata().get("nonRecoverableWrite"));
        putIfPresent(payload, "httpStatus", result.metadata().get("httpStatus"));
        putIfPresent(payload, "inputField", result.metadata().get("inputField"));
        putIfPresent(payload, "interactionType", result.metadata().get("interactionType"));
    }

    static boolean isBusinessTerminalResult(RuntimeGraphSpecExecutionResult result) {
        return result != null
                && result.success()
                && result.metadata() != null
                && result.metadata().get("outcomeClass") != null
                && "BUSINESS_TERMINAL".equalsIgnoreCase(String.valueOf(result.metadata().get("outcomeClass")).trim());
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
