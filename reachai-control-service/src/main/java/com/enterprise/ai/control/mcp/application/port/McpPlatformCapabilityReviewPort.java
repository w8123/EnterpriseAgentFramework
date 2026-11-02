package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

/** Applies, ignores or rolls back one scanned capability change through the console's review controller. */
public interface McpPlatformCapabilityReviewPort {
    enum Action { APPLY, IGNORE, ROLLBACK }
    ResponseEntity<?> review(PlatformAuthenticatedSession actor, String projectCode, long diffItemId, Action action, String note);
}
