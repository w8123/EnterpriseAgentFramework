package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

/** Agent enable/disable and delete through the console's Runtime controller, which checks agent:write on the Agent's project. */
public interface McpPlatformAgentLifecyclePort {
    ResponseEntity<?> setEnabled(PlatformAuthenticatedSession actor, String agentId, boolean enabled);
    ResponseEntity<?> delete(PlatformAuthenticatedSession actor, String agentId);
}
