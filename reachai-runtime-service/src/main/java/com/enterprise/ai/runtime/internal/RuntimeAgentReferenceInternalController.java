package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.runtime.agent.RuntimeAgentToolReferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class RuntimeAgentReferenceInternalController {

    private final RuntimeAgentToolReferenceService service;

    @GetMapping("/internal/runtime/agent-tool-references")
    public ResponseEntity<List<AgentToolReferenceView>> listAgentToolReferences() {
        return ResponseEntity.ok(service.listAgentToolReferences().stream()
                .map(ref -> new AgentToolReferenceView(
                        ref.agentId(),
                        ref.agentName(),
                        ref.tools(),
                        ref.skills()))
                .toList());
    }

    public record AgentToolReferenceView(String agentId, String agentName, List<String> tools, List<String> skills) {
    }
}
