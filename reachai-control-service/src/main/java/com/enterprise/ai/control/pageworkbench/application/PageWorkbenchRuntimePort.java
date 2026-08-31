package com.enterprise.ai.control.pageworkbench.application;

import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PublishedWorkflowView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowDeliveryView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowEngineeringDraftView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowExecutionReadinessView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowReleaseReadinessView;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

/** Runtime operations used by Page Workbench without exposing a Feign client. */
public interface PageWorkbenchRuntimePort {

    ResponseEntity<Object> pageWorkbenchWorkflowNodeTypes();

    ResponseEntity<WorkflowEngineeringDraftView>
            createPageWorkbenchWorkflowDraft(
                    String projectCode,
                    Map<String, Object> body);

    ResponseEntity<WorkflowDeliveryView> deliverPageWorkbenchWorkflow(
            String projectCode,
            String workflowId,
            Map<String, Object> body);

    ResponseEntity<List<PublishedWorkflowView>> pageWorkbenchPublished(
            String projectCode,
            String pageKey);

    ResponseEntity<WorkflowReleaseReadinessView> pageWorkbenchReleaseReadiness(
            String projectCode,
            String pageKey,
            String workflowId,
            String workflowVersion);

    ResponseEntity<WorkflowExecutionReadinessView>
            pageWorkbenchExecutionReadiness(
                    String projectCode,
                    String pageKey,
                    String sessionId,
                    String pageInstanceId,
                    String traceId,
                    String workflowId,
                    Long workflowVersionId,
                    String workflowVersion);
}
