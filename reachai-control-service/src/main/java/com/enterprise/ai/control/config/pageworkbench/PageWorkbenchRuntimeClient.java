package com.enterprise.ai.control.config.pageworkbench;

import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PublishedWorkflowView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowDeliveryView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowEngineeringDraftView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowExecutionReadinessView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowReleaseReadinessView;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;
import java.util.Map;

/** Page Workbench transport binding; its business response types stay outside the shared HTTP client module. */
@FeignClient(name = "reachai-page-workbench-runtime", url = "${services.runtime-service.url:http://localhost:18604}")
public interface PageWorkbenchRuntimeClient {
    @RequestMapping(method = RequestMethod.GET,
            path = "/internal/runtime/page-workbench/workflow-node-types")
    ResponseEntity<Object> pageWorkbenchWorkflowNodeTypes();

    @RequestMapping(method = RequestMethod.POST,
            path = "/internal/runtime/page-workbench/projects/{projectCode}/workflow-drafts")
    ResponseEntity<WorkflowEngineeringDraftView>
            createPageWorkbenchWorkflowDraft(
                    @PathVariable("projectCode") String projectCode,
                    @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST,
            path = "/internal/runtime/page-workbench/projects/{projectCode}/workflows/{workflowId}/deliver")
    ResponseEntity<WorkflowDeliveryView> deliverPageWorkbenchWorkflow(
            @PathVariable("projectCode") String projectCode,
            @PathVariable("workflowId") String workflowId,
            @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET,
            path = "/internal/runtime/page-workbench/projects/{projectCode}/published")
    ResponseEntity<List<PublishedWorkflowView>> pageWorkbenchPublished(
            @PathVariable("projectCode") String projectCode,
            @RequestParam(value = "pageKey", required = false) String pageKey);

    @RequestMapping(method = RequestMethod.GET,
            path = "/internal/runtime/page-workbench/projects/{projectCode}/release-readiness")
    ResponseEntity<WorkflowReleaseReadinessView> pageWorkbenchReleaseReadiness(
            @PathVariable("projectCode") String projectCode,
            @RequestParam("pageKey") String pageKey,
            @RequestParam("workflowId") String workflowId,
            @RequestParam("workflowVersion") String workflowVersion);

    @RequestMapping(method = RequestMethod.GET,
            path = "/internal/runtime/page-workbench/projects/{projectCode}/execution-readiness")
    ResponseEntity<WorkflowExecutionReadinessView> pageWorkbenchExecutionReadiness(
            @PathVariable("projectCode") String projectCode,
            @RequestParam("pageKey") String pageKey,
            @RequestParam("sessionId") String sessionId,
            @RequestParam("pageInstanceId") String pageInstanceId,
            @RequestParam("traceId") String traceId,
            @RequestParam("workflowId") String workflowId,
            @RequestParam("workflowVersionId") Long workflowVersionId,
            @RequestParam("workflowVersion") String workflowVersion);

}
