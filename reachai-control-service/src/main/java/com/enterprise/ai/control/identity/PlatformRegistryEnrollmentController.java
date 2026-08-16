package com.enterprise.ai.control.identity;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Platform-admin API that returns a 72-hour one-time SDK enrollment token once. */
@RestController
@RequiredArgsConstructor
public class PlatformRegistryEnrollmentController {

    private final PlatformAuthorizationService authorizationService;
    private final CapabilityRegistryEnrollmentGateway enrollmentGateway;
    private final PlatformAuthAuditService auditService;

    @PostMapping("/api/platform/registry-enrollments")
    public ResponseEntity<RegistryEnrollmentView> issue(HttpServletRequest request,
                                                         @RequestBody(required = false) RegistryEnrollmentCommand command) {
        PlatformAuthenticatedSession actor = requirePlatformAdmin(request);
        String projectCode = command == null ? null : command.projectCode();
        try {
            CapabilityRegistryEnrollmentGateway.IssuedEnrollment issued = enrollmentGateway.issue(actor, projectCode);
            auditService.record(actor, "REGISTRY_ENROLLMENT_ISSUED", "REGISTRY_PROJECT", issued.projectCode(),
                    Map.of("projectCode", issued.projectCode(), "ttlHours", issued.ttlHours()));
            return ResponseEntity.status(HttpStatus.CREATED).body(new RegistryEnrollmentView(
                    issued.enrollmentToken(), issued.projectCode(), issued.expiresAt(), issued.ttlHours()));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        } catch (IllegalStateException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getMessage(), unavailable);
        }
    }

    private PlatformAuthenticatedSession requirePlatformAdmin(HttpServletRequest request) {
        Object candidate = request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE);
        if (!(candidate instanceof PlatformAuthenticatedSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "live ReachAI platform login is required");
        }
        authorizationService.requireGlobalPermission(session, "platform:admin");
        return session;
    }

    public record RegistryEnrollmentCommand(String projectCode) {
    }

    public record RegistryEnrollmentView(String enrollmentToken, String projectCode, String expiresAt, int ttlHours) {
    }
}
