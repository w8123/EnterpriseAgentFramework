package com.enterprise.ai.capability.internal;

import com.enterprise.ai.capability.catalog.scan.CapabilityScanProjectCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class CapabilityProjectReadinessInternalController {

    private final CapabilityScanProjectCatalogService scanProjectCatalogService;

    @GetMapping("/internal/capability/projects/by-id/{projectId}/readiness-facts")
    public ResponseEntity<?> readinessFacts(@PathVariable("projectId") Long projectId) {
        try {
            return ResponseEntity.ok(scanProjectCatalogService.readinessFacts(projectId));
        } catch (IllegalArgumentException ex) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("service", "reachai-capability-service");
            body.put("code", "CAPABILITY_PROJECT_NOT_FOUND");
            body.put("projectId", projectId);
            body.put("message", ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
    }
}
