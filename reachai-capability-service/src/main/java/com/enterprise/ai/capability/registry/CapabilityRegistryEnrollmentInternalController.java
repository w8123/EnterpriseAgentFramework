package com.enterprise.ai.capability.registry;

import com.enterprise.ai.agent.registry.RegistryEnrollmentService;
import com.enterprise.ai.capability.internalauth.CapabilityVerifiedInternalServiceAuth;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;

/** Trusted Control-only issuance endpoint; raw enrollment tokens are returned once. */
@RestController
@RequiredArgsConstructor
public class CapabilityRegistryEnrollmentInternalController {

    private final RegistryEnrollmentService enrollmentService;

    @PostMapping("/internal/capability/registry/enrollments")
    public ResponseEntity<?> create(HttpServletRequest request,
                                    @RequestBody(required = false) RegistryEnrollmentIssueRequest body) {
        Object candidate = request.getAttribute(CapabilityVerifiedInternalServiceAuth.REQUEST_ATTR);
        if (!(candidate instanceof CapabilityVerifiedInternalServiceAuth verified)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            RegistryEnrollmentService.IssuedEnrollment issued = enrollmentService.create(
                    body == null ? null : body.projectCode(), Long.valueOf(verified.identityUserId()));
            return ResponseEntity.status(HttpStatus.CREATED).body(new RegistryEnrollmentIssueResponse(
                    issued.enrollmentToken(), issued.projectCode(),
                    issued.expiresAt().atZone(ZoneId.systemDefault()).toInstant().toString(),
                    RegistryEnrollmentService.MAX_TTL_HOURS));
        } catch (IllegalArgumentException badRequest) {
            return ResponseEntity.badRequest().body(new ErrorResponse(badRequest.getMessage()));
        }
    }

    public record RegistryEnrollmentIssueRequest(String projectCode) {
    }

    public record RegistryEnrollmentIssueResponse(String enrollmentToken, String projectCode,
                                                  String expiresAt, int ttlHours) {
    }

    record ErrorResponse(String message) {
    }
}
