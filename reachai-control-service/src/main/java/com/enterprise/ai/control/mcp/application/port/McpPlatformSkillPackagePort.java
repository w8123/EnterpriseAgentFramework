package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

/** Skill ZIP inspection and upload import through the same console handlers, scope checks and audit as the page. */
public interface McpPlatformSkillPackagePort {
    ResponseEntity<?> discover(PlatformAuthenticatedSession actor, byte[] archive);
    /** Imports land as REVIEW_PENDING versions; review and publication stay separate, gated steps. */
    ResponseEntity<?> importPackage(PlatformAuthenticatedSession actor, String fileName, byte[] archive, String publisher, String version,
                                    String displayName, String visibility, String projectCode, String sourceRoot);
}
