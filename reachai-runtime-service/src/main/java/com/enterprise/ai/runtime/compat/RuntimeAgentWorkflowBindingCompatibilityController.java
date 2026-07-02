package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.workflow.RuntimeAgentWorkflowBindingResolveRequest;
import com.enterprise.ai.runtime.workflow.RuntimeAgentWorkflowBindingService;
import com.enterprise.ai.runtime.workflow.RuntimeAgentWorkflowBindingView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class RuntimeAgentWorkflowBindingCompatibilityController {

    private final RuntimeAgentWorkflowBindingService bindingService;

    @GetMapping("/api/agents/{agentId}/workflow-bindings")
    public ResponseEntity<List<RuntimeAgentWorkflowBindingView>> list(@PathVariable String agentId) {
        return ResponseEntity.ok(bindingService.list(agentId));
    }

    @PostMapping("/api/agents/{agentId}/workflow-bindings")
    public ResponseEntity<RuntimeAgentWorkflowBindingView> create(
            @PathVariable String agentId,
            @RequestBody RuntimeAgentWorkflowBindingView request) {
        return ResponseEntity.ok(bindingService.create(agentId, request));
    }

    @PutMapping("/api/agents/{agentId}/workflow-bindings/{bindingId}")
    public ResponseEntity<RuntimeAgentWorkflowBindingView> update(
            @PathVariable String agentId,
            @PathVariable Long bindingId,
            @RequestBody RuntimeAgentWorkflowBindingView request) {
        return bindingService.update(agentId, bindingId, request)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/agents/{agentId}/workflow-bindings/{bindingId}")
    public ResponseEntity<Void> delete(@PathVariable String agentId, @PathVariable Long bindingId) {
        return bindingService.delete(agentId, bindingId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/api/agents/{agentId}/workflow-bindings/resolve-preview")
    public ResponseEntity<RuntimeAgentWorkflowBindingView> resolvePreview(
            @PathVariable String agentId,
            @RequestBody(required = false) RuntimeAgentWorkflowBindingResolveRequest request) {
        RuntimeAgentWorkflowBindingResolveRequest effective = new RuntimeAgentWorkflowBindingResolveRequest(
                agentId,
                request == null ? null : request.projectCode(),
                request == null ? null : request.pageKey(),
                request == null ? null : request.route(),
                request == null ? null : request.actionKey(),
                request == null ? null : request.intentType());
        return bindingService.resolvePreview(effective)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
