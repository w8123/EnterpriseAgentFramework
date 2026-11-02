package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.Objects;

/** Exact, project-bound publication snapshots; Control owns human approval and permissions. */
@RestController
@RequiredArgsConstructor
public class RuntimeWorkflowPublicationController {
    private final RuntimeWorkflowVersionService releases;

    @PostMapping("/internal/runtime/workflow-releases/context")
    public ResponseEntity<?> context(HttpServletRequest http, @RequestBody RuntimeWorkflowVersionService.PublicationRequest request) {
        if (actor(http, request) == null) return unauthorized();
        return ResponseEntity.ok(releases.previewPublication(request));
    }

    @PostMapping("/internal/runtime/workflow-releases/publish")
    public ResponseEntity<?> publish(HttpServletRequest http, @RequestBody RuntimeWorkflowVersionService.PublicationRequest request) {
        String actor = actor(http, request);
        if (actor == null) return unauthorized();
        return ResponseEntity.ok(RuntimeWorkflowVersionView.fromEntity(releases.publishApproved(request, actor)));
    }

    @ExceptionHandler(RuntimeWorkflowVersionService.PublicationChanged.class)
    public ResponseEntity<?> changed() {
        return ResponseEntity.status(409).body(Map.of("code", "WORKFLOW_PUBLICATION_CHANGED", "message", "发布内容或线上版本已变化，请重新读取并审核"));
    }

    private String actor(HttpServletRequest http, RuntimeWorkflowVersionService.PublicationRequest request) {
        Object raw = http.getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR);
        if (request == null || !(raw instanceof VerifiedInternalServiceAuth auth)
                || !InternalServiceAuthHeaders.CALLER_CONTROL.equals(auth.caller())
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION.equals(auth.identitySource())
                || auth.identityUserId() == null || !auth.identityUserId().matches("[1-9][0-9]{0,18}")) return null;
        String tenant = request.projectCode() == null || request.projectCode().isBlank() ? "default" : request.projectCode();
        return Objects.equals(tenant, auth.identityTenantId()) ? "platform:" + auth.identityUserId() : null;
    }

    private ResponseEntity<?> unauthorized() { return ResponseEntity.status(401).body(Map.of("code", "RUNTIME_INTERNAL_AUTH_REQUIRED")); }
}
