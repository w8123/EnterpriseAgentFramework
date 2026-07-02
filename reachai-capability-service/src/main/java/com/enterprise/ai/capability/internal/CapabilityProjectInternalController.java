package com.enterprise.ai.capability.internal;

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
public class CapabilityProjectInternalController {

    private final CapabilityProjectLookupService lookupService;

    @GetMapping("/internal/capability/projects/{projectCode}")
    public ResponseEntity<Map<String, Object>> getProject(@PathVariable("projectCode") String projectCode) {
        try {
            return ResponseEntity.ok(lookupService.getProject(projectCode));
        } catch (IllegalArgumentException ex) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("service", "reachai-capability-service");
            body.put("code", "CAPABILITY_PROJECT_NOT_FOUND");
            body.put("projectCode", projectCode);
            body.put("message", ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
    }

    @GetMapping("/internal/capability/projects/by-id/{projectId}")
    public ResponseEntity<Map<String, Object>> getProjectById(@PathVariable("projectId") Long projectId) {
        try {
            return ResponseEntity.ok(lookupService.getProjectById(projectId));
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
