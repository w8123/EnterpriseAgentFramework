package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Structured GraphSpec mutations against the same Workflow working copy as Studio. */
public interface McpPlatformWorkflowAuthoringPort {
    ResponseEntity<?> createDraft(PlatformAuthenticatedSession actor, Map<String, Object> body);
    ResponseEntity<?> patch(PlatformAuthenticatedSession actor, String workflowId,
                            boolean dryRun, Map<String, Object> body);
}
