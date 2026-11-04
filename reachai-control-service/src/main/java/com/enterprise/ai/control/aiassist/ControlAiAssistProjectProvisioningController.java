package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.identity.PlatformPermissions;
import feign.FeignException;
import jakarta.servlet.http.HttpServletRequest;
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
    private final CapabilityProjectOnboardingClient capabilityClient;

    public ControlAiAssistProjectProvisioningController(
            ControlProjectAgentProvisioningService provisioningService,
            CapabilityProjectOnboardingClient capabilityClient) {
        this.provisioningService = provisioningService;
        this.capabilityClient = capabilityClient;
    }

    @PostMapping("/agents/provision")
    public ResponseEntity<Map<String, Object>> provision(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, ?> request,
            HttpServletRequest httpRequest) {
        try {
            ControlAiAssistProjectController.requireProjectPermission(httpRequest, projectId,
                    capabilityClient.getProjectById(projectId), PlatformPermissions.PLATFORM_WRITE);
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
