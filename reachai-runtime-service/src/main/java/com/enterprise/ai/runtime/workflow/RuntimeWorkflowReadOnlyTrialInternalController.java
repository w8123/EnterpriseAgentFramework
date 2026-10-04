package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy;
import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/** Only the HMAC-authenticated Control session may request a project-scoped draft trial. */
@RestController
@RequiredArgsConstructor
public class RuntimeWorkflowReadOnlyTrialInternalController {
    private final RuntimeWorkflowReadOnlyTrialService service;

    @PostMapping("/internal/runtime/workflows/studio/read-only-trials")
    public ResponseEntity<?> run(HttpServletRequest request,
                                 @RequestBody(required = false) WorkflowReadOnlyTrialPolicy.TrialCommand command) {
        Object raw = request.getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR);
        if (!(raw instanceof VerifiedInternalServiceAuth auth)
                || !InternalServiceAuthHeaders.CALLER_CONTROL.equals(auth.caller())
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION.equals(auth.identitySource())
                || !StringUtils.hasText(auth.identityTenantId())
                || !StringUtils.hasText(auth.identityUserId())
                || command == null
                || !Objects.equals(auth.identityTenantId(), command.projectCode())
                || !Objects.equals(auth.identityUserId(), command.platformActorId())) {
            return ResponseEntity.status(401).body(error("RUNTIME_INTERNAL_AUTH_REQUIRED", "内部调用鉴权失败"));
        }
        try {
            return ResponseEntity.ok(service.run(command));
        } catch (RuntimeWorkflowReadOnlyTrialService.Conflict rejected) {
            return ResponseEntity.status(409).body(error(rejected.code(), "试运行条件已变化，请检查草稿和业务方法/API 状态"));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(error("HTTP_API_TRIAL_REQUEST_INVALID", "试运行参数无效"));
        }
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("success", false, "errorCode", code, "errorMessage", message);
    }
}
