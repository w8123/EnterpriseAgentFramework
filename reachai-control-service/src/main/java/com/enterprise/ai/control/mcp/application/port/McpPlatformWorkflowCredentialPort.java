package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Workflow runtime credentials through the console's credential endpoints; responses only carry masked secrets. */
public interface McpPlatformWorkflowCredentialPort {
    ResponseEntity<?> list(PlatformAuthenticatedSession actor, Long projectId, String projectCode);
    ResponseEntity<?> create(PlatformAuthenticatedSession actor, Map<String, Object> body);
    ResponseEntity<?> update(PlatformAuthenticatedSession actor, long id, Map<String, Object> body);
    ResponseEntity<?> disable(PlatformAuthenticatedSession actor, long id);
}
