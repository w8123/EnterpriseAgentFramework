package com.enterprise.ai.capability.catalog.scan;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@FeignClient(name = "reachai-runtime-agent-references", url = "${services.runtime-service.url:http://localhost:18604}")
public interface CapabilityRuntimeAgentReferenceClient {

    @GetMapping("/internal/runtime/agent-tool-references")
    List<AgentToolReferenceView> listAgentToolReferences();

    record AgentToolReferenceView(String agentId, String agentName, List<String> tools, List<String> skills) {
    }
}
