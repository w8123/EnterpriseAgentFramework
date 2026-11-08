package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

/** Console-authorized provisioning of the page assistant for a project. */
public interface McpPlatformProjectAgentProvisioningPort {
    ResponseEntity<?> provision(PlatformAuthenticatedSession actor, long projectId);
}
