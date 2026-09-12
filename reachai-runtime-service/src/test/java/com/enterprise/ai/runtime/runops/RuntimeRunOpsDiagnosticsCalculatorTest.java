package com.enterprise.ai.runtime.runops;

import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDetailView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSpanView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSummaryView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuntimeRunOpsDiagnosticsCalculatorTest {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final RuntimeRunOpsDiagnosticsCalculator calculator = new RuntimeRunOpsDiagnosticsCalculator();

    @Test
    void countsContainingRunsOncePerVersionAndAttributesNestedFailureToItsWorkflow() {
        List<RuntimeRunOpsSpanView> repeatedWorkflow = List.of(
                workflowSpan("span-1", 21L, "SUCCESS"), workflowSpan("span-2", 21L, "SUCCESS"));
        var completed = detail(summary("trace-success", "COMPLETED", 100, 10, 1), repeatedWorkflow);
        var failed = detail(summary("trace-failed", "FAILED", 300, 20, 2),
                List.of(workflowSpan("span-3", 22L, "TIMEOUT")));

        var diagnostics = calculator.calculate(List.of(completed, failed));

        assertEquals(1, diagnostics.failureClusters().size());
        var failure = diagnostics.failureClusters().get(0);
        assertEquals("WORKFLOW", failure.versionType());
        assertEquals("workflow-1", failure.workflowId());
        assertEquals(22L, failure.workflowVersionId());
        assertNull(failure.agentId());
        assertEquals("STEP_TIMEOUT", failure.errorCode());
        assertEquals(List.of("trace-failed"), failure.traceIds());
        assertEquals(3, diagnostics.versionComparisons().size());
        var agent = diagnostics.versionComparisons().stream()
                .filter(row -> "AGENT".equals(row.versionType())).findFirst().orElseThrow();
        assertEquals(2, agent.runCount());
        assertEquals(0.5D, agent.successRate());
        var workflow = diagnostics.versionComparisons().stream()
                .filter(row -> Long.valueOf(21L).equals(row.workflowVersionId())).findFirst().orElseThrow();
        assertEquals(1, workflow.runCount());
        assertEquals(2, workflow.workflowCallCount());
        assertEquals(2, repeatedWorkflow.size());
    }

    @Test
    void excludesMissingTelemetryFromAveragesAndKeepsTimeoutsInFailureCounts() {
        var rows = List.of(
                detail(summary("success", "COMPLETED", 10, null, 1), List.of()),
                detail(summary("failed", "FAILED", null, 1, 2), List.of()),
                detail(summary("timeout", "TIMED_OUT", 90, 3, 3), List.of()));

        var diagnostics = calculator.calculate(rows);

        var version = diagnostics.versionComparisons().get(0);
        assertEquals(3, version.runCount());
        assertEquals(1, version.successCount());
        assertEquals(2, version.failureCount());
        assertEquals(1D / 3, version.successRate());
        assertEquals(50, version.avgLatencyMs());
        assertEquals(90, version.p95LatencyMs());
        assertEquals(2, version.avgTokenCost());
        assertEquals("timeout", version.latestTraceId());
        assertEquals("TIMED_OUT", diagnostics.failureClusters().get(0).errorCode());
        assertEquals(2, diagnostics.failureClusters().size());
    }

    private RuntimeRunOpsDetailView detail(RuntimeRunOpsSummaryView summary, List<RuntimeRunOpsSpanView> spans) {
        return new RuntimeRunOpsDetailView(summary, spans, List.of(), List.of(), null, List.of(), List.of());
    }

    private RuntimeRunOpsSummaryView summary(String traceId, String status, Integer latency,
                                             Integer tokens, int second) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("traceId", traceId);
        values.put("status", status);
        values.put("agentId", "agent-1");
        values.put("agentConfigVersionId", 11L);
        values.put("agentConfigVersion", 1);
        values.put("runtimeType", "AGENTSCOPE");
        values.put("latencyMs", latency);
        values.put("tokenCost", tokens);
        values.put("workflowCallCount", 2);
        values.put("startedAt", LocalDateTime.of(2026, 9, 6, 7, 0, second));
        return json.convertValue(values, RuntimeRunOpsSummaryView.class);
    }

    private RuntimeRunOpsSpanView workflowSpan(String spanId, Long versionId, String status) {
        return json.convertValue(Map.of(
                "spanId", spanId, "spanType", "WORKFLOW_TOOL", "status", status,
                "nodeId", "workflow-1", "toolName", "workflow.call",
                "errorCode", "TIMEOUT".equals(status) ? "STEP_TIMEOUT" : "",
                "metadata", Map.of("workflowId", "workflow-1", "workflowVersionId", versionId,
                        "workflowVersion", "version-" + versionId, "runtimeType", "LANGGRAPH4J")),
                RuntimeRunOpsSpanView.class);
    }
}
