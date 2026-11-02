package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * Knowledge base create and delete, file delete and retrieval tests through the console's knowledge gateway, which checks
 * the caller's knowledge grant on the exact base or file before forwarding.
 */
public interface McpPlatformKnowledgeBasePort {
    enum Operation { CREATE_BASE, DELETE_BASE, DELETE_FILE, RETRIEVAL_TEST }
    /** Unwrapped owner data on success; a bare 503 only when the call was sent and its result is not confirmed; any other status is a definite refusal. */
    ResponseEntity<?> call(PlatformAuthenticatedSession actor, Operation operation, String target, Map<String, Object> body);
}
