package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.client.runtime.RuntimeSessionRetentionGateway;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/** Platform-session + permission protected BFF for Runtime session retention governance. */
@RestController
@RequestMapping("/api/runtime/session-retention")
public class RuntimeSessionRetentionController {

    public static final String MANAGE_PERMISSION = "runtime:session:retention:manage";

    private final RuntimeSessionRetentionGateway gateway;
    private final PlatformAuthorizationService authorizationService;
    private final PlatformAuthAuditService auditService;

    public RuntimeSessionRetentionController(
            RuntimeSessionRetentionGateway gateway,
            PlatformAuthorizationService authorizationService,
            PlatformAuthAuditService auditService) {
        this.gateway = gateway;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    @GetMapping("/tenants/{tenantId}/policy")
    public ResponseEntity<Map<String, Object>> policy(
            HttpServletRequest request,
            @PathVariable String tenantId) {
        PlatformAuthenticatedSession actor = requireAdministrator(request);
        String targetTenantId = normalizeTenantId(tenantId);
        auditService.record(actor, "RUNTIME_SESSION_RETENTION_POLICY_READ",
                "RUNTIME_SESSION_RETENTION_POLICY", hash(targetTenantId),
                Map.of("tenantId", targetTenantId));
        return gateway.policy(targetTenantId, actorId(actor));
    }

    @PutMapping("/tenants/{tenantId}/policy")
    public ResponseEntity<Map<String, Object>> savePolicy(
            HttpServletRequest request,
            @PathVariable String tenantId,
            @RequestBody PolicyCommand command) {
        PlatformAuthenticatedSession actor = requireAdministrator(request);
        if (command == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "policy body is required");
        }
        String targetTenantId = normalizeTenantId(tenantId);
        auditService.record(actor, "RUNTIME_SESSION_RETENTION_POLICY_UPDATE_REQUESTED",
                "RUNTIME_SESSION_RETENTION_POLICY", hash(targetTenantId), Map.of(
                        "tenantId", targetTenantId,
                        "activeRetentionDays", command.activeRetentionDays(),
                        "clearedRetentionHours", command.clearedRetentionHours()));
        return gateway.savePolicy(
                targetTenantId,
                command.activeRetentionDays(),
                command.clearedRetentionHours(),
                actorId(actor));
    }

    @DeleteMapping("/tenants/{tenantId}/policy")
    public ResponseEntity<Map<String, Object>> deletePolicy(
            HttpServletRequest request,
            @PathVariable String tenantId) {
        PlatformAuthenticatedSession actor = requireAdministrator(request);
        String targetTenantId = normalizeTenantId(tenantId);
        auditService.record(actor, "RUNTIME_SESSION_RETENTION_POLICY_DELETE_REQUESTED",
                "RUNTIME_SESSION_RETENTION_POLICY", hash(targetTenantId),
                Map.of("tenantId", targetTenantId));
        return gateway.deletePolicy(targetTenantId, actorId(actor));
    }

    @GetMapping("/tenants/{tenantId}/sessions/{sessionId}")
    public ResponseEntity<Map<String, Object>> session(
            HttpServletRequest request,
            @PathVariable String tenantId,
            @PathVariable String sessionId) {
        PlatformAuthenticatedSession actor = requireAdministrator(request);
        String targetTenantId = normalizeTenantId(tenantId);
        String targetSessionId = normalizeSessionId(sessionId);
        auditService.record(actor, "RUNTIME_SESSION_RETENTION_SESSION_READ",
                "RUNTIME_SESSION", sessionHash(targetTenantId, targetSessionId),
                Map.of("tenantId", targetTenantId));
        return gateway.session(targetTenantId, targetSessionId, actorId(actor));
    }

