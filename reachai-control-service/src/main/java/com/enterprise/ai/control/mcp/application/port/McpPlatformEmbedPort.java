package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Embedded chat operations through the console's embed controllers and their route permission. */
public interface McpPlatformEmbedPort {
    enum Read { SESSIONS, PAGE_ACTION_EVENTS, PAGES, PAGE_ACTIONS, READINESS, CREDENTIAL_POLICIES, DEBUG_RESULT }
    enum Change { DELETE_PAGE, UPDATE_POLICY }
    ResponseEntity<?> read(PlatformAuthenticatedSession actor, Read read, Map<String, Object> query);
    ResponseEntity<?> change(PlatformAuthenticatedSession actor, Change change, long id, Map<String, Object> body);
}
