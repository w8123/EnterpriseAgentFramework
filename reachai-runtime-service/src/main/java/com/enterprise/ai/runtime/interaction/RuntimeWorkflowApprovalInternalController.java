package com.enterprise.ai.runtime.interaction;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.execution.RuntimeWorkflowApprovalService;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Control-attested 待办 for Workflow HUMAN_APPROVAL nodes with named approvers. The approver's
 * runtime user id comes from the signed header; username, roles and project scope come from the
 * signed body Control built from the platform session.
 */
@RestController
@RequiredArgsConstructor
public class RuntimeWorkflowApprovalInternalController {

    private final RuntimeWorkflowApprovalService approvalService;

    public record ApproverEnvelope(String username, String displayName, List<String> roles,
                                   List<String> projectScope, Boolean admin) { }

    public record QueryRequest(String scope, Integer limit, ApproverEnvelope approver) { }

    public record DecisionRequest(String decision, String comment, ApproverEnvelope approver) { }

    @PostMapping("/internal/runtime/workflow-approvals/query")
    public ResponseEntity<?> query(HttpServletRequest httpRequest, @RequestBody QueryRequest request) {
        RuntimeWorkflowApprovalService.Approver approver = approver(httpRequest, request == null ? null : request.approver());
        if (approver == null) return unauthorized();
        String scope = request.scope() == null ? "pending" : request.scope().trim().toLowerCase(java.util.Locale.ROOT);
        int limit = request.limit() == null ? 50 : request.limit();
        Map<String, Object> body = new LinkedHashMap<>();
        switch (scope) {
            case "decided" -> body.put("items", approvalService.listDecided(approver, limit));
            case "all" -> body.put("items", approvalService.listPending(approver, true, limit));
            case "count" -> body.put("count", approvalService.countPending(approver));
            default -> body.put("items", approvalService.listPending(approver, false, limit));
        }
        return ResponseEntity.ok(body);
    }

    @PostMapping("/internal/runtime/workflow-approvals/{interactionId}/decision")
    public ResponseEntity<?> decide(HttpServletRequest httpRequest, @PathVariable String interactionId,
                                    @RequestBody DecisionRequest request) {
        RuntimeWorkflowApprovalService.Approver approver = approver(httpRequest, request == null ? null : request.approver());
        if (approver == null) return unauthorized();
        return ResponseEntity.ok(approvalService.decide(interactionId, approver, request.decision(), request.comment()));
    }

    @GetMapping("/internal/runtime/workflow-approvals/{interactionId}/runner-status")
    public ResponseEntity<?> runnerStatus(@PathVariable String interactionId,
                                          @RequestParam String sessionId,
                                          @RequestParam(required = false) String userId,
                                          @RequestParam(required = false) String appId) {
        return ResponseEntity.ok(approvalService.runnerStatus(interactionId, sessionId, userId, appId));
    }

    @ExceptionHandler(RuntimeWorkflowApprovalService.ApprovalException.class)
    public ResponseEntity<Map<String, Object>> approvalFailure(RuntimeWorkflowApprovalService.ApprovalException ex) {
        return ResponseEntity.status(ex.status()).body(Map.of("code", ex.code(), "message", ex.getMessage()));
    }

    private static RuntimeWorkflowApprovalService.Approver approver(HttpServletRequest httpRequest,
                                                                     ApproverEnvelope envelope) {
        Object attribute = httpRequest.getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR);
        if (!(attribute instanceof VerifiedInternalServiceAuth verified)
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION.equals(verified.identitySource())
                || !StringUtils.hasText(verified.identityUserId())
                || envelope == null || !StringUtils.hasText(envelope.username())) {
            return null;
        }
        return new RuntimeWorkflowApprovalService.Approver(verified.identityUserId(), envelope.username().trim(),
                envelope.displayName(), envelope.roles(), envelope.projectScope(), Boolean.TRUE.equals(envelope.admin()));
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("code", "RUNTIME_INTERNAL_AUTH_REQUIRED"));
    }
}
