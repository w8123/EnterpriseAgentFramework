package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

/** Turning a successful run into a Workflow candidate: check eligibility, then create (or reuse) the AI coding task. */
public interface McpPlatformRunCandidatePort {
    ResponseEntity<?> eligibility(PlatformAuthenticatedSession actor, String traceId);
    ResponseEntity<?> create(PlatformAuthenticatedSession actor, String traceId, String executorProvider);
}
