package com.enterprise.ai.control.compat;

import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/registry/projects")
@RequiredArgsConstructor
public class RegistryCompatibilityController {

    private final CapabilityProxyClient capabilityProxyClient;

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> registerProject(@RequestBody Map<String, Object> body) {
        return capabilityProxyClient.registerProject(body);
    }

    @GetMapping("/{projectCode}/instances")
    public ResponseEntity<List<Map<String, Object>>> listInstances(@PathVariable String projectCode) {
        return capabilityProxyClient.listInstances(projectCode);
    }
}
