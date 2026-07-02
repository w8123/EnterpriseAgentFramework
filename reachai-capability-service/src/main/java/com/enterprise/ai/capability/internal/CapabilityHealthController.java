package com.enterprise.ai.capability.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class CapabilityHealthController {

    @GetMapping("/internal/capability/health")
    public Map<String, Object> health() {
        return Map.of(
                "service", "reachai-capability-service",
                "status", "UP"
        );
    }
}
