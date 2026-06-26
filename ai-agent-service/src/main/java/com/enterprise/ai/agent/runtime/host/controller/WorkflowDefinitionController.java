package com.enterprise.ai.agent.runtime.host.controller;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectService;
import com.enterprise.ai.agent.workflow.PageAssistantWorkflowBindRequest;
import com.enterprise.ai.agent.workflow.PageAssistantWorkflowBindingResult;
import com.enterprise.ai.agent.workflow.PageAssistantWorkflowBindingService;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionEntity;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionService;
import com.enterprise.ai.agent.workflow.WorkflowStudioService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
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
import java.util.Map;

@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
public class WorkflowDefinitionController {

    private final WorkflowDefinitionService service;
    private final WorkflowStudioService studioService;
    private final PageAssistantWorkflowBindingService pageAssistantWorkflowBindingService;
    private final ScanProjectService scanProjectService;

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
    public ResponseEntity<?> delete(@PathVariable String id) {
        try {
            service.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException ex) {
            String message = ex.getMessage();
            if (message != null && message.startsWith("workflow not found")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().body(Map.of("message", message != null ? message : "delete rejected"));
        }
    }

    @PostMapping("/{id}/page-assistant/bind")
    public ResponseEntity<?> bindPageAssistantWorkflow(@PathVariable String id,
                                                       @RequestBody PageAssistantWorkflowBindRequest request) {
        try {
            ScanProjectEntity project = resolveProject(request);
            PageAssistantWorkflowBindingResult result = pageAssistantWorkflowBindingService.bindExistingPageWorkflow(
                    project,
                    id,
                    request.getAgentId(),
                    request.getPageKey(),
                    request.getRoutePattern(),
                    request.getActionKeys());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    private ScanProjectEntity resolveProject(PageAssistantWorkflowBindRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (request.getProjectId() != null) {
            ScanProjectEntity project = scanProjectService.getById(request.getProjectId());
            if (project == null) {
                throw new IllegalArgumentException("project not found: " + request.getProjectId());
            }
            return project;
        }
        if (StringUtils.hasText(request.getProjectCode())) {
            return scanProjectService.findByProjectCode(request.getProjectCode().trim())
                    .orElseThrow(() -> new IllegalArgumentException("project not found: " + request.getProjectCode()));
        }
        throw new IllegalArgumentException("projectId or projectCode is required");
    }
}
