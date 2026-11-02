package com.enterprise.ai.control.mcp.api.management;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Run center conversation reads, implemented by the module that owns conversations. */
public interface McpPlatformConversationReader {
    ResponseEntity<?> read(PlatformAuthenticatedSession actor, String sessionId, Map<String, Object> query);
}
