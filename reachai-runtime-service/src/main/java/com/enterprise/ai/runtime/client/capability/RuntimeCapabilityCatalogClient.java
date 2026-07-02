package com.enterprise.ai.runtime.client.capability;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

@FeignClient(name = "reachai-capability-service", url = "${services.capability-service.url:http://localhost:18605}")
public interface RuntimeCapabilityCatalogClient {

    @GetMapping("/internal/capability/tools/{qualifiedName}")
    Map<String, Object> getToolDefinition(@PathVariable("qualifiedName") String qualifiedName);

    @PostMapping("/internal/capability/tools/{qualifiedName}/execute")
    Map<String, Object> executeTool(@PathVariable("qualifiedName") String qualifiedName,
                                    @RequestBody Map<String, Object> request);

    @GetMapping("/internal/capability/compositions/{qualifiedName}")
    Map<String, Object> getCompositionDefinition(@PathVariable("qualifiedName") String qualifiedName);

    @GetMapping("/internal/capability/projects/{projectCode}")
    Map<String, Object> getProject(@PathVariable("projectCode") String projectCode);

    @GetMapping("/internal/capability/projects/by-id/{projectId}")
    Map<String, Object> getProjectById(@PathVariable("projectId") Long projectId);

    @GetMapping("/internal/capability/runtime-instances")
    List<Map<String, Object>> listRuntimeInstances();
}
