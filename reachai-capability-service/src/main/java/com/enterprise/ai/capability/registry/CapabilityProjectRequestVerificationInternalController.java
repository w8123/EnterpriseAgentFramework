package com.enterprise.ai.capability.registry;

import com.enterprise.ai.agent.registry.RegistryProjectRequestVerificationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Internal credential-owner endpoint. Never returns appSecret or the supplied signature. */
@RestController
public class CapabilityProjectRequestVerificationInternalController {

    private final RegistryProjectRequestVerificationService verificationService;

    public CapabilityProjectRequestVerificationInternalController(
            RegistryProjectRequestVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @PostMapping("/internal/capability/registry/project-requests/verify")
    public ResponseEntity<VerificationResponse> verify(
            @RequestBody RegistryProjectRequestVerificationService.ProjectRequest request) {
        try {
            RegistryProjectRequestVerificationService.VerifiedProjectRequest verified =
                    verificationService.verify(request);
            return ResponseEntity.ok(new VerificationResponse(
                    true,
                    verified.projectId(),
                    verified.projectCode(),
                    verified.credentialId(),
                    verified.appKeyHash()));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    public record VerificationResponse(
            boolean verified,
            Long projectId,
            String projectCode,
            Long credentialId,
            String appKeyHash
    ) {
    }
}
