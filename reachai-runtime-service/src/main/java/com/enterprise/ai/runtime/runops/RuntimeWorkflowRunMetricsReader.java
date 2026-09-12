package com.enterprise.ai.runtime.runops;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/** RunOps 只读统计模型：合并根运行与 Workflow-as-Tool Span，不读取业务请求内容。 */
@Component
@RequiredArgsConstructor
public class RuntimeWorkflowRunMetricsReader implements RuntimeWorkflowRunMetricsQuery {

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(readOnly = true)
    public Metrics recent(String workflowId) {
        if (!StringUtils.hasText(workflowId)) return Metrics.empty();
        workflowId = workflowId.trim();
        LocalDateTime recentSince = LocalDateTime.now().minusDays(30);
        List<Metrics> metrics = jdbcTemplate.query("""
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
            return Metrics.empty();
        }
        Metrics aggregate = metrics.get(0);
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
                : new Metrics(
                        aggregate.callCount(),
                        aggregate.successRate(),
                        aggregate.latestCallAt(),
                        latest.get(0).status(),
                        latest.get(0).traceId());
    }

    private Metrics metricsRow(ResultSet rs) throws SQLException {
        int count = rs.getInt("call_count");
        int success = rs.getInt("success_count");
        LocalDateTime latest = rs.getObject("latest_call_at", LocalDateTime.class);
        return new Metrics(
                count,
                count == 0 ? null : Math.round((success * 10000.0 / count)) / 100.0,
                latest,
                null,
                null);
    }

    private record RunLatest(String traceId, String status) {
    }
}
