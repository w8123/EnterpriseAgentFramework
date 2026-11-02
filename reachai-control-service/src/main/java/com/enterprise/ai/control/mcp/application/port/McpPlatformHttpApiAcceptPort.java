package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

/** Accepting an observed HTTP API into the catalog through the console's HTTP API controller. */
public interface McpPlatformHttpApiAcceptPort {
    ResponseEntity<?> accept(PlatformAuthenticatedSession actor, long apiId, String expectedSourceSetRevision);
}
