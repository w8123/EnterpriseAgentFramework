package com.enterprise.ai.control.client.capability;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.util.List;
import java.util.Map;

@FeignClient(name = "reachai-capability-project-onboarding", url = "${services.capability-service.url:http://localhost:18605}")
public interface CapabilityProjectOnboardingClient {

    @GetMapping("/internal/capability/projects/{projectCode}")
    Map<String, Object> getProjectByCode(@PathVariable("projectCode") String projectCode);

    @GetMapping("/internal/capability/projects/by-id/{projectId}")
    Map<String, Object> getProjectById(@PathVariable("projectId") Long projectId);

    @GetMapping("/internal/capability/projects/by-id/{projectId}/onboarding")
    Map<String, Object> getOnboardingProjectById(@PathVariable("projectId") Long projectId);

    @RequestMapping(method = RequestMethod.PUT,
            path = "/internal/capability/projects/by-id/{projectId}/ai-coding-access")
    Map<String, Object> updateAiCodingAccess(
            @PathVariable("projectId") Long projectId,
            @RequestBody CapabilityAiCodingAccessUpdateRequest request);

    @GetMapping("/internal/capability/projects/by-id/{projectId}/readiness-facts")
    Map<String, Object> getReadinessFacts(@PathVariable("projectId") Long projectId);

    @RequestMapping(method = RequestMethod.POST,
            path = "/internal/capability/projects/by-id/{projectId}/sdk-sync")
    Map<String, Object> triggerSdkSync(@PathVariable("projectId") Long projectId);

    @GetMapping("/internal/capability/projects/by-id/{projectId}/tools")
    List<Map<String, Object>> listProjectTools(@PathVariable("projectId") Long projectId);
}
