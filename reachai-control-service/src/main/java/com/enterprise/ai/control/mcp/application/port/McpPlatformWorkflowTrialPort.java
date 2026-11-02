package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import org.springframework.http.ResponseEntity;
import java.util.Map;

/** A connection-scoped, at-most-once read-only trial and its recovery status. */
public interface McpPlatformWorkflowTrialPort {
    ResponseEntity<?> run(ConnectionIdentity identity, String trialId, Map<String, Object> body);
    ResponseEntity<?> status(ConnectionIdentity identity, String trialId, String workflowId);
}
