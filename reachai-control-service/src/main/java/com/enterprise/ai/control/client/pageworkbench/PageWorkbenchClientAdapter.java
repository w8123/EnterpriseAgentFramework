package com.enterprise.ai.control.client.pageworkbench;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.model.ControlModelCatalogClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PublishedWorkflowView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowDeliveryView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowEngineeringDraftView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowExecutionReadinessView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowReleaseReadinessView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchModelPort;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchProjectPort;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchRuntimePort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Infrastructure adapter that keeps Feign clients outside Page Workbench
 * application services while preserving their current response semantics.
 */
@Component
@RequiredArgsConstructor
public class PageWorkbenchClientAdapter implements
        PageWorkbenchProjectPort,
        PageWorkbenchModelPort,
        PageWorkbenchRuntimePort {

    private final CapabilityProjectOnboardingClient capabilityClient;
    private final ControlModelCatalogClient modelClient;
    private final RuntimeProxyClient runtimeClient;

    @Override
    public Map<String, Object> getProjectById(Long projectId) {
        return capabilityClient.getProjectById(projectId);
    }

    @Override
    public Map<String, Object> getOnboardingProjectById(Long projectId) {
        return capabilityClient.getOnboardingProjectById(projectId);
    }

    @Override
    public List<Map<String, Object>> listProjectTools(Long projectId) {
        return capabilityClient.listProjectTools(projectId);
    }

    @Override
    public ResponseEntity<Map<String, Object>> list(
            String projectCode,
            String modelType,
            String provider) {
        return modelClient.list(projectCode, modelType, provider);
    }

    @Override
    public ResponseEntity<Map<String, Object>> getInternal(String id) {
        return modelClient.getInternal(id);
    }

    @Override
    public ResponseEntity<Object> pageWorkbenchWorkflowNodeTypes() {
        return runtimeClient.pageWorkbenchWorkflowNodeTypes();
    }

    @Override
    public ResponseEntity<WorkflowEngineeringDraftView>
            createPageWorkbenchWorkflowDraft(
                    String projectCode,
                    Map<String, Object> body) {
        return runtimeClient.createPageWorkbenchWorkflowDraft(
                projectCode,
                body);
    }

    @Override
    public ResponseEntity<WorkflowDeliveryView> deliverPageWorkbenchWorkflow(
            String projectCode,
            String workflowId,
            Map<String, Object> body) {
        return runtimeClient.deliverPageWorkbenchWorkflow(
                projectCode,
                workflowId,
                body);
    }

    @Override
    public ResponseEntity<List<PublishedWorkflowView>> pageWorkbenchPublished(
            String projectCode,
            String pageKey) {
        return runtimeClient.pageWorkbenchPublished(projectCode, pageKey);
    }

    @Override
    public ResponseEntity<WorkflowReleaseReadinessView>
            pageWorkbenchReleaseReadiness(
                    String projectCode,
                    String pageKey,
                    String workflowId,
                    String workflowVersion) {
        return runtimeClient.pageWorkbenchReleaseReadiness(
                projectCode,
                pageKey,
                workflowId,
                workflowVersion);
    }

    @Override
    public ResponseEntity<WorkflowExecutionReadinessView>
            pageWorkbenchExecutionReadiness(
                    String projectCode,
                    String pageKey,
                    String sessionId,
                    String pageInstanceId,
                    String traceId,
                    String workflowId,
                    Long workflowVersionId,
                    String workflowVersion) {
        return runtimeClient.pageWorkbenchExecutionReadiness(
                projectCode,
                pageKey,
                sessionId,
                pageInstanceId,
                traceId,
                workflowId,
                workflowVersionId,
                workflowVersion);
    }
}
