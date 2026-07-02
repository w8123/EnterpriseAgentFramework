package com.enterprise.ai.capability.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class CapabilityProjectOnboardingInternalController {

    private final CapabilityProjectOnboardingInternalService onboardingService;

    @GetMapping("/internal/capability/projects/by-id/{projectId}/onboarding")
    public ResponseEntity<Map<String, Object>> getOnboardingProjectById(@PathVariable Long projectId) {
        try {
            return ResponseEntity.ok(onboardingService.getOnboardingProjectById(projectId));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(notFound(projectId, ex));
        }
    }

    @PatchMapping("/internal/capability/projects/by-id/{projectId}/ai-coding-access")
    public ResponseEntity<Map<String, Object>> updateAiCodingAccess(
            @PathVariable Long projectId,
            @RequestBody(required = false) AiCodingAccessUpdateRequest request) {
        try {
            return ResponseEntity.ok(onboardingService.updateAiCodingAccess(
                    projectId,
                    request == null ? Boolean.FALSE : request.enabled(),
                    request == null ? null : request.accessKey()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(notFound(projectId, ex));
        }
    }

    private Map<String, Object> notFound(Long projectId, IllegalArgumentException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "reachai-capability-service");
        body.put("code", "CAPABILITY_PROJECT_NOT_FOUND");
        body.put("projectId", projectId);
        body.put("message", ex.getMessage());
        return body;
    }

    public record AiCodingAccessUpdateRequest(Boolean enabled, String accessKey) {
    }
}
