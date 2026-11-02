package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

/** Installation health, the administrator system check and configured login methods, as the console shows them. */
public interface McpPlatformSystemStatusPort {
    enum Read { SERVICES_HEALTH, SYSTEM_CHECK, AUTH_PROVIDERS }
    ResponseEntity<?> read(PlatformAuthenticatedSession actor, Read read);
}
