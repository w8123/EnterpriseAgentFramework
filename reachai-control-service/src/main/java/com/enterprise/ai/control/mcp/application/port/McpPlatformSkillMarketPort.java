package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Skill Market search, probe and import through the console controller, its permissions, scope checks and audit. */
public interface McpPlatformSkillMarketPort {
    ResponseEntity<?> search(PlatformAuthenticatedSession actor, String provider, String query, String view, String owner, int limit);
    ResponseEntity<?> sources(PlatformAuthenticatedSession actor);
    ResponseEntity<?> imports(PlatformAuthenticatedSession actor, int limit);
    ResponseEntity<?> probe(PlatformAuthenticatedSession actor, Map<String, String> request);
    ResponseEntity<?> importSkill(PlatformAuthenticatedSession actor, Map<String, String> request);
}
