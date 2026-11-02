package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * Runtime user mappings and business user identities through the console's identity controllers, which require the
 * global mapping or business-user permissions and audit every change under the caller.
 */
public interface McpPlatformIdentityPort {
    enum Operation { RUNTIME_MAPPINGS, BUSINESS_USERS, BUSINESS_USER_IDENTITIES, MAPPING_CREATE, MAPPING_DELETE, BUSINESS_IDENTITY_SAVE }
    ResponseEntity<?> call(PlatformAuthenticatedSession actor, Operation operation, Long id, Map<String, Object> query, Map<String, Object> body);
}
