package com.enterprise.ai.control.aicoding.api;

import com.enterprise.ai.control.aicoding.application.AiCodingCredentialPolicyService;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.CredentialPolicyUpdateCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.CredentialPolicyView;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/ai-coding-console/projects/{projectId}/credential-policy")
@RequiredArgsConstructor
public class AiCodingCredentialPolicyConsoleController {

    private final AiCodingCredentialPolicyService policyService;
    private final CapabilityProjectOnboardingClient projects;

    @GetMapping
    public ResponseEntity<CredentialPolicyView> get(
            @PathVariable Long projectId,
            HttpServletRequest request) {
        requireProject(request, projectId, false);
        return ResponseEntity.ok(policyService.get(projectId));
    }

    @PutMapping
    public ResponseEntity<CredentialPolicyView> update(
            @PathVariable Long projectId,
            @RequestBody CredentialPolicyUpdateCommand command,
            HttpServletRequest request) {
        requireProject(request, projectId, true);
        return ResponseEntity.ok(policyService.update(
                projectId,
                command,
                authenticatedUsername(request)));
    }

    /** The console route rule only admits the permission somewhere; the policy belongs to one project. */
    private void requireProject(HttpServletRequest request, Long projectId, boolean write) {
        if (!(request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE) instanceof PlatformAuthenticatedSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "platform session is required");
        }
        if (projectId == null || projectId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectId is invalid");
        Map<String, Object> project = projects.getProjectById(projectId);
        Object id = project == null ? null : project.getOrDefault("id", project.get("projectId"));
        Object code = project == null ? null : project.get("projectCode");
        if (!(id instanceof Number number) || number.longValue() != projectId || !(code instanceof String projectCode) || projectCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "project could not be resolved");
        }
        if (!AiCodingTaskConsoleController.allows(session, projectCode, write)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "project permission is required");
        }
    }

    private static String authenticatedUsername(HttpServletRequest request) {
        Object value = request.getAttribute(
                PlatformConsoleAuthInterceptor.USER_REQUEST_ATTRIBUTE);
        if (value instanceof PlatformPrincipal user) {
            return user.getUsername();
        }
        return null;
    }
}
