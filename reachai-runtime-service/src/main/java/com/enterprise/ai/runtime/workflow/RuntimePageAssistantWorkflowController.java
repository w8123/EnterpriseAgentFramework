package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowAgentAttachmentPort.PageAssistantAttachRequest;
import com.enterprise.ai.runtime.workflow.aicoding.AiCodingAttachmentException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Signed sync of a published Page Assistant into the project page copilot. Control fills the project from the
 * Workflow it authorized; the publisher is the signed platform user.
 */
@RestController
@RequiredArgsConstructor
public class RuntimePageAssistantWorkflowController {
    static final String CONTEXT_PATH = "/internal/runtime/workflows/{id}/page-assistant/context";
    static final String ATTACH_PATH = "/internal/runtime/workflows/{id}/page-assistant/attach";

    private final RuntimePageAssistantWorkflowAttachmentService attachmentService;

    @PostMapping(CONTEXT_PATH)
    public ResponseEntity<?> context(HttpServletRequest http, @PathVariable String id, @RequestBody ProjectCommand command) {
        requireActor(http, command == null ? null : command.projectCode());
        try {
            return ResponseEntity.ok(attachmentService.context(id, command.projectId(), command.projectCode()));
        } catch (AiCodingAttachmentException ex) {
            return ResponseEntity.status(ex.status()).body(ex.toErrorBody());
        }
    }

    @PostMapping(ATTACH_PATH)
    public ResponseEntity<?> attach(HttpServletRequest http, @PathVariable String id, @RequestBody AttachCommand command) {
        VerifiedInternalServiceAuth actor = requireActor(http, command == null ? null : command.projectCode());
        try {
            return ResponseEntity.ok(attachmentService.attachReviewed(id, new PageAssistantAttachRequest(
                    command.projectId(), command.projectCode(), command.agentId(), command.modelInstanceId(),
                    "platform-user:" + actor.identityUserId(), command.replaceWorkflowId(), command.baseRevision(),
                    Boolean.TRUE.equals(command.createTarget()))));
        } catch (AiCodingAttachmentException ex) {
            return ResponseEntity.status(ex.status()).body(ex.toErrorBody());
        }
    }

    private static VerifiedInternalServiceAuth requireActor(HttpServletRequest http, String projectCode) {
        Object attribute = http.getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR);
        if (!(attribute instanceof VerifiedInternalServiceAuth auth)
                || !InternalServiceAuthHeaders.CALLER_CONTROL.equals(auth.caller())
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION.equals(auth.identitySource())
                || auth.identityUserId() == null || !auth.identityUserId().matches("[1-9][0-9]{0,18}")
                || projectCode == null || projectCode.isBlank() || !projectCode.equals(auth.identityTenantId())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "verified platform actor is required");
        }
        return auth;
    }

    public record ProjectCommand(Long projectId, String projectCode) { }

    public record AttachCommand(Long projectId, String projectCode, String agentId, String modelInstanceId,
                                String replaceWorkflowId, String baseRevision, Boolean createTarget) { }
}
