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
public class CapabilityCompositionInternalController {

    private final CapabilityCompositionLookupService lookupService;

    @GetMapping("/internal/capability/compositions/{qualifiedName}")
    public ResponseEntity<Map<String, Object>> getCompositionDefinition(
            @PathVariable("qualifiedName") String qualifiedName) {
        try {
            return ResponseEntity.ok(lookupService.getCompositionDefinition(qualifiedName));
        } catch (IllegalArgumentException ex) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("service", "reachai-capability-service");
            body.put("code", "CAPABILITY_COMPOSITION_NOT_FOUND");
            body.put("qualifiedName", qualifiedName);
            body.put("message", ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
    }
}
