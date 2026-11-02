package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Delivery of a Workflow built by an accepted page Workflow engineering task: preview what would be sent, then send it. */
public interface McpPlatformPageWorkflowDeliveryPort {
    /** The Workflow, applied result and request a delivery would use; nothing is published. */
    Map<String, Object> plan(PlatformAuthenticatedSession actor, String projectCode, String taskId, Map<String, Object> command);
    ResponseEntity<?> deliver(PlatformAuthenticatedSession actor, String projectCode, String taskId, Map<String, Object> command);
}
