package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import com.enterprise.ai.runtime.memory.RuntimeSessionRetentionException;
import com.enterprise.ai.runtime.memory.RuntimeSessionRetentionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** HMAC-authenticated Control administration boundary for Runtime-owned session retention. */
@RestController
@RequestMapping("/internal/runtime/session-retention")
public class RuntimeSessionRetentionInternalController {

    private final RuntimeSessionRetentionService retentionService;

    public RuntimeSessionRetentionInternalController(
            RuntimeSessionRetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @GetMapping("/tenants/{tenantId}/policy")
    public ResponseEntity<RuntimeSessionRetentionService.PolicyView> policy(
            HttpServletRequest request,
            @PathVariable String tenantId) {
        requirePlatformAdministrator(request, tenantId);
        return ResponseEntity.ok(retentionService.policy(tenantId));
    }

    @PutMapping("/tenants/{tenantId}/policy")
    public ResponseEntity<RuntimeSessionRetentionService.PolicyView> savePolicy(
            HttpServletRequest request,
            @PathVariable String tenantId,
            @RequestBody PolicyCommand command) {
        String actorId = requirePlatformAdministrator(request, tenantId);
        if (command == null) {
            throw new IllegalArgumentException("policy body is required");
        }
        return ResponseEntity.ok(retentionService.savePolicy(
                tenantId,
                command.activeRetentionDays(),
                command.clearedRetentionHours(),
                actorId));
    }

    @DeleteMapping("/tenants/{tenantId}/policy")
    public ResponseEntity<RuntimeSessionRetentionService.PolicyView> deletePolicy(
            HttpServletRequest request,
            @PathVariable String tenantId) {
        return ResponseEntity.ok(retentionService.deletePolicy(
                tenantId, requirePlatformAdministrator(request, tenantId)));
    }

    @GetMapping("/tenants/{tenantId}/sessions/{sessionId}")
    public ResponseEntity<RuntimeSessionRetentionService.SessionView> session(
            HttpServletRequest request,
            @PathVariable String tenantId,
            @PathVariable String sessionId) {
        requirePlatformAdministrator(request, tenantId);
        return ResponseEntity.ok(retentionService.session(tenantId, sessionId));
    }

    @PutMapping("/tenants/{tenantId}/sessions/{sessionId}/legal-hold")
    public ResponseEntity<RuntimeSessionRetentionService.SessionView> legalHold(
            HttpServletRequest request,
            @PathVariable String tenantId,
            @PathVariable String sessionId,
            @RequestBody LegalHoldCommand command) {
        String actorId = requirePlatformAdministrator(request, tenantId);
        if (command == null) {
            throw new IllegalArgumentException("legal hold body is required");
        }
        return ResponseEntity.ok(retentionService.setLegalHold(
                tenantId,
                sessionId,
                command.enabled(),
                command.reasonCode(),
                command.referenceId(),
                actorId));
    }

    @PostMapping("/tenants/{tenantId}/sessions/{sessionId}/erase")
    public ResponseEntity<RuntimeSessionRetentionService.EraseResult> erase(
            HttpServletRequest request,
            @PathVariable String tenantId,
            @PathVariable String sessionId,
            @RequestBody EraseCommand command) {
        String actorId = requirePlatformAdministrator(request, tenantId);
        if (command == null) {
            throw new IllegalArgumentException("erase body is required");
        }
        return ResponseEntity.ok(retentionService.erase(
                tenantId,
                sessionId,
                command.reasonCode(),
                command.referenceId(),
                actorId));
    }

    @PostMapping("/tenants/{tenantId}/owner-erasure")
    public ResponseEntity<RuntimeSessionRetentionService.OwnerEraseResult> eraseOwner(
            HttpServletRequest request,
            @PathVariable String tenantId,
            @RequestBody OwnerEraseCommand command) {
        String actorId = requirePlatformAdministrator(request, tenantId);
        if (command == null) {
            throw new IllegalArgumentException("owner erase body is required");
        }
        return ResponseEntity.ok(retentionService.eraseOwner(
                tenantId,
                command.runtimeUserId(),
                command.reasonCode(),
                command.referenceId(),
                command.batchSize(),
                actorId));
    }

    @ExceptionHandler(RuntimeSessionRetentionException.class)
    public ResponseEntity<Map<String, Object>> retentionFailure(
            RuntimeSessionRetentionException failure) {
        return ResponseEntity.status(failure.status()).body(Map.of(
                "success", false,
                "code", failure.code(),
                "message", failure.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalidRequest(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "code", "RUNTIME_SESSION_RETENTION_INVALID",
                "message", failure.getMessage()));
    }

    private static String requirePlatformAdministrator(
            HttpServletRequest request, String targetTenantId) {
        Object candidate = request == null ? null
                : request.getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR);
        if (!(candidate instanceof VerifiedInternalServiceAuth verified)
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION.equals(
                        verified.identitySource())
                || verified.identityUserId() == null
                || verified.identityUserId().isBlank()) {
            throw new RuntimeSessionRetentionException(
                    "RUNTIME_SESSION_RETENTION_AUTH_REQUIRED",
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "verified platform administrator identity is required");
        }
        String signedTenantId = verified.identityTenantId() == null
                ? "" : verified.identityTenantId().trim();
        String target = targetTenantId == null ? "" : targetTenantId.trim();
        if (!signedTenantId.equals(target)) {
            throw new RuntimeSessionRetentionException(
                    "RUNTIME_SESSION_RETENTION_TENANT_MISMATCH",
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "signed tenant does not match the retention target");
        }
        return verified.identityUserId();
    }

    public record PolicyCommand(int activeRetentionDays, int clearedRetentionHours) {
    }

    public record LegalHoldCommand(boolean enabled, String reasonCode, String referenceId) {
    }

    public record EraseCommand(String reasonCode, String referenceId) {
    }

    public record OwnerEraseCommand(String runtimeUserId, String reasonCode,
                                    String referenceId, Integer batchSize) {
    }
}
