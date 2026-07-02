package com.enterprise.ai.control.client.capability;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;

@FeignClient(name = "reachai-capability-health", url = "${services.capability-service.url:http://localhost:18605}")
public interface CapabilityHealthClient {

    @GetMapping("/internal/capability/health")
    Map<String, Object> health();
}
