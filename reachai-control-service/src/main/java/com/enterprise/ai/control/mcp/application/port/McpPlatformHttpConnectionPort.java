package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * HTTP API connection settings through the console's HTTP API controller, which resolves the API's verified owner,
 * checks platform write on its project and lets Runtime refuse a stale revision.
 */
public interface McpPlatformHttpConnectionPort {
    enum Change { SAVE_OVERRIDE, SAVE_PROJECT_DEFAULT, REMOVE_OVERRIDE }
    /** The full effective connection view, including the override and project-default revisions. */
    ResponseEntity<?> connection(PlatformAuthenticatedSession actor, long apiId);
    ResponseEntity<?> change(PlatformAuthenticatedSession actor, Change change, long apiId, Map<String, Object> body);
}
