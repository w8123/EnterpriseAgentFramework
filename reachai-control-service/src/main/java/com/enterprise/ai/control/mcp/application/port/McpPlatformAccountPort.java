package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * Account and role administration through the console's account controller, which requires global platform:admin,
 * keeps the final administrator and system roles intact and records the auth audit event under the caller.
 */
public interface McpPlatformAccountPort {
    enum Operation { USERS, ROLES, PERMISSIONS, AUDIT_EVENTS, UPDATE_USER, REVOKE_SESSIONS, REPLACE_GRANTS, CREATE_ROLE, UPDATE_ROLE }
    ResponseEntity<?> call(PlatformAuthenticatedSession actor, Operation operation, Long id, Map<String, Object> query, Object body);
}
