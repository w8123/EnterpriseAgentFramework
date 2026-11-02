package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentDraftEditCommand;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentDraftEditView;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentDraftVersionCommand;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigVersionsView;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentDraftPublicationView;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentDraftApprovedPublishCommand;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.Objects;

/** Internal, signed Agent draft access; project facts must agree with the attested request. */
@RestController
@RequiredArgsConstructor
public class RuntimeAgentDraftEditController {
    private final RuntimeAgentConfigService configs;

    @ExceptionHandler(RuntimeAgentConfigService.HttpApiRiskDowngradeException.class)
    public ResponseEntity<Map<String, Object>> riskDowngrade(RuntimeAgentConfigService.HttpApiRiskDowngradeException rejected) {
        return ResponseEntity.badRequest().body(Map.of("code", "HTTP_API_WORKFLOW_RISK_DOWNGRADE", "message", rejected.getMessage()));
    }

    @PostMapping("/internal/runtime/agent-config-drafts/context")
    public AgentDraftEditView context(HttpServletRequest request, @RequestBody AgentDraftEditCommand command) {
        requireAttestedActor(request, command);
        return configs.readEditableDraft(command);
    }

    @PostMapping("/internal/runtime/agent-config-drafts/versions")
    public AgentConfigVersionsView versions(HttpServletRequest request, @RequestBody AgentDraftEditCommand command) {
        requireAttestedActor(request, command);
        return configs.readVersionsForProject(command);
    }

    @PostMapping("/internal/runtime/agent-config-drafts/save")
    public AgentDraftEditView save(HttpServletRequest request, @RequestBody AgentDraftEditCommand command) {
        requireAttestedActor(request, command);
        return configs.saveEditableDraft(command);
    }

    @PostMapping("/internal/runtime/agent-config-drafts/copy")
    public AgentDraftEditView copy(HttpServletRequest request, @RequestBody AgentDraftVersionCommand command) {
        requireAttestedActor(request, command.projectCode());
        return configs.copyEditableDraft(command);
    }

    @PostMapping("/internal/runtime/agent-config-drafts/publish")
    public AgentDraftEditView publish(HttpServletRequest request, @RequestBody AgentDraftVersionCommand command) {
        VerifiedInternalServiceAuth actor = requireAttestedActor(request, command.projectCode());
        return configs.publishEditableDraft(command, "platform-user:" + actor.identityUserId());
    }

    @PostMapping("/internal/runtime/agent-config-drafts/publication-context")
    public AgentDraftPublicationView publicationContext(HttpServletRequest request, @RequestBody AgentDraftVersionCommand command) {
        requireAttestedActor(request, command == null ? null : command.projectCode());
        return configs.previewDraftPublication(command);
    }

    @PostMapping("/internal/runtime/agent-config-drafts/publish-approved")
    public AgentDraftEditView publishApproved(HttpServletRequest request, @RequestBody AgentDraftApprovedPublishCommand command) {
        VerifiedInternalServiceAuth actor = requireAttestedActor(request, command == null ? null : command.projectCode());
        return configs.publishApprovedDraft(command, "platform-user:" + actor.identityUserId());
    }

    @ExceptionHandler(RuntimeAgentDraftConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(RuntimeAgentDraftConflictException conflict) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", RuntimeAgentDraftConflictException.CODE,
                "message", conflict.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException failure) {
        String message = failure.getMessage();
        String code = message != null && message.startsWith("BUSINESS_METHOD_WORKFLOW_RISK_DOWNGRADE:")
                ? "BUSINESS_METHOD_WORKFLOW_RISK_DOWNGRADE" : "AGENT_DRAFT_INVALID";
        return ResponseEntity.badRequest().body(Map.of("code", code));
    }

    private void requireAttestedActor(HttpServletRequest request, AgentDraftEditCommand command) {
        requireAttestedActor(request, command == null ? null : command.projectCode());
    }

    private VerifiedInternalServiceAuth requireAttestedActor(HttpServletRequest request, String projectCode) {
        Object attribute = request.getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR);
        String tenant = projectCode == null || projectCode.isBlank() ? "default" : projectCode;
        if (!(attribute instanceof VerifiedInternalServiceAuth auth)
                || !InternalServiceAuthHeaders.CALLER_CONTROL.equals(auth.caller())
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION.equals(auth.identitySource())
                || auth.identityUserId() == null || !auth.identityUserId().matches("[1-9][0-9]{0,18}")
                || !Objects.equals(tenant, auth.identityTenantId())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "verified platform actor is required");
        }
        return auth;
    }
}
