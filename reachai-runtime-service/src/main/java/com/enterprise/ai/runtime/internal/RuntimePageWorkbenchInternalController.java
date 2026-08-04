package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.runtime.workflow.RuntimePageWorkbenchPublishedQueryService;
import com.enterprise.ai.runtime.workflow.RuntimePageWorkbenchPublishedQueryService.PublishedWorkflowView;
import com.enterprise.ai.runtime.workflow.RuntimePageWorkbenchExecutionReadinessService;
import com.enterprise.ai.runtime.workflow.RuntimePageWorkbenchExecutionReadinessService.ExecutionReadinessView;
import com.enterprise.ai.runtime.workflow.RuntimePageWorkbenchReleaseReadinessService;
import com.enterprise.ai.runtime.workflow.RuntimePageWorkbenchReleaseReadinessService.ReleaseReadinessView;
import com.enterprise.ai.runtime.workflow.RuntimePageWorkbenchWorkflowDeliveryService;
import com.enterprise.ai.runtime.workflow.RuntimePageWorkbenchWorkflowDeliveryService.DeliveryRequest;
import com.enterprise.ai.runtime.workflow.RuntimePageWorkbenchWorkflowDeliveryService.DeliveryView;
import com.enterprise.ai.runtime.workflow.RuntimePageWorkbenchWorkflowDeliveryService.EngineeringDraftRequest;
import com.enterprise.ai.runtime.workflow.RuntimePageWorkbenchWorkflowDeliveryService.EngineeringDraftView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class RuntimePageWorkbenchInternalController {

    private final RuntimePageWorkbenchPublishedQueryService queryService;
    private final RuntimePageWorkbenchReleaseReadinessService readinessService;
    private final RuntimePageWorkbenchExecutionReadinessService
            executionReadinessService;
    private final RuntimePageWorkbenchWorkflowDeliveryService
            workflowDeliveryService;

    @PostMapping("/internal/runtime/page-workbench/projects/{projectCode}/workflow-drafts")
    public ResponseEntity<EngineeringDraftView> createWorkflowDraft(
            @PathVariable String projectCode,
            @RequestBody EngineeringDraftRequest request) {
        return ResponseEntity.ok(workflowDeliveryService.createDraft(
                projectCode,
                request));
    }

    @PostMapping("/internal/runtime/page-workbench/projects/{projectCode}/workflows/{workflowId}/deliver")
    public ResponseEntity<DeliveryView> deliverWorkflow(
            @PathVariable String projectCode,
            @PathVariable String workflowId,
            @RequestBody DeliveryRequest request) {
        return ResponseEntity.ok(workflowDeliveryService.deliver(
                projectCode,
                workflowId,
                request));
    }

    @GetMapping("/internal/runtime/page-workbench/projects/{projectCode}/published")
    public ResponseEntity<List<PublishedWorkflowView>> list(
            @PathVariable String projectCode,
            @RequestParam(required = false) String pageKey) {
        return ResponseEntity.ok(queryService.list(projectCode, pageKey));
    }

    @GetMapping("/internal/runtime/page-workbench/projects/{projectCode}/release-readiness")
    public ResponseEntity<ReleaseReadinessView> releaseReadiness(
            @PathVariable String projectCode,
            @RequestParam String pageKey,
            @RequestParam String workflowId,
            @RequestParam String workflowVersion) {
        return ResponseEntity.ok(readinessService.evaluate(
                projectCode,
                pageKey,
                workflowId,
                workflowVersion));
    }

    @GetMapping("/internal/runtime/page-workbench/projects/{projectCode}/execution-readiness")
    public ResponseEntity<ExecutionReadinessView> executionReadiness(
            @PathVariable String projectCode,
            @RequestParam String pageKey,
            @RequestParam String sessionId,
            @RequestParam String pageInstanceId,
            @RequestParam String traceId,
            @RequestParam String workflowId,
            @RequestParam Long workflowVersionId,
            @RequestParam String workflowVersion) {
        return ResponseEntity.ok(executionReadinessService.evaluate(
                projectCode,
                pageKey,
                sessionId,
                pageInstanceId,
                traceId,
                workflowId,
                workflowVersionId,
                workflowVersion));
    }
}
