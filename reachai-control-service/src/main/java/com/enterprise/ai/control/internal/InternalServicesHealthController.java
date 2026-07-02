package com.enterprise.ai.control.internal;

import com.enterprise.ai.control.client.capability.CapabilityHealthClient;
import com.enterprise.ai.control.client.runtime.RuntimeHealthClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class InternalServicesHealthController {

    private final RuntimeHealthClient runtimeHealthClient;
    private final CapabilityHealthClient capabilityHealthClient;

    public InternalServicesHealthController(RuntimeHealthClient runtimeHealthClient,
                                            CapabilityHealthClient capabilityHealthClient) {
        this.runtimeHealthClient = runtimeHealthClient;
        this.capabilityHealthClient = capabilityHealthClient;
    }

    @GetMapping("/api/internal-services/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> runtime = runtimeHealthClient.health();
        Map<String, Object> capability = capabilityHealthClient.health();
        Map<String, Object> services = new LinkedHashMap<>();
        services.put("runtime", runtime);
        services.put("capability", capability);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", allUp(runtime, capability) ? "UP" : "DOWN");
        body.put("services", services);
        return ResponseEntity.ok(body);
    }

    private boolean allUp(Map<String, Object> runtime, Map<String, Object> capability) {
        return "UP".equals(runtime.get("status")) && "UP".equals(capability.get("status"));
    }
}
