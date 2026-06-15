package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.workflow.AgentWorkflowBindingEntity;
import com.enterprise.ai.agent.workflow.AgentWorkflowBindingService;
import com.enterprise.ai.agent.workflow.AgentWorkflowResolveRequest;
import com.enterprise.ai.agent.workflow.AgentWorkflowResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agents/{agentId}/workflow-bindings")
@RequiredArgsConstructor
public class AgentWorkflowBindingController {

    private final AgentWorkflowBindingService bindingService;
    private final AgentWorkflowResolver resolver;

    @GetMapping
    public ResponseEntity<List<AgentWorkflowBindingEntity>> list(@PathVariable String agentId) {
        return ResponseEntity.ok(bindingService.list(agentId));
    }

    @PostMapping
    public ResponseEntity<AgentWorkflowBindingEntity> create(@PathVariable String agentId,
                                                             @RequestBody AgentWorkflowBindingEntity request) {
        return ResponseEntity.ok(bindingService.create(agentId, request));
    }

    @PutMapping("/{bindingId}")
    public ResponseEntity<AgentWorkflowBindingEntity> update(@PathVariable String agentId,
                                                             @PathVariable Long bindingId,
                                                             @RequestBody AgentWorkflowBindingEntity request) {
        AgentWorkflowBindingEntity current = bindingService.findById(bindingId);
        if (current == null || !agentId.equals(current.getAgentId())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(bindingService.update(bindingId, request));
    }

    @DeleteMapping("/{bindingId}")
    public ResponseEntity<Void> delete(@PathVariable String agentId,
                                       @PathVariable Long bindingId) {
        AgentWorkflowBindingEntity current = bindingService.findById(bindingId);
        if (current == null || !agentId.equals(current.getAgentId())) {
            return ResponseEntity.notFound().build();
        }
        return bindingService.delete(bindingId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/resolve-preview")
    public ResponseEntity<AgentWorkflowBindingEntity> resolvePreview(@PathVariable String agentId,
                                                                     @RequestBody AgentWorkflowResolveRequest request) {
        AgentWorkflowResolveRequest effective = new AgentWorkflowResolveRequest(
                agentId,
                request == null ? null : request.projectCode(),
                request == null ? null : request.pageKey(),
                request == null ? null : request.route(),
                request == null ? null : request.actionKey(),
                request == null ? null : request.intentType());
        return resolver.resolve(effective)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
