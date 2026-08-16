package com.enterprise.ai.control.context;

import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Least-privilege administration API for durable cross-domain memory erasure. */
@RestController
@RequestMapping("/api/context/memory-erasure-requests")
public class MemoryErasureController {

    public static final String MANAGE_PERMISSION = "context:memory:erasure:manage";

    private final MemoryErasureOrchestrationService service;
    private final PlatformAuthorizationService authorizationService;
    private final PlatformAuthAuditService auditService;

    public MemoryErasureController(MemoryErasureOrchestrationService service,
                                   PlatformAuthorizationService authorizationService,
                                   PlatformAuthAuditService auditService) {
        this.service = service;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    @PostMapping
    public ResponseEntity<MemoryErasureOrchestrationService.RequestView> create(
            HttpServletRequest request,
            @RequestBody MemoryErasureOrchestrationService.CreateCommand command) {
        PlatformAuthenticatedSession actor = requireAdministrator(request);
        var result = service.create(command, actorId(actor));
        auditService.record(actor,
                result.created()
                        ? "MEMORY_ERASURE_REQUEST_CREATED"
                        : "MEMORY_ERASURE_REQUEST_REPLAYED",
                "MEMORY_ERASURE_REQUEST",
                result.requestId(),
                Map.of(
                        "tenantId", result.tenantId(),
                        "runtimeUserHash", result.runtimeUserHash(),
                        "reasonCode", result.reasonCode(),
                        "referenceId", result.referenceId()));
        return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(result);
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<MemoryErasureOrchestrationService.RequestView> get(
            HttpServletRequest request,
            @PathVariable String requestId) {
        PlatformAuthenticatedSession actor = requireAdministrator(request);
        var result = service.get(requestId);
        auditService.record(actor, "MEMORY_ERASURE_REQUEST_READ",
                "MEMORY_ERASURE_REQUEST", result.requestId(),
                Map.of("tenantId", result.tenantId(), "status", result.status()));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{requestId}/retry")
    public ResponseEntity<MemoryErasureOrchestrationService.RequestView> retry(
            HttpServletRequest request,
            @PathVariable String requestId) {
        PlatformAuthenticatedSession actor = requireAdministrator(request);
        var result = service.retry(requestId);
        auditService.record(actor, "MEMORY_ERASURE_RETRY_REQUESTED",
                "MEMORY_ERASURE_REQUEST", result.requestId(),
                Map.of("tenantId", result.tenantId()));
        return ResponseEntity.accepted().body(result);
    }

    @PostMapping("/{requestId}/domains/{domainCode}/evidence")
    public ResponseEntity<MemoryErasureOrchestrationService.RequestView> attest(
            HttpServletRequest request,
            @PathVariable String requestId,
            @PathVariable String domainCode,
            @RequestBody MemoryErasureOrchestrationService.EvidenceCommand command) {
        PlatformAuthenticatedSession actor = requireAdministrator(request);
        var result = service.attest(requestId, domainCode, command);
        var domain = result.domains().stream()
                .filter(value -> value.domainCode().equalsIgnoreCase(domainCode))
                .findFirst()
                .orElseThrow();
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("domainCode", domain.domainCode());
        details.put("resultCode", domain.resultCode());
        details.put("evidenceReference", domain.evidenceReference());
        auditService.record(actor, "MEMORY_ERASURE_EVIDENCE_SUBMITTED",
                "MEMORY_ERASURE_REQUEST", result.requestId(), details);
        return ResponseEntity.ok(result);
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
}
