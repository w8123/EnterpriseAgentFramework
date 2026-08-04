package com.enterprise.ai.runtime.workflow.authoring;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps deterministic mutation failures into structured, retryable tool errors.
 */
final class WorkflowAuthoringMutationErrors {

    private WorkflowAuthoringMutationErrors() {
    }

    static Map<String, Object> failure(String code, String message, Integer operationIndex, boolean retryable) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("code", StringUtils.hasText(code) ? code : "MUTATION_FAILED");
        payload.put("operationIndex", operationIndex == null ? -1 : operationIndex);
        payload.put("message", message == null ? "mutation failed" : message);
        payload.put("retryable", retryable);
        return payload;
    }

    static Map<String, Object> fromException(Exception ex, Integer operationIndex) {
        String message = ex == null || !StringUtils.hasText(ex.getMessage())
                ? "mutation failed"
                : ex.getMessage().trim();
        return failure(codeFor(message), message, operationIndex, true);
    }

    static String codeFor(String message) {
        if (!StringUtils.hasText(message)) {
            return "MUTATION_FAILED";
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("add_node") && normalized.contains("node.id")) {
            return "ADD_NODE_ID_REQUIRED";
        }
        if (normalized.contains("add_node") && normalized.contains("node")) {
            return "ADD_NODE_REQUIRED";
        }
        if (normalized.contains("add_edge") && (normalized.contains("from") || normalized.contains("to"))) {
            return "ADD_EDGE_ENDPOINTS_REQUIRED";
        }
        if (normalized.contains("update_node") && normalized.contains("nodeid")) {
            return "UPDATE_NODE_ID_REQUIRED";
        }
        if (normalized.contains("delete_node") && normalized.contains("nodeid")) {
            return "DELETE_NODE_ID_REQUIRED";
        }
        if (normalized.contains("update_edge") && normalized.contains("edgeid")) {
            return "UPDATE_EDGE_ID_REQUIRED";
        }
        if (normalized.contains("delete_edge") && normalized.contains("edgeid")) {
            return "DELETE_EDGE_ID_REQUIRED";
        }
        if (normalized.contains("set_entry_node")) {
            return "SET_ENTRY_NODE_INVALID";
        }
        if (normalized.contains("set_exit_nodes")) {
            return "SET_EXIT_NODES_INVALID";
        }
        if (normalized.contains(".op is required") || normalized.contains("op is required")) {
            return "OPERATION_OP_REQUIRED";
        }
        if (normalized.contains("duplicate graph node id")) {
            return "DUPLICATE_NODE_ID";
        }
        if (normalized.contains("graph node not found")) {
            return "NODE_NOT_FOUND";
        }
        if (normalized.contains("graph edge not found")) {
            return "EDGE_NOT_FOUND";
        }
        if (normalized.contains("cannot change node id") || normalized.contains("cannot change edge id")) {
            return "IDENTITY_IMMUTABLE";
        }
        if (normalized.contains("workflow_node_not_authorable")
                || normalized.contains("not enabled for ai authoring")) {
            return "WORKFLOW_NODE_NOT_AUTHORABLE";
        }
        return "MUTATION_INVALID";
    }
}
