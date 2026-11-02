package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RuntimePageAssistantTrustedSmokeController {
    private final RuntimeWorkflowAiCodingService service;

    @PostMapping("/internal/runtime/workflows/ai-coding/page-assistant/smoke-test")
    public ResponseEntity<RuntimeWorkflowAiCodingService.RunView> smoke(HttpServletRequest request,
                                                                       @RequestBody Command command) {
        Object auth = request.getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR);
        if (!(auth instanceof VerifiedInternalServiceAuth verified)
                || !"EMBED_SESSION".equals(verified.identitySource())
                || !StringUtils.hasText(verified.identityUserId())
                || command == null || !StringUtils.hasText(command.workflowId())
                || !StringUtils.hasText(command.projectCode()) || !StringUtils.hasText(command.sessionId())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        var identity = WorkflowExecutionIdentity.fromEmbedSession(verified.identityTenantId(), null,
                command.projectCode(), verified.identityUserId());
        return ResponseEntity.ok(service.smokeTestPageAssistantTrusted(command.workflowId(), command.request(),
                command.projectCode(), command.sessionId(), identity));
    }

    public record Command(String workflowId, String projectCode, String sessionId,
                          RuntimeWorkflowAiCodingService.RunRequest request) { }
}
