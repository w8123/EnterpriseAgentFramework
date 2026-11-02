package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

/** Project picker filtered by the verified platform delegate's resource grants. */
public interface McpPlatformProjectCatalogPort {
    ResponseEntity<?> listProjects(PlatformAuthenticatedSession actor, int offset, int limit);
}
