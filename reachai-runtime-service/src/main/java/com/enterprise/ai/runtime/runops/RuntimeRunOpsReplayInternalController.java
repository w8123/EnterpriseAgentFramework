package com.enterprise.ai.runtime.runops;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/** Control-attested replay operator; an original business user is evidence, never delegated authority. */
@RestController
@RequiredArgsConstructor
public class RuntimeRunOpsReplayInternalController {
    private final RuntimeRunOpsReplayService replayService;

    @PostMapping("/internal/runtime/runops/traces/{traceId}/replay")
    public ResponseEntity<?> replay(HttpServletRequest httpRequest, @PathVariable String traceId,
                                    @RequestBody RuntimeRunOpsReplayService.ReplayRequest request) {
        Object attribute = httpRequest.getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR);
        if (!(attribute instanceof VerifiedInternalServiceAuth verified)
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION.equals(verified.identitySource())
                || !StringUtils.hasText(verified.identityUserId())
                || !StringUtils.hasText(verified.identityTenantId())) {
            return ResponseEntity.status(401).body(Map.of("code", "RUNTIME_INTERNAL_AUTH_REQUIRED"));
        }
        WorkflowExecutionIdentity identity = WorkflowExecutionIdentity.fromAgent(
                verified.identityTenantId(), null, null, verified.identityUserId());
        try {
            return ResponseEntity.ok(replayService.replay(traceId, request, identity));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }
}
