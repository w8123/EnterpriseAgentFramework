package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;

/** Skill version reviews and lifecycle transitions through the console controller, its permissions and audit. */
public interface McpPlatformSkillGovernancePort {
    enum Operation { REVIEW, PUBLISH, SET_DEFAULT, DEPRECATE, REVOKE }
    ResponseEntity<?> change(PlatformAuthenticatedSession actor, long skillId, long versionId, Operation operation,
                             String decision, String comment, JsonNode findings);
}
