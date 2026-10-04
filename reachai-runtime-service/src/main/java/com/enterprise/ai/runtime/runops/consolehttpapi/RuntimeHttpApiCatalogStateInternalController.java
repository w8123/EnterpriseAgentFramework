package com.enterprise.ai.runtime.runops.consolehttpapi;

import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Actor-scoped history and saved-connection indicators for one authorized API catalog page. */
@RestController
@RequestMapping("/internal/runtime/http-api-catalog-states")
@RequiredArgsConstructor
public class RuntimeHttpApiCatalogStateInternalController {
    private final RuntimeHttpApiCatalogStateService service;

    @PostMapping
    public ResponseEntity<?> read(HttpServletRequest request,
                                  @RequestBody HttpApiConsoleContracts.CatalogStatesRequest command) {
        Object raw = request.getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR);
        if (!(raw instanceof VerifiedInternalServiceAuth auth) || command == null
                || !InternalServiceAuthHeaders.CALLER_CONTROL.equals(auth.caller())
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION.equals(auth.identitySource())
                || !StringUtils.hasText(auth.identityUserId())
                || !StringUtils.hasText(auth.identityTenantId())
                || !auth.identityTenantId().equals(command.projectCode())) {
            return ResponseEntity.status(401).body(Map.of("success", false,
                    "code", "RUNTIME_INTERNAL_AUTH_REQUIRED", "message", "内部调用鉴权失败"));
        }
        try { return ResponseEntity.ok(service.read(command, auth.identityUserId())); }
        catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "code", "HTTP_API_CATALOG_STATES_INVALID", "message", "目录状态请求无效"));
        }
    }
}