    @PutMapping("/tenants/{tenantId}/sessions/{sessionId}/legal-hold")
    public ResponseEntity<Map<String, Object>> legalHold(
            HttpServletRequest request,
            @PathVariable String tenantId,
            @PathVariable String sessionId,
            @RequestBody LegalHoldCommand command) {
        PlatformAuthenticatedSession actor = requireAdministrator(request);
        if (command == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "legal hold body is required");
        }
        String targetTenantId = normalizeTenantId(tenantId);
        String targetSessionId = normalizeSessionId(sessionId);
        String reasonCode = normalizeReasonCode(command.reasonCode());
        String referenceId = normalizeReferenceId(command.referenceId());
        auditService.record(actor,
                command.enabled()
                        ? "RUNTIME_SESSION_LEGAL_HOLD_SET_REQUESTED"
                        : "RUNTIME_SESSION_LEGAL_HOLD_RELEASE_REQUESTED",
                "RUNTIME_SESSION",
                sessionHash(targetTenantId, targetSessionId),
                safeDetails(targetTenantId, reasonCode, referenceId));
        return gateway.setLegalHold(
                targetTenantId,
                targetSessionId,
                command.enabled(),
                reasonCode,
                referenceId,
                actorId(actor));
    }

    @PostMapping("/tenants/{tenantId}/sessions/{sessionId}/erase")
    public ResponseEntity<Map<String, Object>> erase(
            HttpServletRequest request,
            @PathVariable String tenantId,
            @PathVariable String sessionId,
            @RequestBody EraseCommand command) {
        PlatformAuthenticatedSession actor = requireAdministrator(request);
        if (command == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "erase body is required");
        }
        String targetTenantId = normalizeTenantId(tenantId);
        String targetSessionId = normalizeSessionId(sessionId);
        String reasonCode = normalizeReasonCode(command.reasonCode());
        String referenceId = normalizeReferenceId(command.referenceId());
        auditService.record(actor, "RUNTIME_SESSION_ERASE_REQUESTED", "RUNTIME_SESSION",
                sessionHash(targetTenantId, targetSessionId),
                safeDetails(targetTenantId, reasonCode, referenceId));
        return gateway.erase(
                targetTenantId,
                targetSessionId,
                reasonCode,
                referenceId,
                actorId(actor));
    }

    @PostMapping("/tenants/{tenantId}/owner-erasure")
    public ResponseEntity<Map<String, Object>> eraseOwner(
            HttpServletRequest request,
            @PathVariable String tenantId,
            @RequestBody OwnerEraseCommand command) {
        PlatformAuthenticatedSession actor = requireAdministrator(request);
        if (command == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "owner erase body is required");
        }
        String targetTenantId = normalizeTenantId(tenantId);
        String runtimeUserId = normalizeRuntimeUserId(command.runtimeUserId());
        String reasonCode = normalizeReasonCode(command.reasonCode());
        String referenceId = normalizeReferenceId(command.referenceId());
        Integer batchSize = normalizeBatchSize(command.batchSize());
        auditService.record(actor, "RUNTIME_OWNER_SESSION_ERASE_REQUESTED", "RUNTIME_USER",
                hash(targetTenantId + "\n" + runtimeUserId),
                safeOwnerEraseDetails(targetTenantId, reasonCode, referenceId, batchSize));
        return gateway.eraseOwner(
                targetTenantId,
                runtimeUserId,
                reasonCode,
                referenceId,
                batchSize,
                actorId(actor));
    }

    private PlatformAuthenticatedSession requireAdministrator(HttpServletRequest request) {
        Object candidate = request == null ? null
                : request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE);
        if (!(candidate instanceof PlatformAuthenticatedSession session)) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "live ReachAI platform login is required");
        }
        authorizationService.requireGlobalPermission(session, MANAGE_PERMISSION);
        return session;
    }

    private static String actorId(PlatformAuthenticatedSession actor) {
        return String.valueOf(actor.user().getId());
    }

    private static Map<String, ?> safeDetails(
            String tenantId, String reasonCode, String referenceId) {
        java.util.LinkedHashMap<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("tenantId", tenantId);
        details.put("reasonCode", reasonCode);
        if (referenceId != null) {
            details.put("referenceId", referenceId);
        }
        return details;
    }

    private static Map<String, ?> safeOwnerEraseDetails(
            String tenantId, String reasonCode, String referenceId, Integer batchSize) {
        java.util.LinkedHashMap<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("tenantId", tenantId);
        details.put("reasonCode", reasonCode);
        if (referenceId != null) {
            details.put("referenceId", referenceId);
        }
        if (batchSize != null) {
            details.put("batchSize", batchSize);
        }
        return details;
    }

    private static String normalizeTenantId(String value) {
        String normalized = required(value, "tenantId", 96);
        if (!normalized.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("tenantId contains unsupported characters");
        }
        return normalized;
    }

    private static String normalizeReasonCode(String value) {
        String normalized = required(value, "reasonCode", 64).toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9_.:-]*")) {
            throw new IllegalArgumentException(
                    "reasonCode must be an uppercase machine-readable code");
        }
        return normalized;
    }

    private static String normalizeSessionId(String value) {
        String normalized = required(value, "sessionId", 128);
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("sessionId must not contain control characters");
        }
        return normalized;
    }

    private static String normalizeRuntimeUserId(String value) {
        String normalized = required(value, "runtimeUserId", 128);
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("runtimeUserId must not contain control characters");
        }
        return normalized;
    }

    private static Integer normalizeBatchSize(Integer value) {
        if (value == null) {
            return null;
        }
        if (value < 1 || value > 500) {
            throw new IllegalArgumentException("batchSize must be between 1 and 500");
        }
        return value;
    }

    private static String normalizeReferenceId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 128
                || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
            throw new IllegalArgumentException(
                    "referenceId must be a machine-readable external reference up to 128 characters");
        }
        return normalized;
    }

    private static String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must be at most " + maxLength + " characters");
        }
        return normalized;
    }

    private static String sessionHash(String tenantId, String sessionId) {
        return hash((tenantId == null ? "" : tenantId.trim()) + "\n"
                + (sessionId == null ? "" : sessionId.trim()));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
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
