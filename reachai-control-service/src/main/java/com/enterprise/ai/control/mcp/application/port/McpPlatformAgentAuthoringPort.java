package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;
import java.util.Map;

/** Agent identity creation through the same project authorization as the console. */
public interface McpPlatformAgentAuthoringPort {
    ResponseEntity<?> create(PlatformAuthenticatedSession actor, Map<String, Object> body);
    ResponseEntity<?> readDraft(PlatformAuthenticatedSession actor, String agentId);
    ResponseEntity<?> saveDraft(PlatformAuthenticatedSession actor, String agentId, String baseRevision, Map<String, Object> changes);
    ResponseEntity<?> copyVersionToDraft(PlatformAuthenticatedSession actor, String agentId, Long configVersionId, String baseRevision);
    ResponseEntity<?> publicationContext(PlatformAuthenticatedSession actor, String agentId, Long configVersionId, String baseRevision);
    ResponseEntity<?> publishApproved(PlatformAuthenticatedSession actor, String agentId, Long configVersionId, String baseRevision,
                                      Long projectId, String projectCode, Map<String, Long> workflowVersions);
}
