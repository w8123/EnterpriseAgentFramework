package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDebugService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowSearchPage;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowStudioService;
import com.enterprise.ai.runtime.workflow.draft.RuntimeWorkflowDraftEditRequest;
import com.enterprise.ai.runtime.workflow.draft.RuntimeWorkflowDraftEditService;
import com.enterprise.ai.runtime.workflow.draft.RuntimeWorkflowDraftEditView;
import com.enterprise.ai.runtime.workflow.draft.RuntimeWorkflowDraftGenerationRequest;
import com.enterprise.ai.runtime.workflow.draft.RuntimeWorkflowDraftGenerationService;
import com.enterprise.ai.runtime.workflow.draft.RuntimeWorkflowDraftGenerationView;
import com.enterprise.ai.runtime.workflow.node.RuntimeWorkflowNodeCapabilityDescriptor;
import com.enterprise.ai.runtime.workflow.node.RuntimeWorkflowNodeCapabilityRegistry;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class RuntimeWorkflowCompatibilityController {

    private final RuntimeWorkflowDefinitionService workflowDefinitionService;
    private final RuntimeWorkflowReleaseValidationService validationService;
    private final RuntimeWorkflowStudioService studioService;
    private final RuntimeWorkflowDebugService debugService;
    private final RuntimeWorkflowDraftGenerationService draftGenerationService;
    private final RuntimeWorkflowDraftEditService draftEditService;
    private final RuntimeWorkflowNodeCapabilityRegistry nodeCapabilityRegistry;

    public RuntimeWorkflowCompatibilityController(RuntimeWorkflowDefinitionService workflowDefinitionService,
                                                  RuntimeWorkflowReleaseValidationService validationService,
                                                  RuntimeWorkflowStudioService studioService,
                                                  RuntimeWorkflowDebugService debugService,
                                                  RuntimeWorkflowDraftGenerationService draftGenerationService,
                                                  RuntimeWorkflowDraftEditService draftEditService) {
        this(workflowDefinitionService, validationService, studioService, debugService,
                draftGenerationService, draftEditService, new RuntimeWorkflowNodeCapabilityRegistry());
    }

    @Autowired
    public RuntimeWorkflowCompatibilityController(RuntimeWorkflowDefinitionService workflowDefinitionService,
                                                  RuntimeWorkflowReleaseValidationService validationService,
                                                  RuntimeWorkflowStudioService studioService,
                                                  RuntimeWorkflowDebugService debugService,
                                                  RuntimeWorkflowDraftGenerationService draftGenerationService,
                                                  RuntimeWorkflowDraftEditService draftEditService,
                                                  RuntimeWorkflowNodeCapabilityRegistry nodeCapabilityRegistry) {
        this.workflowDefinitionService = workflowDefinitionService;
        this.validationService = validationService;
        this.studioService = studioService;
        this.debugService = debugService;
        this.draftGenerationService = draftGenerationService;
        this.draftEditService = draftEditService;
        this.nodeCapabilityRegistry = nodeCapabilityRegistry == null
                ? new RuntimeWorkflowNodeCapabilityRegistry()
                : nodeCapabilityRegistry;
    }

    @GetMapping("/api/workflows")
    public ResponseEntity<List<RuntimeWorkflowDefinitionEntity>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String workflowType,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(workflowDefinitionService.list(projectId, projectCode, workflowType, status));
    }

    @GetMapping("/api/workflows/search")
    public ResponseEntity<RuntimeWorkflowSearchPage> search(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String workflowType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(workflowDefinitionService.search(
                projectId, projectCode, workflowType, status, keyword, current, size));
    }

    @PostMapping("/api/workflows")
    public ResponseEntity<RuntimeWorkflowDefinitionEntity> create(
            @RequestBody RuntimeWorkflowDefinitionEntity request) {
        return ResponseEntity.ok(workflowDefinitionService.create(request));
    }

    @GetMapping("/api/workflows/{id}")
    public ResponseEntity<RuntimeWorkflowDefinitionEntity> get(@PathVariable String id) {
        return workflowDefinitionService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/workflows/graph-node-types")
    public ResponseEntity<List<RuntimeWorkflowNodeCapabilityDescriptor>> graphNodeTypes() {
        return ResponseEntity.ok(nodeCapabilityRegistry.allCatalog());
    }

    @PostMapping("/api/workflows/runtime-validation")
    public ResponseEntity<RuntimeWorkflowRuntimeValidationView> validateRuntime(
            @RequestBody RuntimeWorkflowRuntimeValidationRequest request) {
        if (request == null) {
            return ResponseEntity.ok(new RuntimeWorkflowRuntimeValidationView(false, List.of(
                    new RuntimeWorkflowRuntimeValidationView.Item(
                            "WORKFLOW_REQUEST_EMPTY", null, "workflow runtime validation request is required")),
                    List.of()));
        }
        RuntimeWorkflowReleaseValidationResult result;
        if (request.graphSpecJson() != null && !request.graphSpecJson().isBlank()) {
            RuntimeWorkflowReleaseValidationResult.Builder report = RuntimeWorkflowReleaseValidationResult.builder();
            GraphSpec proposed = validationService.readGraph(request.graphSpecJson(), report);
            if (proposed == null) {
                result = report.build();
            } else {
                RuntimeWorkflowDefinitionEntity workflow = workflowForValidation(request, true);
                result = validationService.validateProposed(workflow, proposed);
            }
        } else {
            RuntimeWorkflowDefinitionEntity workflow = workflowForValidation(request, false);
            result = validationService.validate(workflow);
        }
        return ResponseEntity.ok(toValidationView(result));
    }

    @GetMapping("/api/workflows/{id}/studio")
    public ResponseEntity<RuntimeWorkflowStudioService.WorkflowStudioState> studio(@PathVariable String id) {
        return ResponseEntity.ok(studioService.getStudioState(id));
    }

    @PutMapping("/api/workflows/{id}/studio")
    public ResponseEntity<RuntimeWorkflowStudioService.WorkflowStudioState> saveStudio(
            @PathVariable String id,
            @RequestBody RuntimeWorkflowStudioSaveRequest request) {
        RuntimeWorkflowStudioService.WorkflowStudioSaveRequest saveRequest = request == null
                ? null
                : new RuntimeWorkflowStudioService.WorkflowStudioSaveRequest(
                        request.graphSpecJson(),
                        request.canvasJson(),
                        request.extraJson(),
                        request.baseRevision(),
                        request.keySlug(),
                        request.name(),
                        request.description(),
                        request.workflowType(),
                        request.runtimeType(),
                        request.inputSchemaJson(),
                        request.outputSchemaJson(),
                        request.defaultModelInstanceId(),
                        request.defaultResourceConfigJson());
        return ResponseEntity.ok(studioService.saveStudioDraft(id, saveRequest));
    }

    @PostMapping("/api/workflows/studio/debug-node")
    public ResponseEntity<RuntimeWorkflowDebugService.NodeDebugResult> debugWorkflowNode(
            @RequestBody RuntimeWorkflowDebugService.NodeDebugRequest request) {
        return ResponseEntity.ok(debugService.debugNode(request));
    }

    @PostMapping("/api/workflows/studio/debug-run")
    public ResponseEntity<RuntimeWorkflowDebugService.DebugRunResult> debugWorkflowRun(
            @RequestBody RuntimeWorkflowDebugService.DebugRunRequest request) {
        return ResponseEntity.ok(debugService.debugRun(request));
    }

    @PostMapping("/api/workflows/studio/generate-draft")
    public ResponseEntity<RuntimeWorkflowDraftGenerationView> generateWorkflowStudioDraft(
            @RequestBody RuntimeWorkflowDraftGenerationRequest request) {
        return ResponseEntity.ok(draftGenerationService.generate(request));
    }

    @PostMapping("/api/workflows/studio/edit-draft")
    public ResponseEntity<RuntimeWorkflowDraftEditView> editWorkflowStudioDraft(
            @RequestBody RuntimeWorkflowDraftEditRequest request) {
        return ResponseEntity.ok(draftEditService.edit(request));
    }

    @PutMapping("/api/workflows/{id}")
    public ResponseEntity<RuntimeWorkflowDefinitionEntity> update(
            @PathVariable String id,
            @RequestBody RuntimeWorkflowDefinitionEntity request) {
        return ResponseEntity.ok(workflowDefinitionService.update(id, request));
    }

    @DeleteMapping("/api/workflows/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        try {
            workflowDefinitionService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException ex) {
            String message = ex.getMessage();
            if (message != null && message.startsWith("workflow not found")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().body(Map.of("message", message != null ? message : "delete rejected"));
        }
    }

    private RuntimeWorkflowDefinitionEntity workflowForValidation(RuntimeWorkflowRuntimeValidationRequest request,
                                                                  boolean applyDraftOverrides) {
        if (request.workflowId() == null || request.workflowId().isBlank()) {
            RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
            workflow.setRuntimeType(request.runtimeType());
            return workflow;
        }
        RuntimeWorkflowDefinitionEntity persisted = workflowDefinitionService.findById(request.workflowId())
                .orElse(null);
        if (persisted == null || !applyDraftOverrides || request.defaultModelInstanceId() == null) {
            return persisted;
        }
        RuntimeWorkflowDefinitionEntity proposed = new RuntimeWorkflowDefinitionEntity();
        BeanUtils.copyProperties(persisted, proposed);
        proposed.setDefaultModelInstanceId(request.defaultModelInstanceId());
        return proposed;
    }

    private RuntimeWorkflowRuntimeValidationView toValidationView(RuntimeWorkflowReleaseValidationResult result) {
        return new RuntimeWorkflowRuntimeValidationView(
                result.valid(),
                result.errors().stream()
                        .map(item -> new RuntimeWorkflowRuntimeValidationView.Item(
                                item.code(),
                                item.nodeId(),
                                item.message()))
                        .toList(),
                result.warnings().stream()
                        .map(item -> new RuntimeWorkflowRuntimeValidationView.Item(
                                item.code(),
                                item.nodeId(),
                                item.message()))
                        .toList());
    }

}
