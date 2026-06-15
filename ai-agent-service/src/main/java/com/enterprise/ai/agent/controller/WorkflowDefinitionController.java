package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionEntity;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionService;
import com.enterprise.ai.agent.workflow.WorkflowStudioService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
public class WorkflowDefinitionController {

    private final WorkflowDefinitionService service;
    private final WorkflowStudioService studioService;

    @GetMapping
    public ResponseEntity<List<WorkflowDefinitionEntity>> list(@RequestParam(required = false) Long projectId,
                                                               @RequestParam(required = false) String projectCode,
                                                               @RequestParam(required = false) String workflowType,
                                                               @RequestParam(required = false) String status) {
        return ResponseEntity.ok(service.list(projectId, projectCode, workflowType, status));
    }

    @PostMapping
    public ResponseEntity<WorkflowDefinitionEntity> create(@RequestBody WorkflowDefinitionEntity request) {
        return ResponseEntity.ok(service.create(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorkflowDefinitionEntity> get(@PathVariable String id) {
        return service.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/studio")
    public ResponseEntity<WorkflowStudioService.WorkflowStudioState> studio(@PathVariable String id) {
        return ResponseEntity.ok(studioService.getStudioState(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<WorkflowDefinitionEntity> update(@PathVariable String id,
                                                           @RequestBody WorkflowDefinitionEntity request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @PutMapping("/{id}/studio")
    public ResponseEntity<WorkflowDefinitionEntity> saveStudio(@PathVariable String id,
                                                               @RequestBody WorkflowStudioService.WorkflowStudioSaveRequest request) {
        return ResponseEntity.ok(studioService.saveStudioDraft(id, request));
    }

    @GetMapping("/graph-node-types")
    public ResponseEntity<List<AgentGraphNodeType.Descriptor>> graphNodeTypes() {
        return ResponseEntity.ok(AgentGraphNodeType.catalog());
    }

    @PostMapping("/runtime-validation")
    public ResponseEntity<WorkflowStudioService.WorkflowRuntimeValidationResult> validateRuntime(
            @RequestBody WorkflowStudioService.WorkflowRuntimeValidationRequest request) {
        return ResponseEntity.ok(studioService.validateRuntime(request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        return service.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
