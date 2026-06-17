package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.workflow.aicoding.WorkflowAiCodingContextResponse;
import com.enterprise.ai.agent.workflow.aicoding.WorkflowAiCodingPatchRequest;
import com.enterprise.ai.agent.workflow.aicoding.WorkflowAiCodingPatchResponse;
import com.enterprise.ai.agent.workflow.aicoding.WorkflowAiCodingRunRequest;
import com.enterprise.ai.agent.workflow.aicoding.WorkflowAiCodingRunResponse;
import com.enterprise.ai.agent.workflow.aicoding.WorkflowAiCodingRunDetailResponse;
import com.enterprise.ai.agent.workflow.aicoding.WorkflowAiCodingRunListResponse;
import com.enterprise.ai.agent.workflow.aicoding.WorkflowAiCodingService;
import com.enterprise.ai.agent.workflow.aicoding.WorkflowAiCodingValidateRequest;
import com.enterprise.ai.agent.workflow.aicoding.WorkflowAiCodingValidateResponse;
import com.enterprise.ai.agent.workflow.aicoding.WorkflowAiCodingVersionsResponse;
import com.enterprise.ai.agent.workflow.aicoding.pageassistant.WorkflowPageAssistantAiCodingService;
import com.enterprise.ai.agent.workflow.aicoding.pageassistant.WorkflowPageAssistantCatalogResponse;
import com.enterprise.ai.agent.workflow.aicoding.pageassistant.WorkflowPageAssistantSmokeTestRequest;
import com.enterprise.ai.agent.workflow.aicoding.pageassistant.WorkflowPageAssistantSmokeTestResponse;
import com.enterprise.ai.agent.workflow.aicoding.pageassistant.WorkflowPageAssistantValidateRequest;
import com.enterprise.ai.agent.workflow.aicoding.pageassistant.WorkflowPageAssistantValidateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workflows/{workflowId}/ai-coding")
@RequiredArgsConstructor
public class WorkflowAiCodingController {

    private final WorkflowAiCodingService aiCodingService;
    private final WorkflowPageAssistantAiCodingService pageAssistantAiCodingService;

    @GetMapping("/context")
    public ResponseEntity<WorkflowAiCodingContextResponse> context(@PathVariable String workflowId) {
        return ResponseEntity.ok(aiCodingService.getContext(workflowId));
    }

    @PostMapping("/validate")
    public ResponseEntity<WorkflowAiCodingValidateResponse> validate(@PathVariable String workflowId,
                                                                       @RequestBody(required = false)
                                                                       WorkflowAiCodingValidateRequest request) {
        return ResponseEntity.ok(aiCodingService.validate(workflowId, request));
    }

    @PostMapping("/patch")
    public ResponseEntity<WorkflowAiCodingPatchResponse> patch(@PathVariable String workflowId,
                                                               @RequestBody(required = false)
                                                               WorkflowAiCodingPatchRequest request) {
        return ResponseEntity.ok(aiCodingService.patch(workflowId, request));
    }

    @PostMapping("/run")
    public ResponseEntity<WorkflowAiCodingRunResponse> run(@PathVariable String workflowId,
                                                             @RequestBody(required = false)
                                                             WorkflowAiCodingRunRequest request) {
        return ResponseEntity.ok(aiCodingService.run(workflowId, request));
    }

    @GetMapping("/versions")
    public ResponseEntity<WorkflowAiCodingVersionsResponse> versions(@PathVariable String workflowId) {
        return ResponseEntity.ok(aiCodingService.getVersions(workflowId));
    }

    @GetMapping("/runs")
    public ResponseEntity<WorkflowAiCodingRunListResponse> runs(@PathVariable String workflowId,
                                                                @RequestParam(required = false) Integer limit,
                                                                @RequestParam(required = false) Integer days) {
        return ResponseEntity.ok(aiCodingService.listRuns(workflowId, limit, days));
    }

    @GetMapping("/runs/{traceId}")
    public ResponseEntity<WorkflowAiCodingRunDetailResponse> runDetail(@PathVariable String workflowId,
                                                                       @PathVariable String traceId) {
        return ResponseEntity.ok(aiCodingService.getRunDetail(workflowId, traceId));
    }

    @GetMapping("/page-assistant/catalog")
    public ResponseEntity<WorkflowPageAssistantCatalogResponse> pageAssistantCatalog(
            @PathVariable String workflowId) {
        return ResponseEntity.ok(pageAssistantAiCodingService.getCatalog(workflowId));
    }

    @PostMapping("/page-assistant/validate")
    public ResponseEntity<WorkflowPageAssistantValidateResponse> pageAssistantValidate(
            @PathVariable String workflowId,
            @RequestBody(required = false) WorkflowPageAssistantValidateRequest request) {
        return ResponseEntity.ok(pageAssistantAiCodingService.validate(workflowId, request));
    }

    @PostMapping("/page-assistant/smoke-test")
    public ResponseEntity<WorkflowPageAssistantSmokeTestResponse> pageAssistantSmokeTest(
            @PathVariable String workflowId,
            @RequestBody(required = false) WorkflowPageAssistantSmokeTestRequest request) {
        return ResponseEntity.ok(pageAssistantAiCodingService.smokeTest(workflowId, request));
    }
}
