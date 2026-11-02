package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

public interface McpPlatformSkillCatalogPort {
    ResponseEntity<?> list(PlatformAuthenticatedSession actor, String search, String status, int offset, int limit);
    ResponseEntity<?> access(PlatformAuthenticatedSession actor);
    ResponseEntity<?> get(PlatformAuthenticatedSession actor, long skillId);
    ResponseEntity<?> version(PlatformAuthenticatedSession actor, long skillId, long versionId);
    ResponseEntity<?> bindableVersions(PlatformAuthenticatedSession actor, String search, String agentProjectCode,
                                       int offset, int limit);
    ResponseEntity<?> reviews(PlatformAuthenticatedSession actor, long skillId, long versionId);
    ResponseEntity<?> file(PlatformAuthenticatedSession actor, long skillId, long versionId, String path);
}
