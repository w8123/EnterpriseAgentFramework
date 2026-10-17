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

    private final PlatformRequestAuthorization requestAuthorization;
    private final CapabilityRegistryEnrollmentGateway enrollmentGateway;
    private final PlatformAuthAuditService auditService;

    @PostMapping("/api/platform/registry-enrollments")
    public ResponseEntity<RegistryEnrollmentView> issue(HttpServletRequest request,
                                                         @RequestBody(required = false) RegistryEnrollmentCommand command) {
        String projectCode = command == null ? null : command.projectCode();
        PlatformAuthenticatedSession actor = requireProjectWrite(request, projectCode);
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

    /** Developers enroll the SDK of the projects they are granted; a blank code needs a global grant. */
    private PlatformAuthenticatedSession requireProjectWrite(HttpServletRequest request, String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            return requestAuthorization.requireGlobalPermission(request, PlatformPermissions.PLATFORM_WRITE);
        }
        return requestAuthorization.requireResourcePermission(
                request, PlatformPermissions.PLATFORM_WRITE, "PROJECT", null, projectCode.trim());
    }

    public record RegistryEnrollmentCommand(String projectCode) {
    }

    public record RegistryEnrollmentView(String enrollmentToken, String projectCode, String expiresAt, int ttlHours) {
    }
}
