package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** API market discovery and project integrations through the console's API market controller. */
public interface McpPlatformApiMarketPort {
    enum Read { ENTRIES, ENTRY, STATS, CATEGORIES, SOURCES, INTEGRATIONS, INTEGRATION }
    ResponseEntity<?> read(PlatformAuthenticatedSession actor, Read read, String key, Map<String, String> query);
    ResponseEntity<?> select(PlatformAuthenticatedSession actor, String entryKey, Map<String, Object> body);
    ResponseEntity<?> updateStatus(PlatformAuthenticatedSession actor, long integrationId, Map<String, Object> body);
}
