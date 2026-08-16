package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionMapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowResourceBindingService.BindingView;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class RuntimePageWorkbenchPublishedQueryService {

    private final RuntimeWorkflowResourceBindingService bindingService;
    private final RuntimeWorkflowDefinitionMapper workflowMapper;
    private final RuntimeWorkflowVersionMapper versionMapper;
    private final RuntimeAgentWorkflowToolMapper workflowToolMapper;
    private final RuntimeAgentMapper agentMapper;
    private final RuntimeAgentConfigVersionMapper agentConfigMapper;
    private final JdbcTemplate jdbcTemplate;

    public List<PublishedWorkflowView> list(String projectCode, String pageKey) {
        List<BindingView> bindings = bindingService.find(
                projectCode,
                RuntimeWorkflowResourceBindingService.RESOURCE_PAGE,
                pageKey);
        List<PublishedWorkflowView> results = new ArrayList<>();
        for (BindingView binding : bindings) {
            if (!RuntimeWorkflowResourceBindingService.ROLE_TARGET.equals(binding.bindingRole())) {
                continue;
            }
            RuntimeWorkflowDefinitionEntity workflow = workflowMapper.selectById(binding.workflowId());
            if (workflow == null
                    || !WorkflowSemanticValues.KIND_PAGE_ASSISTANT.equals(workflow.getWorkflowKind())
                    || !"ACTIVE".equalsIgnoreCase(workflow.getStatus())) {
                continue;
            }
            RuntimeWorkflowVersionEntity version = versionMapper.selectOne(
                    Wrappers.<RuntimeWorkflowVersionEntity>lambdaQuery()
                            .eq(RuntimeWorkflowVersionEntity::getWorkflowId, workflow.getId())
                            .eq(RuntimeWorkflowVersionEntity::getStatus, "ACTIVE")
                            .orderByDesc(RuntimeWorkflowVersionEntity::getId)
                            .last("LIMIT 1"));
            if (version == null) {
                continue;
            }
            ActiveTool activeTool = activeTool(workflow.getId(), version.getId());
            if (activeTool == null) {
                continue;
            }
            RunMetrics metrics = runMetrics(workflow.getId());
            results.add(new PublishedWorkflowView(
                    binding.resourceKey(),
                    workflow.getId(),
                    workflow.getKeySlug(),
                    workflow.getName(),
                    workflow.getDescription(),
                    workflow.getStatus(),
                    version.getId(),
                    version.getVersion(),
                    version.getPublishedBy(),
                    version.getPublishedAt(),
                    activeTool.agent().getId(),
                    activeTool.agent().getKeySlug(),
                    activeTool.agent().getName(),
                    activeTool.config().getId(),
                    activeTool.config().getVersionNo(),
                    activeTool.config().getModelInstanceId(),
                    activeTool.tool().getToolName(),
                    activeTool.tool().getRiskLevel(),
                    activeTool.tool().getPermissionKey(),
                    metrics.callCount(),
                    metrics.successRate(),
                    metrics.latestCallAt(),
                    metrics.latestStatus(),
                    metrics.latestTraceId()));
        }
        return results;
    }

    private ActiveTool activeTool(String workflowId, Long workflowVersionId) {
        List<RuntimeAgentWorkflowToolEntity> tools = workflowToolMapper.selectList(
                Wrappers.<RuntimeAgentWorkflowToolEntity>lambdaQuery()
                        .eq(RuntimeAgentWorkflowToolEntity::getWorkflowId, workflowId)
                        .eq(RuntimeAgentWorkflowToolEntity::getWorkflowVersionId, workflowVersionId)
                        .eq(RuntimeAgentWorkflowToolEntity::getEnabled, true)
                        .orderByDesc(RuntimeAgentWorkflowToolEntity::getUpdatedAt));
        for (RuntimeAgentWorkflowToolEntity tool : tools) {
            RuntimeAgentEntity agent = agentMapper.selectById(tool.getAgentId());
            if (agent == null
                    || !Boolean.TRUE.equals(agent.getEnabled())
                    || !Objects.equals(agent.getActiveConfigVersionId(), tool.getAgentConfigVersionId())) {
                continue;
            }
            RuntimeAgentConfigVersionEntity config = agentConfigMapper.selectById(
                    tool.getAgentConfigVersionId());
            if (config != null && "ACTIVE".equalsIgnoreCase(config.getStatus())) {
                return new ActiveTool(agent, config, tool);
            }
        }
        return null;
    }

    private RunMetrics runMetrics(String workflowId) {
        LocalDateTime recentSince = LocalDateTime.now().minusDays(30);
        List<RunMetrics> metrics = jdbcTemplate.query("""
                        SELECT COUNT(*) AS call_count,
                               SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) AS success_count,
                               MAX(started_at) AS latest_call_at
                          FROM (
                                SELECT trace_id, status, started_at
                                  FROM runtime_run
                                 WHERE workflow_id = ?
                                   AND started_at >= ?
                                UNION ALL
                                SELECT trace_id,
                                       CASE WHEN status = 'SUCCESS'
                                            THEN 'COMPLETED'
                                            ELSE status
                                       END AS status,
                                       started_at
                                  FROM runtime_trace_span
                                 WHERE span_type = 'WORKFLOW_TOOL'
                                   AND node_id = ?
                                   AND started_at >= ?
                               ) invocation
                        """,
                (rs, rowNum) -> metricsRow(rs),
                workflowId,
                recentSince,
                workflowId,
                recentSince);
        if (metrics.isEmpty()) {
            return RunMetrics.empty();
        }
        RunMetrics aggregate = metrics.get(0);
        if (aggregate.latestCallAt() == null) {
            return aggregate;
        }
        List<RunLatest> latest = jdbcTemplate.query("""
                        SELECT trace_id, status
                          FROM (
                                SELECT trace_id, status, started_at, id AS source_id
                                  FROM runtime_run
                                 WHERE workflow_id = ?
                                UNION ALL
                                SELECT trace_id,
                                       CASE WHEN status = 'SUCCESS'
                                            THEN 'COMPLETED'
                                            ELSE status
                                       END AS status,
                                       started_at,
                                       id AS source_id
                                  FROM runtime_trace_span
                                 WHERE span_type = 'WORKFLOW_TOOL'
                                   AND node_id = ?
                               ) invocation
                         ORDER BY started_at DESC, source_id DESC
                         LIMIT 1
                        """,
                (rs, rowNum) -> new RunLatest(rs.getString("trace_id"), rs.getString("status")),
                workflowId,
                workflowId);
        return latest.isEmpty()
                ? aggregate
                : new RunMetrics(
                        aggregate.callCount(),
                        aggregate.successRate(),
                        aggregate.latestCallAt(),
                        latest.get(0).status(),
                        latest.get(0).traceId());
    }

    private RunMetrics metricsRow(ResultSet rs) throws SQLException {
        int count = rs.getInt("call_count");
        int success = rs.getInt("success_count");
        LocalDateTime latest = rs.getObject("latest_call_at", LocalDateTime.class);
        return new RunMetrics(
                count,
                count == 0 ? null : Math.round((success * 10000.0 / count)) / 100.0,
                latest,
                null,
                null);
    }

    private record ActiveTool(
            RuntimeAgentEntity agent,
            RuntimeAgentConfigVersionEntity config,
            RuntimeAgentWorkflowToolEntity tool) {
    }

    private record RunLatest(String traceId, String status) {
    }

    private record RunMetrics(
            int callCount,
            Double successRate,
            LocalDateTime latestCallAt,
            String latestStatus,
            String latestTraceId) {
        private static RunMetrics empty() {
            return new RunMetrics(0, null, null, null, null);
        }
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
