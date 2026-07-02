package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class PlatformEmbedCredentialPolicyController {

    private final CapabilityProxyClient capabilityProxyClient;

    @GetMapping("/api/platform/embed/credentials")
    public ResponseEntity<Object> listPolicies(@RequestParam(required = false) String projectCode,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "200") int limit) {
        return capabilityProxyClient.listEmbedCredentialPolicies(projectCode, status, limit);
    }

    @PutMapping("/api/platform/embed/credentials/{id}/policy")
    public ResponseEntity<Object> updatePolicy(@PathVariable Long id,
                                               @RequestBody(required = false) Map<String, Object> request) {
        return capabilityProxyClient.updateEmbedCredentialPolicy(id, request == null ? Map.of() : request);
    }
}
