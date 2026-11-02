package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Console trial invocation with an attested platform actor and owner-enforced side effects. */
public interface McpPlatformCapabilityInvocationPort {
    ResponseEntity<?> context(PlatformAuthenticatedSession actor, String qualifiedName);
    ResponseEntity<?> invokeReadOnly(PlatformAuthenticatedSession actor, String qualifiedName, Map<String, Object> body);
    ResponseEntity<?> invokeApproved(PlatformAuthenticatedSession actor, String qualifiedName, Map<String, Object> body);
    ResponseEntity<?> get(PlatformAuthenticatedSession actor, String invocationId);
    ResponseEntity<?> httpConnection(PlatformAuthenticatedSession actor, long apiId);
    ResponseEntity<?> httpWriteContext(PlatformAuthenticatedSession actor, long apiId);
    ResponseEntity<?> invokeHttpReadOnly(PlatformAuthenticatedSession actor, long apiId, Map<String, Object> body);
    ResponseEntity<?> invokeHttpApproved(PlatformAuthenticatedSession actor, long apiId, Map<String, Object> body);
    ResponseEntity<?> getHttpInvocation(PlatformAuthenticatedSession actor, String invocationId, String projectCode);
}
