package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentDraftEditCommand;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentDraftEditView;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentDraftVersionCommand;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

/** The project-key path preserves project identity and cannot stand in for a platform user's approval. */
@RestController
@RequiredArgsConstructor
public class RuntimeProjectAgentDraftEditController {
    private final RuntimeAgentConfigService configs;

    @ExceptionHandler(RuntimeAgentConfigService.HttpApiRiskDowngradeException.class)
    public ResponseEntity<Map<String, Object>> riskDowngrade(RuntimeAgentConfigService.HttpApiRiskDowngradeException rejected) {
        return ResponseEntity.badRequest().body(Map.of("code", "HTTP_API_WORKFLOW_RISK_DOWNGRADE", "message", rejected.getMessage()));
    }

    @PostMapping("/internal/runtime/project-agent-config-drafts/context")
    public AgentDraftEditView context(HttpServletRequest request, @RequestBody AgentDraftEditCommand command) {
        requireProject(request, command == null ? null : command.projectId(), command == null ? null : command.projectCode());
        return configs.readEditableDraft(command);
    }
    @PostMapping("/internal/runtime/project-agent-config-drafts/save")
    public AgentDraftEditView save(HttpServletRequest request, @RequestBody AgentDraftEditCommand command) {
        requireProject(request, command == null ? null : command.projectId(), command == null ? null : command.projectCode());
        return configs.saveEditableDraft(command);
    }

    @PostMapping("/internal/runtime/project-agent-config-drafts/versions")
    public RuntimeAgentConfigViews.AgentConfigVersionsView versions(HttpServletRequest request, @RequestBody AgentDraftEditCommand command) {
        requireProject(request, command == null ? null : command.projectId(), command == null ? null : command.projectCode());
        return configs.readVersionsForProject(command);
    }
    @PostMapping("/internal/runtime/project-agent-config-drafts/publish")
    public AgentDraftEditView publish(HttpServletRequest request, @RequestBody AgentDraftVersionCommand command) {
        var actor = requireProject(request, command == null ? null : command.projectId(), command == null ? null : command.projectCode());
        // Keep the full credential fingerprint inside the existing VARCHAR(64) audit field.
        String fingerprint = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.util.HexFormat.of().parseHex(actor.identityUserId().substring("ai-coding-".length())));
        return configs.publishEditableDraft(command, "project-key:" + fingerprint);
    }
    @ExceptionHandler(RuntimeAgentDraftConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(RuntimeAgentDraftConflictException conflict) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", RuntimeAgentDraftConflictException.CODE,
                "message", conflict.getMessage()));
    }
    private VerifiedInternalServiceAuth requireProject(HttpServletRequest request, Long projectId, String projectCode) {
        Object value = request.getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR);
        if (!(value instanceof VerifiedInternalServiceAuth actor)
                || !InternalServiceAuthHeaders.CALLER_CONTROL.equals(actor.caller())
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL.equals(actor.identitySource())
                || actor.identityUserId() == null || !actor.identityUserId().matches("ai-coding-[a-f0-9]{64}")
                || projectId == null || projectId < 1 || projectCode == null || projectCode.isBlank()
                || !projectCode.equals(actor.identityTenantId())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "verified AI Coding project is required");
        }
        return actor;
    }
}
