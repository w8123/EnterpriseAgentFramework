package com.enterprise.ai.capability.catalog.httpapi;

import com.enterprise.ai.capability.internalauth.CapabilityVerifiedInternalServiceAuth;
import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.common.capability.HttpApiFirstCallPolicy;
import com.enterprise.ai.common.capability.HttpApiConsolePolicy;
import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** HMAC protected owner read and acceptance endpoints; the browser uses Control. */
@RestController
@RequestMapping("/internal/capability/http-apis")
@RequiredArgsConstructor
public class HttpApiCatalogInternalController {
    private final HttpApiCatalogService catalog;

    @GetMapping
    public ResponseEntity<?> list(@RequestParam long projectId,
                                  @RequestParam(required = false) String environment,
                                  @RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) String method,
                                  @RequestParam(required = false) String sourceStatus,
                                  @RequestParam(defaultValue = "1") int current,
                                  @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(catalog.list(projectId, environment, keyword, method, sourceStatus, current, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable long id) {
        HttpApiCatalogService.ApiDetail detail = catalog.detail(id);
        return detail == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(detail);
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<?> accept(HttpServletRequest request, @PathVariable long id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        CapabilityVerifiedInternalServiceAuth actor = verified(request);
        if (actor == null || !InternalServiceAuthHeaders.CALLER_CONTROL.equals(actor.caller())
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION.equals(actor.identitySource())) {
            return ResponseEntity.status(401).build();
        }
        if (body == null || !body.keySet().equals(java.util.Set.of("expectedSourceSetRevision"))
                || !(body.get("expectedSourceSetRevision") instanceof String revision)) {
            return ResponseEntity.badRequest().body(error("HTTP_API_ACCEPT_REQUEST_INVALID", "接纳请求格式无效"));
        }
        try {
            HttpApiCatalogService.ApiDetail accepted = catalog.accept(id, revision, actor.identityUserId());
            return accepted == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(accepted);
        } catch (HttpApiCatalogService.Conflict conflict) {
            return ResponseEntity.status(409).body(error(conflict.code(), conflict.getMessage()));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(error("HTTP_API_ACCEPT_REQUEST_INVALID", "接纳请求格式无效"));
        }
    }

    @PostMapping("/{id}/execution-context")
    public ResponseEntity<?> executionContext(HttpServletRequest request, @PathVariable long id,
                                              @RequestBody(required = false) Map<String, Object> body) {
        CapabilityVerifiedInternalServiceAuth caller = verified(request);
        if (caller == null || !InternalServiceAuthHeaders.CALLER_RUNTIME.equals(caller.caller())
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_TENANT_TRUSTED.equals(caller.identitySource())
                || !StringUtils.hasText(caller.identityTenantId()) || body == null
                || !(body.get("apiId") instanceof Number requested) || requested.longValue() != id) {
            return ResponseEntity.status(401).build();
        }
        HttpApiCatalogService.ApiDetail detail = catalog.detail(id);
        if (detail == null) return ResponseEntity.notFound().build();
        HttpApiCatalogService.ApiSummary summary = detail.summary();
        if (!summary.projectCode().equals(caller.identityTenantId())) return ResponseEntity.status(403).build();
        String unsupported = HttpApiFirstCallPolicy.unsupportedReason(detail.acceptedContract());
        String consoleUnsupported = HttpApiConsolePolicy.unsupportedReason(detail.acceptedContract());
        return ResponseEntity.ok(new HttpApiConsoleContracts.ExecutionContext(
                HttpApiConsoleContracts.VERSION, id, summary.qualifiedName(), summary.projectId(),
                summary.projectCode(), summary.environment(), summary.httpMethod(), summary.routeTemplate(),
                summary.sourceConfirmed(), summary.sourceStatus(), summary.sourceReason(),
                summary.candidateContractHash(), summary.acceptedContractHash(), summary.sourceSetRevision(),
                detail.acceptedContract(), unsupported == null, unsupported,
                consoleUnsupported == null, consoleUnsupported));
    }

    private CapabilityVerifiedInternalServiceAuth verified(HttpServletRequest request) {
        Object value = request.getAttribute(CapabilityVerifiedInternalServiceAuth.REQUEST_ATTR);
        return value instanceof CapabilityVerifiedInternalServiceAuth auth ? auth : null;
    }

    private Map<String, Object> error(String code, String message) {
        return Map.of("success", false, "code", code, "message", message);
    }
}
