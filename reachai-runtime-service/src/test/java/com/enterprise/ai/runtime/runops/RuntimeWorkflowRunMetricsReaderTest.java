package com.enterprise.ai.runtime.runops;

import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeWorkflowRunMetricsReaderTest {

    @Test
    void countsOnlyRootAndNestedWorkflowCallsInTheRecentWindow() throws Exception {
        try (var database = new RuntimeQueryTestDatabase(List.of("runtime_run", "runtime_trace_span"))) {
            var jdbc = database.jdbc();
            var reader = new RuntimeWorkflowRunMetricsReader(jdbc);
            LocalDateTime now = LocalDateTime.now().withNano(0);
            jdbc.update("""
                    INSERT INTO runtime_run(trace_id, run_type, entry_type, workflow_id, status, started_at)
                    VALUES ('trace-root', 'WORKFLOW', 'API', 'wf-1', 'COMPLETED', ?),
                           ('trace-expired', 'WORKFLOW', 'API', 'wf-1', 'FAILED', ?),
                           ('trace-other', 'WORKFLOW', 'API', 'wf-other', 'COMPLETED', ?)
                    """, now.minusHours(2), now.minusDays(31), now.plusHours(1));
            jdbc.update("""
                    INSERT INTO runtime_trace_span(trace_id, span_id, span_type, node_id, status, started_at)
                    VALUES ('trace-nested', 'nested', 'WORKFLOW_TOOL', 'wf-1', 'SUCCESS', ?),
                           ('trace-waiting', 'waiting', 'WORKFLOW_TOOL', 'wf-1', 'WAITING_USER', ?),
                           ('trace-node', 'node', 'GRAPH_NODE', 'wf-1', 'SUCCESS', ?)
                    """, now.minusHours(1), now, now.plusHours(1));

            var metrics = reader.recent(" wf-1 ");

            assertEquals(3, metrics.callCount());
            assertEquals(66.67, metrics.successRate());
            assertEquals(now, metrics.latestCallAt());
            assertEquals("WAITING_USER", metrics.latestStatus());
            assertEquals("trace-waiting", metrics.latestTraceId());
        }
    }

    @Test
    void oldOrAbsentHistoryDoesNotSupplyARecentTrace() throws Exception {
        try (var database = new RuntimeQueryTestDatabase(List.of("runtime_run", "runtime_trace_span"))) {
            database.jdbc().update("""
                    INSERT INTO runtime_run(trace_id, run_type, entry_type, workflow_id, status, started_at)
                    VALUES ('trace-old', 'WORKFLOW', 'API', 'wf-1', 'COMPLETED', ?)
                    """, LocalDateTime.now().minusDays(31));
            var reader = new RuntimeWorkflowRunMetricsReader(database.jdbc());
            assertEquals(RuntimeWorkflowRunMetricsQuery.Metrics.empty(), reader.recent("wf-1"));
            assertEquals(RuntimeWorkflowRunMetricsQuery.Metrics.empty(), reader.recent("absent"));
            assertEquals(RuntimeWorkflowRunMetricsQuery.Metrics.empty(), reader.recent(" "));
            assertEquals(RuntimeWorkflowRunMetricsQuery.Metrics.empty(), reader.recent(null));
        }
    }
}
