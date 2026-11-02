package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

/** Catalog reads owned by Capability and authorized through the human console. */
public interface McpPlatformCapabilityReadPort {
    ResponseEntity<?> listBusinessMethods(PlatformAuthenticatedSession actor, long projectId,
                                          int page, int size, String keyword, Boolean enabled);
    ResponseEntity<?> getBusinessMethod(PlatformAuthenticatedSession actor, String qualifiedName);
    ResponseEntity<?> listTools(PlatformAuthenticatedSession actor, Long projectId, int page, int size, String keyword,
                                String source, Boolean enabled);
    ResponseEntity<?> listHttpApis(PlatformAuthenticatedSession actor, long projectId, int page, int size,
                                   String keyword, String method, String sourceStatus);
    ResponseEntity<?> getHttpApi(PlatformAuthenticatedSession actor, long apiId);
    ResponseEntity<?> httpApiReferences(PlatformAuthenticatedSession actor, long apiId);
    ResponseEntity<?> capabilityReferences(PlatformAuthenticatedSession actor, String projectCode, String name);
    ResponseEntity<?> capabilityChanges(PlatformAuthenticatedSession actor, String projectCode, String state, String keyword, int page, int size);
}
