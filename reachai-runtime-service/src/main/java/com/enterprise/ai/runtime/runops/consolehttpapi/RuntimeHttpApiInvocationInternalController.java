package com.enterprise.ai.runtime.runops.consolehttpapi;

import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Signed Control-only HTTP API trial call and actor-owned result read-back. */
@RestController
@RequestMapping("/internal/runtime/http-api-invocations")
@RequiredArgsConstructor
public class RuntimeHttpApiInvocationInternalController {
    private final RuntimeHttpApiInvocationService service;

    @PostMapping
    public ResponseEntity<?> invoke(HttpServletRequest request,
                                    @RequestBody HttpApiConsoleContracts.InvocationCommand command) {
        VerifiedInternalServiceAuth auth = verified(request);
        if (auth == null || command == null || !auth.identityUserId().equals(command.platformActorId())
                || !auth.identityTenantId().equals(command.projectCode())) return unauthorized();
        try {
            return ResponseEntity.ok(service.invoke(command));
        } catch (RuntimeHttpApiInvocationService.Conflict conflict) {
            return ResponseEntity.status(409).body(error(conflict.code(), conflict.getMessage()));
        } catch (RuntimeHttpApiConnectionService.Conflict conflict) {
            return ResponseEntity.status(409).body(error(conflict.code(), conflict.getMessage()));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(error("HTTP_API_INVOCATION_INVALID", "试调用参数无效"));
        }
    }

    @GetMapping("/{invocationId}")
    public ResponseEntity<?> get(HttpServletRequest request, @PathVariable String invocationId) {
        VerifiedInternalServiceAuth auth = verified(request);
        if (auth == null) return unauthorized();
        try {
            HttpApiConsoleContracts.InvocationOutcome result = service.get(invocationId, auth.identityUserId());
            if (result == null || !auth.identityTenantId().equals(result.projectCode())) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.notFound().build();
        }
    }

    private VerifiedInternalServiceAuth verified(HttpServletRequest request) {
        Object raw = request.getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR);
        if (!(raw instanceof VerifiedInternalServiceAuth auth)
                || !InternalServiceAuthHeaders.CALLER_CONTROL.equals(auth.caller())
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION.equals(auth.identitySource())
                || !StringUtils.hasText(auth.identityTenantId()) || !StringUtils.hasText(auth.identityUserId())) {
            return null;
        }
        return auth;
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(error("RUNTIME_INTERNAL_AUTH_REQUIRED", "内部调用鉴权失败"));
    }

    private Map<String, Object> error(String code, String message) {
        return Map.of("success", false, "code", code, "message", message);
    }
}
