package com.enterprise.ai.runtime.runops.consolehttpapi;

import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpEgressPolicy;
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
        return write(() -> service.save(command, auth.identityUserId()));
    }

    /** Project + environment default inherited by every API of the verified owner without an active override. */
    @PutMapping("/project-default")
    public ResponseEntity<?> saveProjectDefault(HttpServletRequest request,
                                                @RequestBody HttpApiConsoleContracts.ConnectionCommand command) {
        VerifiedInternalServiceAuth auth = verified(request, command);
        if (auth == null) return unauthorized();
        return write(() -> service.saveProjectDefault(command, auth.identityUserId()));
    }

    /** Deactivates this API's override; {@code save.expectedRevision} pins the override row revision. */
    @PostMapping("/override-removals")
    public ResponseEntity<?> removeOverride(HttpServletRequest request,
                                            @RequestBody HttpApiConsoleContracts.ConnectionCommand command) {
        VerifiedInternalServiceAuth auth = verified(request, command);
        if (auth == null) return unauthorized();
        return write(() -> service.removeOverride(command, auth.identityUserId()));
    }

    private ResponseEntity<?> write(java.util.function.Supplier<HttpApiConsoleContracts.ConnectionView> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (RuntimeHttpApiConnectionService.Conflict conflict) {
            return ResponseEntity.status(409).body(error(conflict.code(), conflict.getMessage()));
        } catch (WorkflowHttpEgressPolicy.WorkflowHttpEgressException denied) {
            return ResponseEntity.status(409).body(error(denied.code(),
                    "服务地址被 HTTP 出站策略拒绝，请由管理员核对目标主机白名单；连接未保存"));
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
