package com.enterprise.ai.control.client.capability;

import com.enterprise.ai.control.aiassist.ControlAiAssistProjectController;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "reachai-capability-project-onboarding", url = "${services.capability-service.url:http://localhost:18605}")
public interface CapabilityProjectOnboardingClient {

    @GetMapping("/internal/capability/projects/by-id/{projectId}/onboarding")
    Map<String, Object> getOnboardingProjectById(@PathVariable("projectId") Long projectId);

    @PatchMapping("/internal/capability/projects/by-id/{projectId}/ai-coding-access")
    Map<String, Object> updateAiCodingAccess(
            @PathVariable("projectId") Long projectId,
            @RequestBody ControlAiAssistProjectController.AiCodingAccessUpdateRequest request);
}
