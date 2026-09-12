package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.runtime.agent.RuntimeAgentPublishedWorkflowToolQuery;
import com.enterprise.ai.runtime.agent.RuntimeAgentPublishedWorkflowToolQuery.Binding;
import com.enterprise.ai.runtime.runops.RuntimeWorkflowRunMetricsQuery;
import com.enterprise.ai.runtime.runops.RuntimeWorkflowRunMetricsQuery.Metrics;
import com.enterprise.ai.runtime.workflow.RuntimePublishedPageWorkflowQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Composes the internal page-workbench response from domain-owned queries. */
@Service
@RequiredArgsConstructor
public class RuntimePageWorkbenchPublishedQueryService {
    private final RuntimePublishedPageWorkflowQuery workflows;
    private final RuntimeAgentPublishedWorkflowToolQuery agentTools;
    private final RuntimeWorkflowRunMetricsQuery runMetrics;

    public List<PublishedWorkflowView> list(String projectCode, String pageKey) {
        List<PublishedWorkflowView> results = new ArrayList<>();
        for (var workflow : workflows.list(projectCode, pageKey)) {
            Binding activeTool = agentTools.findPublishedBinding(workflow.workflowId(), workflow.workflowVersionId()).orElse(null);
            if (activeTool == null) {
                continue;
            }
            Metrics metrics = runMetrics.recent(workflow.workflowId());
            results.add(new PublishedWorkflowView(
                    workflow.pageKey(),
                    workflow.workflowId(),
                    workflow.workflowKeySlug(),
                    workflow.workflowName(),
                    workflow.workflowDescription(),
                    workflow.workflowStatus(),
                    workflow.workflowVersionId(),
                    workflow.workflowVersion(),
                    workflow.publishedBy(),
                    workflow.publishedAt(),
                    activeTool.agentId(),
                    activeTool.agentKeySlug(),
                    activeTool.agentName(),
                    activeTool.agentConfigVersionId(),
                    activeTool.agentConfigVersion(),
                    activeTool.modelInstanceId(),
                    activeTool.toolName(),
                    activeTool.riskLevel(),
                    activeTool.permissionKey(),
                    metrics.callCount(),
                    metrics.successRate(),
                    metrics.latestCallAt(),
                    metrics.latestStatus(),
                    metrics.latestTraceId()));
        }
        return results;
    }

    public record PublishedWorkflowView(
            String pageKey,
            String workflowId,
            String workflowKeySlug,
            String workflowName,
            String workflowDescription,
            String workflowStatus,
            Long workflowVersionId,
            String workflowVersion,
            String publishedBy,
            LocalDateTime publishedAt,
            String agentId,
            String agentKeySlug,
            String agentName,
            Long agentConfigVersionId,
            Integer agentConfigVersion,
            String modelInstanceId,
            String toolName,
            String riskLevel,
            String permissionKey,
            int recentCallCount,
            Double successRate,
            LocalDateTime latestCallAt,
            String latestCallStatus,
            String latestTraceId) {
    }
}
