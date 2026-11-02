package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * MCP publication management through the console controllers, so platform AI meets the same MCP Hub permissions,
 * domain rules and audit. Client creation and key rotation are not offered: a freshly issued API key is shown only to a
 * person in the console.
 */
public interface McpPlatformPublicationPort {
    enum Read { LIST, DETAIL, REVISIONS, RESOLVE_PREVIEW, PRECHECK, CLIENTS, EMPLOYEE_ACCESS }
    ResponseEntity<?> read(PlatformAuthenticatedSession actor, Read operation, Long publicationId, Map<String, Object> query);

    /** Draft edits only change the next revision; what clients see changes when a revision is published. */
    enum Draft { CREATE, RENAME, ADD_ITEM, REMOVE_ITEM }
    ResponseEntity<?> draft(PlatformAuthenticatedSession actor, Draft operation, Long publicationId, Long itemId, Map<String, Object> body);

    enum Change { PUBLISH, SUSPEND, RESUME, ARCHIVE, ROLLBACK, EMPLOYEE_ACCESS, CLIENT_UPDATE, CLIENT_REVOKE }
    /** Whether the console would admit this change for the session, before anything is frozen. */
    boolean admits(PlatformAuthenticatedSession actor, Change operation, Map<String, Object> body);
    ResponseEntity<?> change(PlatformAuthenticatedSession actor, Change operation, long publicationId, Long targetId, Map<String, Object> body);
}
