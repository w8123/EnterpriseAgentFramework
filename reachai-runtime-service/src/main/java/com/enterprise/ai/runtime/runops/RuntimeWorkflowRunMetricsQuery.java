package com.enterprise.ai.runtime.runops;

import java.time.LocalDateTime;

/** Workflow 近 30 天调用统计；调用方不接触运行事实表或 Trace 表。 */
public interface RuntimeWorkflowRunMetricsQuery {

    Metrics recent(String workflowId);

    record Metrics(
            int callCount,
            Double successRate,
            LocalDateTime latestCallAt,
            String latestStatus,
            String latestTraceId) {

        public static Metrics empty() {
            return new Metrics(0, null, null, null, null);
        }
    }
}
