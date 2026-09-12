package com.enterprise.ai.runtime.api;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowWriteCommand;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowManagementService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDebugService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowSearchPage;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowStudioService;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalEditRequest;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalEditService;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalEditView;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalGenerationRequest;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalGenerationService;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalGenerationView;
import com.enterprise.ai.runtime.workflow.node.RuntimeWorkflowNodeCapabilityDescriptor;
import com.enterprise.ai.runtime.workflow.node.RuntimeWorkflowNodeCapabilityRegistry;
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
public class RuntimeWorkflowPublicController {

    private final RuntimeWorkflowManagementService workflowManagementService;
    private final RuntimeWorkflowStudioService studioService;
    private final RuntimeWorkflowDebugService debugService;
    private final RuntimeWorkflowProposalGenerationService proposalGenerationService;
    private final RuntimeWorkflowProposalEditService proposalEditService;
    private final RuntimeWorkflowNodeCapabilityRegistry nodeCapabilityRegistry;

    public RuntimeWorkflowPublicController(RuntimeWorkflowManagementService workflowManagementService,
                                                  RuntimeWorkflowStudioService studioService,
                                                  RuntimeWorkflowDebugService debugService,
                                                   RuntimeWorkflowProposalGenerationService proposalGenerationService,
                                                   RuntimeWorkflowProposalEditService proposalEditService) {
        this(workflowManagementService, studioService, debugService,
                proposalGenerationService, proposalEditService, new RuntimeWorkflowNodeCapabilityRegistry());
    }

    @Autowired
    public RuntimeWorkflowPublicController(RuntimeWorkflowManagementService workflowManagementService,
                                                  RuntimeWorkflowStudioService studioService,
                                                  RuntimeWorkflowDebugService debugService,
                                                   RuntimeWorkflowProposalGenerationService proposalGenerationService,
                                                   RuntimeWorkflowProposalEditService proposalEditService,
                                                  RuntimeWorkflowNodeCapabilityRegistry nodeCapabilityRegistry) {
        this.workflowManagementService = workflowManagementService;
        this.studioService = studioService;
        this.debugService = debugService;
        this.proposalGenerationService = proposalGenerationService;
        this.proposalEditService = proposalEditService;
        this.nodeCapabilityRegistry = nodeCapabilityRegistry == null
                ? new RuntimeWorkflowNodeCapabilityRegistry()
                : nodeCapabilityRegistry;
    }

    @GetMapping("/api/workflows")
    public ResponseEntity<List<RuntimeWorkflowDefinitionView>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String workflowKind,
            @RequestParam(required = false) String definitionAuthority,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(workflowManagementService.list(
                projectId,
                projectCode,
                workflowKind,
                definitionAuthority,
                status));
    }

    @GetMapping("/api/workflows/search")
    public ResponseEntity<RuntimeWorkflowSearchPage> search(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String workflowKind,
            @RequestParam(required = false) String definitionAuthority,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(workflowManagementService.search(
                projectId,
                projectCode,
                workflowKind,
                definitionAuthority,
                status,
                keyword,
                current,
                size));
    }

    @PostMapping("/api/workflows")
    public ResponseEntity<RuntimeWorkflowDefinitionView> create(
            @RequestBody RuntimeWorkflowWriteCommand request) {
        return ResponseEntity.ok(workflowManagementService.create(request));
    }

    @GetMapping("/api/workflows/{id}")
    public ResponseEntity<RuntimeWorkflowDefinitionView> get(@PathVariable String id) {
        return workflowManagementService.findById(id)
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
        RuntimeWorkflowReleaseValidationResult result = workflowManagementService.validateRuntime(
                request.workflowId(), request.graphSpecJson(), request.executionEngine(), request.defaultModelInstanceId());
        return ResponseEntity.ok(toValidationView(result));
    }

    @GetMapping("/api/workflows/{id}/working-copy")
    public ResponseEntity<RuntimeWorkflowStudioService.WorkflowWorkingCopyState> workingCopy(
            @PathVariable String id) {
        return ResponseEntity.ok(studioService.getWorkingCopy(id));
    }

    @PutMapping("/api/workflows/{id}/working-copy")
    public ResponseEntity<RuntimeWorkflowStudioService.WorkflowWorkingCopyState> saveWorkingCopy(
            @PathVariable String id,
            @RequestBody RuntimeWorkflowWorkingCopySaveRequest request) {
        RuntimeWorkflowStudioService.SaveWorkingCopyCommand command = request == null
                ? null
                : new RuntimeWorkflowStudioService.SaveWorkingCopyCommand(
                        request.graphSpecJson(),
                        request.canvasJson(),
                        request.extraJson(),
                        request.baseRevision(),
                        request.keySlug(),
                        request.name(),
                        request.description(),
                        request.inputSchemaJson(),
                        request.outputSchemaJson(),
                        request.defaultModelInstanceId(),
                        request.defaultResourceConfigJson(),
                        request.workflowKind(),
                        request.executionEngine(),
                        request.definitionAuthority(),
                        request.creationChannel());
        return ResponseEntity.ok(studioService.saveWorkingCopy(id, command));
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

    @PostMapping("/api/workflows/studio/proposals/generate")
    public ResponseEntity<RuntimeWorkflowProposalGenerationView> generateWorkflowProposal(
            @RequestBody RuntimeWorkflowProposalGenerationRequest request) {
        return ResponseEntity.ok(proposalGenerationService.generate(request));
    }

    @PostMapping("/api/workflows/studio/proposals/edit")
    public ResponseEntity<RuntimeWorkflowProposalEditView> editWorkflowProposal(
            @RequestBody RuntimeWorkflowProposalEditRequest request) {
        return ResponseEntity.ok(proposalEditService.edit(request));
    }

    @PutMapping("/api/workflows/{id}")
    public ResponseEntity<RuntimeWorkflowDefinitionView> update(
            @PathVariable String id,
            @RequestBody RuntimeWorkflowWriteCommand request) {
        return ResponseEntity.ok(workflowManagementService.update(id, request));
    }

    @DeleteMapping("/api/workflows/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        try {
            workflowManagementService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException ex) {
            String message = ex.getMessage();
            if (message != null && message.startsWith("workflow not found")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().body(Map.of("message", message != null ? message : "delete rejected"));
        }
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
