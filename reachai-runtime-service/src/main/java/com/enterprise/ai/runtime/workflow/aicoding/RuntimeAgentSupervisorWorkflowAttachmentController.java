package com.enterprise.ai.runtime.workflow.aicoding;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowAgentAttachmentPort;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RuntimeAgentSupervisorWorkflowAttachmentController {

    private final RuntimeWorkflowAgentAttachmentPort attachmentService;

    @PostMapping("/internal/runtime/projects/{projectId}/agent-supervisor/workflow-tools/attach")
    public ResponseEntity<?> attach(
            jakarta.servlet.http.HttpServletRequest http,
            @PathVariable("projectId") Long projectId,
            @RequestBody RuntimeWorkflowAgentAttachmentPort.AttachRequest request) {
        Object identity = http.getAttribute(com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth.REQUEST_ATTR);
        if (!(identity instanceof com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth actor)
                || !com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders.CALLER_CONTROL.equals(actor.caller())
                || !com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL.equals(actor.identitySource())
                || actor.identityUserId() == null || !actor.identityUserId().matches("ai-coding-[a-f0-9]{64}")
                || actor.identityTenantId() == null || actor.identityTenantId().isBlank() || projectId == null || projectId < 1)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "verified AI Coding project is required");
        String audit = "project-key:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.util.HexFormat.of().parseHex(actor.identityUserId().substring("ai-coding-".length())));
        if (request == null || request.baseRevision() == null || !request.baseRevision().matches("[a-f0-9]{64}")
                || (request.agentId() == null || request.agentId().isBlank()) && (request.agentKeySlug() == null || request.agentKeySlug().isBlank()))
            return ResponseEntity.badRequest().body(new AiCodingAttachmentException("AGENT_DRAFT_REVISION_REQUIRED",
                    "Read the target Agent context and send its agentId or agentKeySlug and baseRevision before attachment").toErrorBody());
        var attested = new RuntimeWorkflowAgentAttachmentPort.AttachRequest(request.workflowId(), request.agentId(), request.agentKeySlug(),
                request.modelInstanceId(), audit, request.toolName(), request.descriptionOverride(), request.inputSchema(), request.outputSchema(),
                request.riskLevel(), request.permissionKey(), request.readOnly(), request.priority(), request.replaceWorkflowId(), request.baseRevision());
        try {
            return ResponseEntity.ok(attachmentService.attachInKnownProject(projectId, actor.identityTenantId(), attested));
        } catch (AiCodingAttachmentException ex) {
            return ResponseEntity.status(ex.status()).body(ex.toErrorBody());
        }
    }
}
