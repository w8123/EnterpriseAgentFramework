package com.enterprise.ai.runtime.client.control;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@FeignClient(
        name = "reachai-control-agent-skill",
        url = "${services.control-service.url:http://localhost:18603}",
        configuration = RuntimeAgentSkillInternalAuthFeignConfig.class)
public interface RuntimeAgentSkillCatalogClient {

    @GetMapping("/internal/control/agent-skills/{skillId}/versions/{versionId}/package")
    ResponseEntity<byte[]> getPackage(@PathVariable("skillId") Long skillId,
                                      @PathVariable("versionId") Long versionId);

    @PostMapping("/internal/control/agent-skills/resolve-execution")
    List<ExecutionResolution> resolveExecution(@RequestBody List<ExecutionReference> references);

    record ExecutionReference(Long skillId, Long skillVersionId, String sourceSha256) {
    }

    record ExecutionResolution(Long skillId,
                               Long skillVersionId,
                               String status,
                               String sourceSha256,
                               boolean executable,
                               String reason) {
    }
}
