package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Cross-domain memory erasure requests, through the compliance console with its global permission check. */
public interface McpPlatformMemoryErasurePort {
    ResponseEntity<?> get(PlatformAuthenticatedSession actor, String requestId);
    ResponseEntity<?> create(PlatformAuthenticatedSession actor, Map<String, Object> command);
    ResponseEntity<?> retry(PlatformAuthenticatedSession actor, String requestId);
}
