package com.enterprise.ai.capability.internal;

import com.enterprise.ai.capability.catalog.scan.CapabilityScanProjectCatalogService;
import com.enterprise.ai.capability.catalog.scan.CapabilitySdkSyncTriggerService;
import com.enterprise.ai.capability.catalog.scan.CapabilitySdkSyncTriggerService.SdkSyncRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class CapabilityProjectReadinessInternalController {

    private final CapabilityScanProjectCatalogService scanProjectCatalogService;
    private final CapabilitySdkSyncTriggerService sdkSyncTriggerService;

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

    @PostMapping("/internal/capability/projects/by-id/{projectId}/sdk-sync")
    public ResponseEntity<?> triggerSdkSync(
            @PathVariable("projectId") Long projectId) {
        try {
            return ResponseEntity.ok(sdkSyncTriggerService.triggerScan(projectId));
        } catch (SdkSyncRequestException ex) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("service", "reachai-capability-service");
            body.put("code", ex.code());
            body.put("projectId", projectId);
            body.put("targetUrl", ex.targetUrl());
            body.put("message", ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
        } catch (IllegalStateException ex) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("service", "reachai-capability-service");
            body.put("code", "CAPABILITY_SDK_SYNC_NOT_READY");
            body.put("projectId", projectId);
            body.put("message", ex.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
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
