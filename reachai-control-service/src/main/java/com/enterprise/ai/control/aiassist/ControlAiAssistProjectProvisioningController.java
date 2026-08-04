package com.enterprise.ai.control.aiassist;

import feign.FeignException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Console-authenticated provisioning entry. The external project-key entry
 * delegates to the same provisioning service.
 */
@RestController
@RequestMapping("/api/ai-assist/projects/{projectId}")
public class ControlAiAssistProjectProvisioningController {

    private final ControlProjectAgentProvisioningService provisioningService;

    public ControlAiAssistProjectProvisioningController(
            ControlProjectAgentProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
    }

    @PostMapping("/agents/provision")
    public ResponseEntity<Map<String, Object>> provision(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, ?> request) {
        try {
            return ResponseEntity.ok(
                    provisioningService.provision(projectId, request));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(409).body(Map.of(
                    "schema", "agent-provisioning.v2",
                    "message", ex.getMessage(),
                    "code", "SUPERVISOR_PROVISIONING_NOT_READY"));
        }
    }
}
