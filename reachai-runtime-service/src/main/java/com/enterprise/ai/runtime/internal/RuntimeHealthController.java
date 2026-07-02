package com.enterprise.ai.runtime.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class RuntimeHealthController {

    @GetMapping("/internal/runtime/health")
    public Map<String, Object> health() {
        return Map.of(
                "service", "reachai-runtime-service",
                "status", "UP"
        );
    }
}
