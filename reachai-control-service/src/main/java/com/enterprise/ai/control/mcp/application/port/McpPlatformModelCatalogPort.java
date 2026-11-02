package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

public interface McpPlatformModelCatalogPort {
    ResponseEntity<?> list(PlatformAuthenticatedSession actor, String projectCode, String modelType,
                           String provider, boolean includeArchived, int offset, int limit);
    ResponseEntity<?> get(PlatformAuthenticatedSession actor, String modelId);
    /** Creates through the console create handler, which checks model:manage on the requested project. */
    ResponseEntity<?> create(PlatformAuthenticatedSession actor, java.util.Map<String, Object> body);
}
