package com.enterprise.ai.runtime.runops;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsComparisonView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDetailView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDiagnosticsView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSummaryView;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogEntity;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanEntity;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceQueryService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSummaryView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeRunOpsQueryServiceTest {

    private final RuntimeRunMapper runMapper = mock(RuntimeRunMapper.class);
    private final RuntimeTraceSpanMapper spanMapper = mock(RuntimeTraceSpanMapper.class);
    private final RuntimeToolCallLogMapper toolMapper = mock(RuntimeToolCallLogMapper.class);
    private final RuntimeGuardDecisionLogMapper guardMapper = mock(RuntimeGuardDecisionLogMapper.class);
    private final RuntimeRunOpsQueryService service = new RuntimeRunOpsQueryService(
            runMapper, new RuntimeTraceQueryService(toolMapper, spanMapper, new ObjectMapper()), guardMapper, new ObjectMapper());

    @Test
    void recentIncludesDirectAnswerSupervisorRunWithNoWorkflowOrToolCalls() {
        RuntimeRunEntity run = supervisorRun("trace-direct", "COMPLETED");
        run.setWorkflowCallCount(0);
        run.setToolCallCount(0);
        when(runMapper.selectList(any())).thenReturn(List.of(run));

        List<RuntimeRunOpsSummaryView> summaries = service.recent(
                "qmssmp", null, "AGENT", "EMBED", "agent-1", "user-1", 20, 7);

        assertEquals(1, summaries.size());
        assertEquals("trace-direct", summaries.get(0).traceId());
        assertEquals("AGENT", summaries.get(0).runType());
        assertEquals(0, summaries.get(0).workflowCallCount());
        assertEquals(0, summaries.get(0).toolCallCount());
        verify(toolMapper, never()).selectList(any());
        verify(spanMapper, never()).selectList(any());
    }

    @Test
    void recentPushesProjectStatusRunEntryAgentAndUserFiltersIntoRootRunQuery() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "runops-filter-test"),
                RuntimeRunEntity.class);
        when(runMapper.selectList(any())).thenReturn(List.of());

        service.recent("qmssmp", "COMPLETED", "agent", "debug", "agent-1", "user-1", 25, 14);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(runMapper).selectList(captor.capture());
        LambdaQueryWrapper<?> wrapper = (LambdaQueryWrapper<?>) captor.getValue();
        String sql = wrapper.getSqlSegment().toLowerCase();
        assertTrue(sql.contains("project_code"));
        assertTrue(sql.contains("status"));
        assertTrue(sql.contains("run_type"));
        assertTrue(sql.contains("entry_type"));
        assertTrue(sql.contains("agent_id"));
        assertTrue(sql.contains("user_id"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("qmssmp"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("COMPLETED"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("AGENT"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("DEBUG"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("agent-1"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("user-1"));
    }

    @Test
    void detailAggregatesRootRunSpansToolsGuardsAndSemanticExecutionPath() {
        RuntimeRunEntity run = supervisorRun("trace-1", "FAILED");
        run.setErrorCode("WORKFLOW_FAILED");
        run.setErrorMessage("workflow failed");
        run.setWorkflowCallCount(1);
        run.setToolCallCount(1);
        run.setGuardDenyCount(1);
        run.setSnapshotJson("{\"agentConfigVersionId\":11}");
        when(runMapper.selectOne(any())).thenReturn(run);
        when(spanMapper.selectList(any())).thenReturn(List.of(
                span(1L, "trace-1", "supervisor", null, "SUPERVISOR", "SUCCESS"),
                span(2L, "trace-1", "plan", "supervisor", "PLAN", "SUCCESS"),
                span(3L, "trace-1", "workflow", "plan", "WORKFLOW_TOOL", "FAILED")));
        when(toolMapper.selectList(any())).thenReturn(List.of(tool("trace-1", false)));
        when(guardMapper.selectList(any())).thenReturn(List.of(guard("trace-1", "DENY")));

        RuntimeRunOpsDetailView detail = service.detail("trace-1");

        assertEquals("trace-1", detail.summary().traceId());
        assertEquals("WORKFLOW_FAILED", detail.summary().errorCode());
        assertEquals(3, detail.spans().size());
        assertEquals(1, detail.toolCalls().size());
        assertEquals(1, detail.guardDecisions().size());
        assertEquals(3, detail.executionPath().size());
        assertEquals(0, detail.executionPath().get(0).depth());
        assertEquals(1, detail.executionPath().get(1).depth());
        assertEquals(2, detail.executionPath().get(2).depth());
        assertEquals("WORKFLOW_TOOL", detail.executionPath().get(2).spanType());
        assertEquals("input", detail.executionPath().get(2).fromNodeId());
        assertEquals("query", detail.executionPath().get(2).toNodeId());
        assertEquals("success", detail.executionPath().get(2).condition());
        assertEquals("next", detail.executionPath().get(2).route());
        assertEquals("approval-1", detail.executionPath().get(2).interactionId());
        assertEquals(11L, detail.snapshot().agentConfigVersionId());
        assertFalse(detail.repairHints().isEmpty());
    }

    @Test
    void detailRejectsChildEventsWithoutRuntimeRunRoot() {
        when(runMapper.selectOne(any())).thenReturn(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.detail("orphan-trace"));

        assertEquals("RunOps 运行记录不存在: orphan-trace", ex.getMessage());
        verify(spanMapper, never()).selectList(any());
        verify(toolMapper, never()).selectList(any());
    }

    @Test
    void compareUsesRootRunFieldsAndChildEvidence() {
        RuntimeRunEntity baseline = supervisorRun("baseline", "COMPLETED");
        RuntimeRunEntity candidate = supervisorRun("candidate", "FAILED");
        candidate.setErrorCode("MODEL_FAILED");
        when(runMapper.selectOne(any())).thenReturn(baseline, candidate);
        when(spanMapper.selectList(any())).thenReturn(List.of(), List.of());
        when(toolMapper.selectList(any())).thenReturn(List.of(), List.of());
        when(guardMapper.selectList(any())).thenReturn(List.of(), List.of());

        RuntimeRunOpsComparisonView comparison = service.compare("baseline", "candidate");

        assertEquals("baseline", comparison.baseline().traceId());
        assertEquals("candidate", comparison.candidate().traceId());
        assertTrue(comparison.summaryDiffs().stream()
                .anyMatch(diff -> "status".equals(diff.field()) && diff.changed()));
        assertTrue(comparison.summaryDiffs().stream()
                .anyMatch(diff -> "errorCode".equals(diff.field()) && diff.changed()));
    }

    @Test
    void diagnosticsGroupsAgentRunsByPublishedConfigVersionWithoutFallbackMetrics() {
        RuntimeRunEntity success = supervisorRun("trace-success", "COMPLETED");
        RuntimeRunEntity failed = supervisorRun("trace-failed", "FAILED");
        failed.setId(2L);
        failed.setErrorCode("MODEL_FAILED");
        failed.setErrorMessage("model unavailable");
        when(runMapper.selectList(any())).thenReturn(List.of(success, failed));
        when(runMapper.selectOne(any())).thenReturn(success, failed);
        when(spanMapper.selectList(any())).thenReturn(List.of());
        when(toolMapper.selectList(any())).thenReturn(List.of());
        when(guardMapper.selectList(any())).thenReturn(List.of());

        RuntimeRunOpsDiagnosticsView diagnostics = service.diagnostics(
                "qmssmp", null, "AGENT", null, "agent-1", null, 20, 7);

        assertEquals(1, diagnostics.failureClusters().size());
        assertEquals("AGENT", diagnostics.failureClusters().get(0).versionType());
        assertEquals(11L, diagnostics.failureClusters().get(0).agentConfigVersionId());
        assertEquals("MODEL_FAILED", diagnostics.failureClusters().get(0).errorCode());
        assertEquals(1, diagnostics.versionComparisons().size());
        assertEquals(2, diagnostics.versionComparisons().get(0).runCount());
        assertEquals(1, diagnostics.versionComparisons().get(0).successCount());
        assertEquals(1, diagnostics.versionComparisons().get(0).failureCount());
        assertEquals(0.5D, diagnostics.versionComparisons().get(0).successRate());
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAITING_APPROVAL", "BUSINESS_TERMINAL", "TIMEOUT"})
    void diagnosticsAcceptsSpanStatesProducedBySupervisor(String spanStatus) {
        RuntimeRunEntity root = supervisorRun("trace-native-status",
                "WAITING_APPROVAL".equals(spanStatus) ? "SUSPENDED" : "COMPLETED");
        when(runMapper.selectList(any())).thenReturn(List.of(root));
        when(runMapper.selectOne(any())).thenReturn(root);
        when(spanMapper.selectList(any())).thenReturn(List.of(span(1L, root.getTraceId(),
                "native-span", "root", "WORKFLOW_TOOL", spanStatus)));
        when(toolMapper.selectList(any())).thenReturn(List.of());
        when(guardMapper.selectList(any())).thenReturn(List.of());

        RuntimeRunOpsDiagnosticsView diagnostics = service.diagnostics(
                "qmssmp", null, "AGENT", null, "agent-1", null, 20, 7);

        assertEquals("TIMEOUT".equals(spanStatus) ? 1 : 0, diagnostics.failureClusters().size());
        assertEquals(spanStatus, service.detail(root.getTraceId()).spans().get(0).status());
    }

    private RuntimeRunEntity supervisorRun(String traceId, String status) {
        RuntimeRunEntity run = new RuntimeRunEntity();
        run.setId(1L);
        run.setTraceId(traceId);
        run.setRunType("AGENT");
        run.setEntryType("EMBED");
        run.setStatus(status);
        run.setProjectCode("qmssmp");
        run.setTenantId("tenant-1");
        run.setSessionId("session-1");
        run.setUserId("user-1");
        run.setAgentId("agent-1");
        run.setAgentKeySlug("team-assistant");
        run.setAgentName("班组助手");
        run.setAgentConfigVersionId(11L);
        run.setAgentConfigVersion(3);
        run.setRuntimeType("AGENTSCOPE");
        run.setInputSummary("查询班组信息");
        run.setOutputSummary("回答");
        run.setLatencyMs(1200);
        run.setTokenCost(42);
        run.setPlanCount(1);
        run.setReplanCount(0);
        run.setWorkflowCallCount(0);
        run.setToolCallCount(0);
        run.setGuardDenyCount(0);
        run.setApprovalCount(0);
        run.setMetadataJson("{\"sourceType\":\"AGENT_SUPERVISOR\"}");
        run.setStartedAt(LocalDateTime.parse("2026-07-14T08:00:00"));
        run.setEndedAt(LocalDateTime.parse("2026-07-14T08:00:01"));
        return run;
    }

    @Test
    void listsRecentTracesFromRootRunsWithMostRecentFirst() {
        when(runMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeRunEntity>>any())).thenReturn(List.of(
                run(2L, "trace-b", "COMPLETED", 1,
                        LocalDateTime.parse("2026-06-29T12:02:00"), LocalDateTime.parse("2026-06-29T12:02:00")),
                run(1L, "trace-a", "FAILED", 2,
                        LocalDateTime.parse("2026-06-29T12:00:00"), LocalDateTime.parse("2026-06-29T12:01:00"))));

        List<RuntimeTraceSummaryView> summaries = service.listRecentTraces("user-1", 20, 7);

        assertEquals(2, summaries.size());
        assertEquals("trace-b", summaries.get(0).traceId());
        assertEquals(1, summaries.get(0).callCount());
        assertEquals(1L, summaries.get(0).successCount());
        assertEquals("trace-a", summaries.get(1).traceId());
        assertEquals(2, summaries.get(1).callCount());
        assertEquals(0L, summaries.get(1).successCount());
        assertEquals(LocalDateTime.parse("2026-06-29T12:00:00"), summaries.get(1).startedAt());
        assertEquals(LocalDateTime.parse("2026-06-29T12:01:00"), summaries.get(1).endedAt());
    }

    private RuntimeRunEntity run(Long id,
                                 String traceId,
                                 String status,
                                 int toolCallCount,
                                 LocalDateTime startedAt,
                                 LocalDateTime endedAt) {
        RuntimeRunEntity run = new RuntimeRunEntity();
        run.setId(id);
        run.setTraceId(traceId);
        run.setStatus(status);
        run.setSessionId("session-1");
        run.setUserId("user-1");
        run.setAgentName("Order Agent");
        run.setEntryType("API");
        run.setToolCallCount(toolCallCount);
        run.setStartedAt(startedAt);
        run.setEndedAt(endedAt);
        return run;
    }

    private RuntimeTraceSpanEntity span(Long id,
                                        String traceId,
                                        String spanId,
                                        String parentSpanId,
                                        String spanType,
                                        String status) {
        RuntimeTraceSpanEntity span = new RuntimeTraceSpanEntity();
        span.setId(id);
        span.setTraceId(traceId);
        span.setSpanId(spanId);
        span.setParentSpanId(parentSpanId);
        span.setSpanType(spanType);
        span.setRuntimeType("AGENTSCOPE");
        span.setAgentId("agent-1");
        span.setAgentName("班组助手");
        span.setNodeId("WORKFLOW_TOOL".equals(spanType) ? "workflow-call" : null);
        span.setToolName("WORKFLOW_TOOL".equals(spanType) ? "team.query" : null);
        span.setStatus(status);
        span.setMetadataJson("WORKFLOW_TOOL".equals(spanType)
                ? "{\"workflowId\":\"wf-1\",\"workflowName\":\"班组查询\",\"workflowVersionId\":21,"
                + "\"step\":{\"fromNodeId\":\"input\",\"toNodeId\":\"query\","
                + "\"condition\":\"success\",\"route\":\"next\",\"interactionId\":\"approval-1\"}}"
                : "{}");
        span.setErrorCode("FAILED".equals(status) ? "WORKFLOW_FAILED" : null);
        span.setLatencyMs(100);
        span.setTokenCost(3);
        span.setStartedAt(LocalDateTime.parse("2026-07-14T08:00:0" + id));
        span.setEndedAt(LocalDateTime.parse("2026-07-14T08:00:0" + id));
        return span;
    }

    private RuntimeToolCallLogEntity tool(String traceId, boolean success) {
        RuntimeToolCallLogEntity tool = new RuntimeToolCallLogEntity();
        tool.setId(1L);
        tool.setTraceId(traceId);
        tool.setToolName("team.query");
        tool.setSuccess(success);
        tool.setErrorCode(success ? null : "TOOL_FAILED");
        tool.setCreateTime(LocalDateTime.parse("2026-07-14T08:00:03"));
        return tool;
    }

    private RuntimeGuardDecisionLogEntity guard(String traceId, String decision) {
        RuntimeGuardDecisionLogEntity guard = new RuntimeGuardDecisionLogEntity();
        guard.setId(1L);
        guard.setTraceId(traceId);
        guard.setDecisionType("TOOL_ACL");
        guard.setTargetKind("TOOL");
        guard.setTargetName("team.query");
        guard.setDecision(decision);
        guard.setReason("not allowed");
        guard.setMetadataJson("{}");
        guard.setCreatedAt(LocalDateTime.parse("2026-07-14T08:00:03"));
        return guard;
    }
}
