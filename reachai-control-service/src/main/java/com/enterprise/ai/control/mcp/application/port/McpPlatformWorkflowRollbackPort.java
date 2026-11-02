package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

/** The Studio rollback chain: same publish permission, project check and working-copy revision lock. */
public interface McpPlatformWorkflowRollbackPort {
    ResponseEntity<?> rollback(PlatformAuthenticatedSession actor, String workflowId, long versionId, String baseRevision);
}
