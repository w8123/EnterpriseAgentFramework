package com.enterprise.ai.runtime.runops.consolehttpapi;

import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Control-only project connection boundary; no browser can choose an owner in Runtime. */
@RestController
@RequestMapping("/internal/runtime/http-api-connections")
@RequiredArgsConstructor
public class RuntimeHttpApiConnectionInternalController {
    private final RuntimeHttpApiConnectionService service;

    @PostMapping
    public ResponseEntity<?> read(HttpServletRequest request,
                                  @RequestBody HttpApiConsoleContracts.ConnectionCommand command) {
        VerifiedInternalServiceAuth auth = verified(request, command);
        if (auth == null) return unauthorized();
        try {
            return ResponseEntity.ok(service.read(command));
        } catch (RuntimeHttpApiConnectionService.Conflict conflict) {
            return ResponseEntity.status(409).body(error(conflict.code(), conflict.getMessage()));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(error("HTTP_API_CONNECTION_INVALID", "连接请求无效"));
        }
    }

    @PutMapping
    public ResponseEntity<?> save(HttpServletRequest request,
                                  @RequestBody HttpApiConsoleContracts.ConnectionCommand command) {
        VerifiedInternalServiceAuth auth = verified(request, command);
        if (auth == null) return unauthorized();
        try {
            return ResponseEntity.ok(service.save(command, auth.identityUserId()));
        } catch (RuntimeHttpApiConnectionService.Conflict conflict) {
            return ResponseEntity.status(409).body(error(conflict.code(), conflict.getMessage()));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(error("HTTP_API_CONNECTION_INVALID", "连接请求无效"));
        }
    }

    private VerifiedInternalServiceAuth verified(HttpServletRequest request,
                                                 HttpApiConsoleContracts.ConnectionCommand command) {
        Object raw = request.getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR);
        if (!(raw instanceof VerifiedInternalServiceAuth auth) || command == null
                || !InternalServiceAuthHeaders.CALLER_CONTROL.equals(auth.caller())
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION.equals(auth.identitySource())
                || !StringUtils.hasText(auth.identityUserId())
                || !StringUtils.hasText(auth.identityTenantId())
                || !auth.identityTenantId().equals(command.projectCode())) return null;
        return auth;
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(error("RUNTIME_INTERNAL_AUTH_REQUIRED", "内部调用鉴权失败"));
    }

    private Map<String, Object> error(String code, String message) {
        return Map.of("success", false, "code", code, "message", message);
    }
}
