package com.enterprise.ai.runtime.runops;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDetailView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsComparisonView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDiagnosticsView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSummaryView;
import com.enterprise.ai.runtime.trace.RuntimeAgentTraceSpanEntity;
import com.enterprise.ai.runtime.trace.RuntimeAgentTraceSpanMapper;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogEntity;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeRunOpsQueryServiceTest {

    @Test
    void detailAggregatesToolLogsTraceSpansAndGuardDecisions() {
        RuntimeToolCallLogMapper toolLogMapper = mock(RuntimeToolCallLogMapper.class);
        RuntimeAgentTraceSpanMapper spanMapper = mock(RuntimeAgentTraceSpanMapper.class);
        RuntimeGuardDecisionLogMapper guardMapper = mock(RuntimeGuardDecisionLogMapper.class);
        RuntimeRunOpsQueryService service = new RuntimeRunOpsQueryService(
                toolLogMapper, spanMapper, guardMapper, new ObjectMapper());
        when(toolLogMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeToolCallLogEntity>>any()))
                .thenReturn(List.of(toolLog(2L, "trace-1", false)));
        when(spanMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeAgentTraceSpanEntity>>any()))
                .thenReturn(List.of(span(1L, "trace-1", "ERROR")));
        when(guardMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeGuardDecisionLogEntity>>any()))
                .thenReturn(List.of(guardDecision(3L, "trace-1", "DENY")));

        RuntimeRunOpsDetailView detail = service.detail("trace-1");

        assertEquals("trace-1", detail.summary().traceId());
        assertEquals("ERROR", detail.summary().status());
        assertEquals("Order Agent", detail.summary().agentName());
        assertEquals("LANGGRAPH4J", detail.summary().runtimeType());
        assertEquals("wf-1", detail.summary().workflowId());
        assertEquals(12, detail.summary().tokenCost());
        assertEquals(3, detail.summary().errorCount());
        assertEquals(1, detail.spans().size());
        assertEquals("node_1", detail.spans().get(0).nodeId());
        assertEquals(1, detail.toolCalls().size());
        assertEquals("order.lookup", detail.toolCalls().get(0).toolName());
        assertEquals(1, detail.guardDecisions().size());
        assertEquals("TOOL_ACL", detail.guardDecisions().get(0).decisionType());
    }

    @Test
    void detailRejectsMissingTrace() {
        RuntimeToolCallLogMapper toolLogMapper = mock(RuntimeToolCallLogMapper.class);
        RuntimeAgentTraceSpanMapper spanMapper = mock(RuntimeAgentTraceSpanMapper.class);
        RuntimeGuardDecisionLogMapper guardMapper = mock(RuntimeGuardDecisionLogMapper.class);
        RuntimeRunOpsQueryService service = new RuntimeRunOpsQueryService(
                toolLogMapper, spanMapper, guardMapper, new ObjectMapper());
        when(toolLogMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeToolCallLogEntity>>any())).thenReturn(List.of());
        when(spanMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeAgentTraceSpanEntity>>any())).thenReturn(List.of());
        when(guardMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeGuardDecisionLogEntity>>any())).thenReturn(List.of());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.detail("missing"));

        assertEquals("RunOps 运行记录不存在: missing", ex.getMessage());
    }

    @Test
    void recentReturnsSummariesGroupedByTrace() {
        RuntimeToolCallLogMapper toolLogMapper = mock(RuntimeToolCallLogMapper.class);
        RuntimeAgentTraceSpanMapper spanMapper = mock(RuntimeAgentTraceSpanMapper.class);
        RuntimeGuardDecisionLogMapper guardMapper = mock(RuntimeGuardDecisionLogMapper.class);
        RuntimeRunOpsQueryService service = new RuntimeRunOpsQueryService(
                toolLogMapper, spanMapper, guardMapper, new ObjectMapper());
        when(toolLogMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeToolCallLogEntity>>any()))
                .thenReturn(List.of(
                        toolLog(3L, "trace-b", true),
                        toolLog(2L, "trace-a", false),
                        toolLog(1L, "trace-a", true)
                ));
        when(spanMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeAgentTraceSpanEntity>>any()))
                .thenReturn(List.of());

        List<RuntimeRunOpsSummaryView> summaries = service.recent("user-1", 20, 7);

        assertEquals(2, summaries.size());
        assertEquals("trace-b", summaries.get(0).traceId());
        assertEquals("SUCCESS", summaries.get(0).status());
        assertEquals("trace-a", summaries.get(1).traceId());
        assertEquals("ERROR", summaries.get(1).status());
        assertEquals(2, summaries.get(1).toolCallCount());
        assertEquals(1, summaries.get(1).errorCount());
    }

    @Test
    void compareReturnsSummarySpanToolAndGuardDiffs() {
        RuntimeToolCallLogMapper toolLogMapper = mock(RuntimeToolCallLogMapper.class);
        RuntimeAgentTraceSpanMapper spanMapper = mock(RuntimeAgentTraceSpanMapper.class);
        RuntimeGuardDecisionLogMapper guardMapper = mock(RuntimeGuardDecisionLogMapper.class);
        RuntimeRunOpsQueryService service = new RuntimeRunOpsQueryService(
                toolLogMapper, spanMapper, guardMapper, new ObjectMapper());
        when(toolLogMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeToolCallLogEntity>>any()))
                .thenReturn(List.of(toolLog(1L, "baseline", true)))
                .thenReturn(List.of(toolLog(2L, "candidate", false)));
        when(spanMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeAgentTraceSpanEntity>>any()))
                .thenReturn(List.of(span(1L, "baseline", "SUCCESS")))
                .thenReturn(List.of(span(2L, "candidate", "ERROR")));
        when(guardMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeGuardDecisionLogEntity>>any()))
                .thenReturn(List.of(guardDecision(1L, "baseline", "ALLOW")))
                .thenReturn(List.of(guardDecision(2L, "candidate", "DENY")));

        RuntimeRunOpsComparisonView comparison = service.compare("baseline", "candidate");

        assertEquals("baseline", comparison.baseline().traceId());
        assertEquals("candidate", comparison.candidate().traceId());
        assertEquals("status", comparison.summaryDiffs().get(0).field());
        assertEquals("SUCCESS", comparison.summaryDiffs().get(0).baseline());
        assertEquals("ERROR", comparison.summaryDiffs().get(0).candidate());
        assertEquals(true, comparison.summaryDiffs().get(0).changed());
        assertEquals("node_1", comparison.spanDiffs().get(0).key());
        assertEquals(true, comparison.spanDiffs().get(0).changed());
        assertEquals("order.lookup", comparison.toolDiffs().get(0).key());
        assertEquals(true, comparison.toolDiffs().get(0).changed());
        assertEquals("TOOL_ACL|TOOL|order.lookup", comparison.guardDiffs().get(0).key());
        assertEquals(true, comparison.guardDiffs().get(0).changed());
    }

    @Test
    void diagnosticsClustersFailuresAndComparesVersionsFromRecentRuns() {
        RuntimeToolCallLogMapper toolLogMapper = mock(RuntimeToolCallLogMapper.class);
        RuntimeAgentTraceSpanMapper spanMapper = mock(RuntimeAgentTraceSpanMapper.class);
        RuntimeGuardDecisionLogMapper guardMapper = mock(RuntimeGuardDecisionLogMapper.class);
        RuntimeRunOpsQueryService service = new RuntimeRunOpsQueryService(
                toolLogMapper, spanMapper, guardMapper, new ObjectMapper());
        when(toolLogMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeToolCallLogEntity>>any()))
                .thenReturn(List.of(
                        toolLog(2L, "trace-b", false),
                        toolLog(1L, "trace-a", true)))
                .thenReturn(List.of(toolLog(2L, "trace-b", false)))
                .thenReturn(List.of(toolLog(1L, "trace-a", true)));
        when(spanMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeAgentTraceSpanEntity>>any()))
                .thenReturn(List.of(span(2L, "trace-b", "ERROR")))
                .thenReturn(List.of(span(1L, "trace-a", "SUCCESS")));
        when(guardMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeGuardDecisionLogEntity>>any()))
                .thenReturn(List.of(guardDecision(2L, "trace-b", "DENY")))
                .thenReturn(List.of(guardDecision(1L, "trace-a", "ALLOW")));

        RuntimeRunOpsDiagnosticsView diagnostics = service.diagnostics("user-1", 20, 7);

        assertEquals(1, diagnostics.failureClusters().size());
        assertEquals("NODE_FAILED", diagnostics.failureClusters().get(0).errorType());
        assertEquals("trace-b", diagnostics.failureClusters().get(0).sampleTraceId());
        assertEquals(1, diagnostics.failureClusters().get(0).count());
        assertEquals(1, diagnostics.versionComparisons().size());
        assertEquals(2, diagnostics.versionComparisons().get(0).runCount());
        assertEquals(1, diagnostics.versionComparisons().get(0).successCount());
        assertEquals(1, diagnostics.versionComparisons().get(0).failureCount());
        assertEquals(0.5D, diagnostics.versionComparisons().get(0).successRate());
    }

    private RuntimeToolCallLogEntity toolLog(Long id, String traceId, boolean success) {
        RuntimeToolCallLogEntity entity = new RuntimeToolCallLogEntity();
        entity.setId(id);
        entity.setTraceId(traceId);
        entity.setSessionId("session-1");
        entity.setUserId("user-1");
        entity.setAgentName("Order Agent");
        entity.setIntentType("ORDER_QA");
        entity.setProjectCode("orders");
        entity.setToolName("order.lookup");
        entity.setSuccess(success);
        entity.setArgsJson("{}");
        entity.setResultSummary(success ? "ok" : "failed");
        entity.setErrorCode(success ? null : "TOOL_FAILED");
        entity.setElapsedMs(30);
        entity.setTokenCost(2);
        entity.setCreateTime(LocalDateTime.parse("2026-06-29T12:00:0" + id));
        return entity;
    }

    private RuntimeAgentTraceSpanEntity span(Long id, String traceId, String status) {
        RuntimeAgentTraceSpanEntity entity = new RuntimeAgentTraceSpanEntity();
        entity.setId(id);
        entity.setTraceId(traceId);
        entity.setSpanId("span-1");
        entity.setParentSpanId("root");
        entity.setSpanType("NODE");
        entity.setRuntimeType("LANGGRAPH4J");
        entity.setAgentId("agent-1");
        entity.setAgentName("Order Agent");
        entity.setNodeId("node_1");
        entity.setToolName("order.lookup");
        entity.setStatus(status);
        entity.setInputSummary("input");
        entity.setOutputSummary("output");
        entity.setMetadataJson("""
                {"workflowId":"wf-1","workflowKeySlug":"orders","sourceType":"WORKFLOW_VERSION"}
                """);
        entity.setErrorCode("ERROR".equals(status) ? "NODE_FAILED" : null);
        entity.setErrorMessage("ERROR".equals(status) ? "node failed" : null);
        entity.setLatencyMs(50);
        entity.setTokenCost(10);
        entity.setStartedAt(LocalDateTime.parse("2026-06-29T12:00:00"));
        entity.setEndedAt(LocalDateTime.parse("2026-06-29T12:00:03"));
        entity.setCreatedAt(LocalDateTime.parse("2026-06-29T12:00:00"));
        return entity;
    }

    private RuntimeGuardDecisionLogEntity guardDecision(Long id, String traceId, String decision) {
        RuntimeGuardDecisionLogEntity entity = new RuntimeGuardDecisionLogEntity();
        entity.setId(id);
        entity.setTraceId(traceId);
        entity.setDecisionType("TOOL_ACL");
        entity.setTargetKind("TOOL");
        entity.setTargetName("order.lookup");
        entity.setDecision(decision);
        entity.setReason("not allowed");
        entity.setMetadataJson("{\"policy\":\"strict\"}");
        entity.setCreatedAt(LocalDateTime.parse("2026-06-29T12:00:02"));
        return entity;
    }
}
