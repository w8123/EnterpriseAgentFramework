package com.enterprise.ai.capability.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class CapabilityRuntimeInstanceInternalController {

    private final CapabilityRuntimeInstanceLookupService lookupService;

    @GetMapping("/internal/capability/runtime-instances")
    public ResponseEntity<List<Map<String, Object>>> listRuntimeInstances() {
        return ResponseEntity.ok(lookupService.listRuntimeInstances());
    }
}
