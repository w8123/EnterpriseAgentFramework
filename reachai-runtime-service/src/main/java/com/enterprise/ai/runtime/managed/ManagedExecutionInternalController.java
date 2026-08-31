package com.enterprise.ai.runtime.managed;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.CancelRequest;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ApprovalDecisionRequest;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ApprovalDecisionView;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.CreateRequest;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.CreatedView;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ExecutionView;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ArtifactView;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ApprovalView;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/internal/runtime/managed-executions")
@RequiredArgsConstructor
public class ManagedExecutionInternalController {

    private final ManagedExecutionService executionService;
    private final ManagedArtifactReadService artifactReadService;

    @PostMapping
    public ResponseEntity<CreatedView> create(HttpServletRequest servletRequest,
                                              @RequestBody CreateRequest request) {
        VerifiedInternalServiceAuth auth = requireControlAuth(servletRequest);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(executionService.create(auth.identityTenantId(), auth.identityUserId(), request));
    }

    @GetMapping("/{executionId}")
    public ResponseEntity<ExecutionView> get(HttpServletRequest servletRequest,
                                             @PathVariable String executionId) {
        VerifiedInternalServiceAuth auth = requireControlAuth(servletRequest);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(executionService.get(executionId, auth.identityTenantId()));
    }

    @GetMapping("/{executionId}/artifacts")
    public ResponseEntity<List<ArtifactView>> artifacts(
            HttpServletRequest servletRequest,
            @PathVariable String executionId) {
        VerifiedInternalServiceAuth auth = requireControlAuth(servletRequest);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(artifactReadService.list(executionId, auth.identityTenantId()));
    }

    @GetMapping("/{executionId}/approval")
    public ResponseEntity<ApprovalView> approval(
            HttpServletRequest servletRequest,
            @PathVariable String executionId) {
        VerifiedInternalServiceAuth auth = requireControlAuth(servletRequest);
        ApprovalView approval = executionService.approval(
                executionId, auth.identityTenantId());
        if (approval == null) {
            return ResponseEntity.noContent()
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(approval);
    }

    @GetMapping("/{executionId}/artifacts/{artifactId}")
    public ResponseEntity<byte[]> artifact(
            HttpServletRequest servletRequest,
            @PathVariable String executionId,
            @PathVariable String artifactId) {
        VerifiedInternalServiceAuth auth = requireControlAuth(servletRequest);
        ManagedArtifactReadService.ArtifactContent content = artifactReadService.read(
                executionId, artifactId, auth.identityTenantId());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(content.metadata().mediaType()))
                .contentLength(content.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(content.filename(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header("X-ReachAI-Artifact-SHA256", content.metadata().sha256())
                .body(content.bytes());
    }

    @PostMapping("/{executionId}:cancel")
    public ResponseEntity<ExecutionView> cancel(HttpServletRequest servletRequest,
                                                @PathVariable String executionId,
                                                @RequestBody(required = false) CancelRequest request) {
        VerifiedInternalServiceAuth auth = requireControlAuth(servletRequest);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(executionService.cancel(
                        executionId,
                        auth.identityTenantId(),
                        request == null ? null : request.reason()));
    }

    @PostMapping("/{executionId}/approvals/{interactionId}:resolve")
    public ResponseEntity<ApprovalDecisionView> resolveApproval(
            HttpServletRequest servletRequest,
            @PathVariable String executionId,
            @PathVariable String interactionId,
            @RequestBody ApprovalDecisionRequest request) {
        VerifiedInternalServiceAuth auth = requireControlAuth(servletRequest);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(executionService.resolveApproval(
                        executionId,
                        auth.identityTenantId(),
                        auth.identityUserId(),
                        interactionId,
                        request));
    }

    private VerifiedInternalServiceAuth requireControlAuth(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR);
        if (!(value instanceof VerifiedInternalServiceAuth verified)
                || !InternalServiceAuthHeaders.CALLER_CONTROL.equals(verified.caller())
                || !StringUtils.hasText(verified.identityTenantId())
                || !StringUtils.hasText(verified.identityUserId())) {
            throw new ManagedExecutionException(401, "RUNTIME_INTERNAL_AUTH_REQUIRED",
                    "Internal service authentication failed");
        }
        return verified;
    }
}
