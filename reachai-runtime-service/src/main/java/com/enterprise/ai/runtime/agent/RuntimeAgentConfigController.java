package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigDraftRequest;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigVersionView;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.PublishRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class RuntimeAgentConfigController {

    private final RuntimeAgentConfigService configService;

    @GetMapping("/api/agents/{agentId}/config-versions")
    public ResponseEntity<List<AgentConfigVersionView>> list(@PathVariable String agentId) {
        return ResponseEntity.ok(configService.list(agentId));
    }

    @PutMapping("/api/agents/{agentId}/config-versions/draft")
    public ResponseEntity<AgentConfigVersionView> saveDraft(
            @PathVariable String agentId,
            @RequestBody(required = false) AgentConfigDraftRequest request) {
        return ResponseEntity.ok(configService.saveDraft(agentId, request));
    }

    @PostMapping("/api/agents/{agentId}/config-versions/{configVersionId}/publish")
    public ResponseEntity<AgentConfigVersionView> publish(
            @PathVariable String agentId,
            @PathVariable Long configVersionId,
            @RequestBody(required = false) PublishRequest request) {
        return ResponseEntity.ok(configService.publish(
                agentId,
                configVersionId,
                request == null ? null : request.publishedBy()));
    }

    @PostMapping("/api/agents/{agentId}/config-versions/{configVersionId}/copy-to-draft")
    public ResponseEntity<AgentConfigVersionView> copyToDraft(
            @PathVariable String agentId,
            @PathVariable Long configVersionId) {
        return ResponseEntity.ok(configService.copyToDraft(agentId, configVersionId));
    }
}
