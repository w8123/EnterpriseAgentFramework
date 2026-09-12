package com.enterprise.ai.control.aicoding.api;

import com.enterprise.ai.control.aicoding.application.AiCodingCredentialPolicyService;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.CredentialPolicyUpdateCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.CredentialPolicyView;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-coding-console/projects/{projectId}/credential-policy")
@RequiredArgsConstructor
public class AiCodingCredentialPolicyConsoleController {

    private final AiCodingCredentialPolicyService policyService;

    @GetMapping
    public ResponseEntity<CredentialPolicyView> get(
            @PathVariable Long projectId) {
        return ResponseEntity.ok(policyService.get(projectId));
    }

    @PutMapping
    public ResponseEntity<CredentialPolicyView> update(
            @PathVariable Long projectId,
            @RequestBody CredentialPolicyUpdateCommand command,
            HttpServletRequest request) {
        return ResponseEntity.ok(policyService.update(
                projectId,
                command,
                authenticatedUsername(request)));
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
