package com.enterprise.ai.runtime.runops.consolecapability;

import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
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

/** The Console invocation boundary is internal-only; the browser never reaches Runtime directly. */
@RestController
@RequestMapping("/internal/runtime/console-capability-invocations")
@RequiredArgsConstructor
public class ConsoleCapabilityInvocationInternalController {

    private final ConsoleCapabilityInvocationService service;

    @PostMapping
    public ResponseEntity<?> invoke(HttpServletRequest request,
                                    @RequestBody ConsoleCapabilityInvocationContracts.InvocationCommand command) {
        VerifiedInternalServiceAuth verified = verified(request);
        if (verified == null || command == null || !verified.identityUserId().equals(command.platformActorId())) {
            return unauthorized();
        }
        try {
            return ResponseEntity.ok(service.invoke(command));
        } catch (ConsoleCapabilityInvocationService.SideEffectConfirmationRequiredException required) {
            return ResponseEntity.badRequest().body(error("CONSOLE_CAPABILITY_CONFIRMATION_REQUIRED"));
        } catch (ConsoleCapabilityInvocationService.InvocationConflictException conflict) {
            return ResponseEntity.status(409).body(error("CONSOLE_CAPABILITY_INVOCATION_CONFLICT"));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(error("CONSOLE_CAPABILITY_COMMAND_INVALID"));
        }
    }

    @GetMapping("/{invocationId}")
    public ResponseEntity<?> get(HttpServletRequest request, @PathVariable String invocationId) {
        VerifiedInternalServiceAuth verified = verified(request);
        if (verified == null) return unauthorized();
        try {
            ConsoleCapabilityInvocationContracts.InvocationOutcome outcome = service.get(invocationId,
                    verified.identityUserId());
            return outcome == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(outcome);
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.notFound().build();
        }
    }

    private VerifiedInternalServiceAuth verified(HttpServletRequest request) {
        Object raw = request == null ? null : request.getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR);
        if (!(raw instanceof VerifiedInternalServiceAuth auth)
                || !InternalServiceAuthHeaders.CALLER_CONTROL.equals(auth.caller())
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION.equals(auth.identitySource())
                || !StringUtils.hasText(auth.identityUserId())) {
            return null;
        }
        return auth;
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(error("RUNTIME_INTERNAL_AUTH_REQUIRED"));
    }

    private Map<String, Object> error(String code) {
        return Map.of("success", false, "code", code);
    }
}
