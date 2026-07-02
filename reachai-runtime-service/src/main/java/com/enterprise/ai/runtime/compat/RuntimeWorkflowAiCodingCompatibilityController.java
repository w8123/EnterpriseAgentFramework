package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RuntimeWorkflowAiCodingCompatibilityController {

    private final RuntimeWorkflowAiCodingService workflowAiCodingService;

    @PostMapping("/api/workflows/ai-coding/workflows")
    public ResponseEntity<RuntimeWorkflowAiCodingService.ContextView> create(
            @RequestBody RuntimeWorkflowAiCodingService.CreateRequest request) {
        return ResponseEntity.ok(workflowAiCodingService.createWorkflow(request));
    }

    @GetMapping("/api/workflows/{workflowId}/ai-coding/context")
    public ResponseEntity<RuntimeWorkflowAiCodingService.ContextView> context(@PathVariable String workflowId) {
        return ResponseEntity.ok(workflowAiCodingService.context(workflowId));
    }

    @PostMapping("/api/workflows/{workflowId}/ai-coding/validate")
    public ResponseEntity<RuntimeWorkflowAiCodingService.ValidationView> validate(
            @PathVariable String workflowId,
            @RequestBody(required = false) RuntimeWorkflowAiCodingService.ValidateRequest request) {
        return ResponseEntity.ok(workflowAiCodingService.validateWorkflow(workflowId, request));
    }

    @PostMapping("/api/workflows/{workflowId}/ai-coding/patch")
    public ResponseEntity<RuntimeWorkflowAiCodingService.PatchView> patch(
            @PathVariable String workflowId,
            @RequestBody(required = false) RuntimeWorkflowAiCodingService.PatchRequest request) {
        return ResponseEntity.ok(workflowAiCodingService.patchWorkflow(workflowId, request));
    }

    @PostMapping("/api/workflows/{workflowId}/ai-coding/run")
    public ResponseEntity<RuntimeWorkflowAiCodingService.RunView> run(
            @PathVariable String workflowId,
            @RequestBody(required = false) RuntimeWorkflowAiCodingService.RunRequest request) {
        return ResponseEntity.ok(workflowAiCodingService.runWorkflow(workflowId, request));
    }

    @GetMapping("/api/workflows/{workflowId}/ai-coding/versions")
    public ResponseEntity<RuntimeWorkflowAiCodingService.VersionsView> versions(@PathVariable String workflowId) {
        return ResponseEntity.ok(workflowAiCodingService.versions(workflowId));
    }

    @PostMapping("/api/workflows/{workflowId}/ai-coding/publish")
    public ResponseEntity<RuntimeWorkflowAiCodingService.PublishView> publish(
            @PathVariable String workflowId,
            @RequestBody(required = false) RuntimeWorkflowAiCodingService.PublishRequest request) {
        return ResponseEntity.ok(workflowAiCodingService.publishWorkflow(workflowId, request));
    }

    @GetMapping("/api/workflows/{workflowId}/ai-coding/runs")
    public ResponseEntity<RuntimeWorkflowAiCodingService.RunListView> runs(
            @PathVariable String workflowId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer days) {
        return ResponseEntity.ok(workflowAiCodingService.runs(workflowId, limit, days));
    }

    @GetMapping("/api/workflows/{workflowId}/ai-coding/runs/{traceId}")
    public ResponseEntity<RuntimeWorkflowAiCodingService.RunDetailView> runDetail(
            @PathVariable String workflowId,
            @PathVariable String traceId) {
        return ResponseEntity.ok(workflowAiCodingService.runDetail(workflowId, traceId));
    }

    @GetMapping("/api/workflows/{workflowId}/ai-coding/page-assistant/catalog")
    public ResponseEntity<RuntimeWorkflowAiCodingService.PageAssistantCatalogView> pageAssistantCatalog(
            @PathVariable String workflowId) {
        return ResponseEntity.ok(workflowAiCodingService.pageAssistantCatalog(workflowId));
    }

    @PostMapping("/api/workflows/{workflowId}/ai-coding/page-assistant/validate")
    public ResponseEntity<RuntimeWorkflowAiCodingService.PageAssistantValidateView> validatePageAssistant(
            @PathVariable String workflowId,
            @RequestBody(required = false) RuntimeWorkflowAiCodingService.PageAssistantValidateRequest request) {
        return ResponseEntity.ok(workflowAiCodingService.validatePageAssistant(workflowId, request));
    }

    @PostMapping("/api/workflows/{workflowId}/ai-coding/page-assistant/smoke-test")
    public ResponseEntity<RuntimeWorkflowAiCodingService.RunView> smokeTestPageAssistant(
            @PathVariable String workflowId,
            @RequestBody(required = false) RuntimeWorkflowAiCodingService.RunRequest request) {
        return ResponseEntity.ok(workflowAiCodingService.smokeTestPageAssistant(workflowId, request));
    }
}
