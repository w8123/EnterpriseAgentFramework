package com.enterprise.ai.control.compat;

import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/registry/projects")
@RequiredArgsConstructor
public class RegistryCompatibilityController {

    private final CapabilityProxyClient capabilityProxyClient;

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> registerProject(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-ReachAI-Registry-Enrollment-Token", required = false) String enrollmentToken,
            @RequestHeader(value = "X-ReachAI-App-Key", required = false) String appKey,
            @RequestHeader(value = "X-ReachAI-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-ReachAI-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-ReachAI-Signature", required = false) String signature) {
        return capabilityProxyClient.registerProject(
                body, enrollmentToken, appKey, timestamp, nonce, signature);
    }

    @GetMapping("/{projectCode}/instances")
    public ResponseEntity<Object> listInstances(@PathVariable String projectCode) {
        return capabilityProxyClient.listInstances(projectCode);
    }
}
