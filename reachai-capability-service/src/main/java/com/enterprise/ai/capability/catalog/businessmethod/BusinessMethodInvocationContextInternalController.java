package com.enterprise.ai.capability.catalog.businessmethod;

import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.enterprise.ai.capability.internalauth.CapabilityVerifiedInternalServiceAuth;
import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;

/** Capability-owned trial-call target read; protected by the same Control catalog HMAC policy. */
@RestController
@RequestMapping("/internal/capability/business-methods")
@RequiredArgsConstructor
public class BusinessMethodInvocationContextInternalController {

    private final BusinessMethodInvocationContextService contextService;

    @GetMapping("/{name}/invocation-context")
    public ResponseEntity<ConsoleCapabilityInvocationContracts.InvocationContext> get(@PathVariable String name) {
        try {
            return ResponseEntity.ok(contextService.get(name));
        } catch (BusinessMethodInvocationContextService.BusinessMethodNotFoundException missing) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Runtime rechecks the same safe owner facts with a signed project-only identity. */
    @PostMapping("/{name}/execution-context")
    public ResponseEntity<?> executionContext(HttpServletRequest request, @PathVariable String name,
                                              @RequestBody(required = false) Map<String, Object> body) {
        Object attribute = request.getAttribute(CapabilityVerifiedInternalServiceAuth.REQUEST_ATTR);
        if (!(attribute instanceof CapabilityVerifiedInternalServiceAuth auth)
                || !InternalServiceAuthHeaders.CALLER_RUNTIME.equals(auth.caller())
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_TENANT_TRUSTED.equals(auth.identitySource())
                || auth.identityTenantId() == null || body == null
                || !body.keySet().equals(Set.of("qualifiedName", "context"))
                || !name.equals(body.get("qualifiedName"))
                || !(body.get("context") instanceof Map<?, ?> context)
                || !context.keySet().equals(Set.of("tenantId"))
                || !auth.identityTenantId().equals(context.get("tenantId"))) return ResponseEntity.status(401).build();
        try {
            var owner = contextService.get(name);
            if (!name.equals(owner.qualifiedName()) || !auth.identityTenantId().equals(owner.projectCode())) {
                return ResponseEntity.status(403).build();
            }
            return ResponseEntity.ok(owner);
        } catch (BusinessMethodInvocationContextService.BusinessMethodNotFoundException missing) {
            return ResponseEntity.notFound().build();
        }
    }
}
