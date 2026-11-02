package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

public interface McpPlatformWorkflowReleasePort {
    ResponseEntity<Object> context(PlatformAuthenticatedSession actor, String workflowId, String revision, String version, String note);
    ResponseEntity<Object> publish(PlatformAuthenticatedSession actor, String workflowId, String revision, String version, String note,
                                   Long projectId, String projectCode, String digest);
}
