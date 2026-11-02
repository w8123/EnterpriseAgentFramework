package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigVersionView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class RuntimeAgentConfigController {

    private final RuntimeAgentConfigService configService;

    @ExceptionHandler(RuntimeAgentConfigService.HttpApiRiskDowngradeException.class)
    public ResponseEntity<Map<String, Object>> riskDowngrade(RuntimeAgentConfigService.HttpApiRiskDowngradeException rejected) {
        return ResponseEntity.badRequest().body(Map.of("code", "HTTP_API_WORKFLOW_RISK_DOWNGRADE", "message", rejected.getMessage()));
    }

    @GetMapping("/api/agents/{agentId}/config-versions")
    public ResponseEntity<List<AgentConfigVersionView>> list(@PathVariable String agentId) {
        return ResponseEntity.ok(configService.list(agentId));
    }

    @PutMapping("/api/agents/{agentId}/config-versions/draft")
    public ResponseEntity<Map<String, Object>> saveDraft(@PathVariable String agentId) {
        return ResponseEntity.status(409).body(com.enterprise.ai.common.agent.AgentDraftProtocol.revisionRequired());
    }

    @PostMapping("/api/agents/{agentId}/config-versions/{configVersionId}/publish")
    public ResponseEntity<Map<String, Object>> publish(
            @PathVariable String agentId,
            @PathVariable Long configVersionId) {
        return ResponseEntity.status(409).body(com.enterprise.ai.common.agent.AgentDraftProtocol.revisionRequired());
    }

    @PostMapping("/api/agents/{agentId}/config-versions/{configVersionId}/copy-to-draft")
    public ResponseEntity<Map<String, Object>> copyToDraft(
            @PathVariable String agentId,
            @PathVariable Long configVersionId) {
        return ResponseEntity.status(409).body(com.enterprise.ai.common.agent.AgentDraftProtocol.revisionRequired());
    }
}
