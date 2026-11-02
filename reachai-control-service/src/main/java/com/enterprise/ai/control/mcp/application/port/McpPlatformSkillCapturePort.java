package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.List;

/** Turning run records into a Skill draft and saving it, through the same console handlers and checks as the run center. */
public interface McpPlatformSkillCapturePort {
    ResponseEntity<?> preview(PlatformAuthenticatedSession actor, String source, List<Long> callLogIds, String traceId, String goal);
    /** Saves the (possibly edited) SKILL.md as a REVIEW_PENDING version; review and publication stay gated. */
    ResponseEntity<?> create(PlatformAuthenticatedSession actor, String projectCode, String displayName, String skillMd,
                             String visibility, String version, String sourceRef);
}
