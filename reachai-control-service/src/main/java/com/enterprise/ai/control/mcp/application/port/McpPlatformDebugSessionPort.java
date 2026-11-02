package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

/** Workflow debug sessions people started in Studio, through the console's debug session endpoints. */
public interface McpPlatformDebugSessionPort {
    ResponseEntity<?> get(PlatformAuthenticatedSession actor, String sessionId);
    ResponseEntity<?> getByCreationKey(PlatformAuthenticatedSession actor, String creationKey);
    ResponseEntity<?> cancel(PlatformAuthenticatedSession actor, String sessionId);
}
