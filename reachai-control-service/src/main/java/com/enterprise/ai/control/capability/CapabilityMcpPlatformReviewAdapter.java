package com.enterprise.ai.control.capability;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCapabilityReviewPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Runs a capability change review through the controller a person uses on the capability review panel. */
@Component
@RequiredArgsConstructor
final class CapabilityMcpPlatformReviewAdapter implements McpPlatformCapabilityReviewPort {
    private final CapabilityReviewConsoleController reviews;
    private final PlatformDelegatedRequestScope scope;

    @Override
    public ResponseEntity<?> review(PlatformAuthenticatedSession actor, String projectCode, long diffItemId, Action action, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action.name());
        if (note != null) body.put("note", note);
        return scope.run(actor, request -> action == Action.ROLLBACK
                ? reviews.rollbackCapabilityDiffItem(request, projectCode, diffItemId, body)
                : reviews.reviewCapabilityDiffItem(request, projectCode, diffItemId, body));
    }
}
