package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** The same Page Assistant → page copilot sync people run from Workflow Studio. */
public interface McpPlatformPageAssistantSyncPort {
    ResponseEntity<?> context(PlatformAuthenticatedSession actor, String workflowId);

    ResponseEntity<?> attach(PlatformAuthenticatedSession actor, String workflowId, Map<String, Object> request);
}
