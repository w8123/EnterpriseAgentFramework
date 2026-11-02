package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Business capability enrichment and API graph resources through the console's project-authorized catalog facade. */
public interface McpPlatformCatalogResourcePort {
    enum Area { BUSINESS_CAPABILITIES, API_GRAPH }
    ResponseEntity<?> call(PlatformAuthenticatedSession actor, Area area, String method, long projectId, String path,
                           Map<String, String> query, Object body);
}
