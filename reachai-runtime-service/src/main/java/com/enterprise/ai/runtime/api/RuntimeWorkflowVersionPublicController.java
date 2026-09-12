package com.enterprise.ai.runtime.api;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowManagementService;
import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class RuntimeWorkflowVersionPublicController {

    private final RuntimeWorkflowManagementService workflowManagementService;

    @GetMapping("/api/workflows/{workflowId}/versions")
    public ResponseEntity<List<RuntimeWorkflowVersionView>> list(@PathVariable String workflowId) {
        return ResponseEntity.ok(workflowManagementService.listVersions(workflowId));
    }

    @PostMapping("/api/workflows/{workflowId}/versions/publish")
    public ResponseEntity<?> publishExplicit(@PathVariable String workflowId,
                                             @RequestBody RuntimeWorkflowVersionPublishRequest request,
                                             HttpServletRequest httpRequest) {
        return publishWorkflow(workflowId, request, requireActor(httpRequest));
    }

    @PostMapping("/api/workflows/{workflowId}/versions/validate")
    public ResponseEntity<RuntimeWorkflowReleaseValidationResult> validate(@PathVariable String workflowId) {
        return ResponseEntity.ok(workflowManagementService.validateRelease(workflowId));
    }

    @PostMapping("/api/workflows/{workflowId}/versions/{versionId}/rollback")
    public ResponseEntity<RuntimeWorkflowVersionView> rollback(
            @PathVariable String workflowId,
            @PathVariable Long versionId,
            @RequestBody RuntimeWorkflowVersionRollbackRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(workflowManagementService.rollback(
                workflowId,
                versionId,
                requireActor(httpRequest),
                request == null ? null : request.baseRevision()));
    }

    private ResponseEntity<?> publishWorkflow(String workflowId, RuntimeWorkflowVersionPublishRequest request, String actor) {
        try {
            int rollout = request == null || request.rolloutPercent() == null ? 100 : request.rolloutPercent();
            return ResponseEntity.ok(workflowManagementService.publish(
                    workflowId,
                    request == null ? null : request.version(),
                    rollout,
                    request == null ? null : request.note(),
                    actor,
                    request == null ? null : request.baseRevision()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    private String requireActor(HttpServletRequest request) {
        Object candidate = request == null ? null : request.getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR);
        if (!(candidate instanceof VerifiedInternalServiceAuth verified)
                || !InternalServiceAuthHeaders.CALLER_CONTROL.equals(verified.caller())
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION.equals(verified.identitySource())
                || verified.identityUserId() == null || verified.identityUserId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Verified platform identity is required for Workflow release");
        }
        return "platform:" + verified.identityUserId();
    }
}
