package com.enterprise.ai.runtime.runops;

import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionService;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDetailView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSpanView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSummaryView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsToolCallView;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeRunOpsReplayServiceTest {

    private final RuntimeRunOpsQueryService queryService = mock(RuntimeRunOpsQueryService.class);
    private final RuntimeAgentExecutionService executionService = mock(RuntimeAgentExecutionService.class);
    private final RuntimeRunOpsReplayService service = new RuntimeRunOpsReplayService(queryService, executionService);

    @Test
    void replayRestoresTraceInputAndRunsThroughRuntimeAgentExecution() {
        when(queryService.detail("trace-1")).thenReturn(detail("trace-1", "agent-entry", "hello from trace"));
        when(executionService.execute(org.mockito.ArgumentMatchers.any(), eq(true))).thenReturn(Map.of(
                "success", true,
                "answer", "replayed answer",
                "metadata", Map.of("traceId", "trace-replay", "code", "RUNTIME_GRAPH_EXECUTED")));

        RuntimeRunOpsReplayService.ReplayResult result = service.replay("trace-1",
                new RuntimeRunOpsReplayService.ReplayRequest(
                        null,
                        "session-replay",
                        "user-replay",
                        List.of("operator"),
                        false));

        assertEquals("trace-1", result.originalTraceId());
        assertEquals("trace-replay", result.replayTraceId());
        assertEquals("session-replay", result.sessionId());
        assertEquals("user-replay", result.userId());
        assertEquals("agent-entry", result.agentId());
        assertEquals("hello from trace", result.message());
        assertEquals(true, result.success());
        assertEquals("replayed answer", result.answer());
        assertEquals("wf-1", result.workflowId());
        assertEquals("RUNTIME_AGENT_EXECUTION", result.executionPath());

        verify(executionService).execute(org.mockito.ArgumentMatchers.argThat(body ->
                "agent-entry".equals(body.get("agentDefinitionId"))
                        && "hello from trace".equals(body.get("message"))
                        && "session-replay".equals(body.get("sessionId"))
                        && "user-replay".equals(body.get("userId"))
                        && Boolean.TRUE.equals(((Map<?, ?>) body.get("metadata")).get("replay"))
                        && Boolean.FALSE.equals(((Map<?, ?>) body.get("metadata")).get("replayUseSnapshot"))), eq(true));
    }

    @Test
    void replayRequiresRecoverableMessageOrOverride() {
        when(queryService.detail("trace-1")).thenReturn(detail("trace-1", "agent-entry", null));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.replay("trace-1", new RuntimeRunOpsReplayService.ReplayRequest(
                        null, null, null, List.of(), true)));

        assertEquals("Unable to restore replay input from trace; provide messageOverride", ex.getMessage());
    }

    private RuntimeRunOpsDetailView detail(String traceId, String entryAgentId, String inputSummary) {
        RuntimeRunOpsSummaryView summary = new RuntimeRunOpsSummaryView(
                traceId,
                "SUCCESS",
                "agent-current",
                "Order Agent",
                "v1",
                11L,
                "LANGGRAPH4J",
                null,
                null,
                "session-1",
                "user-1",
                "ORDER_QA",
                LocalDateTime.parse("2026-06-29T12:00:00"),
                LocalDateTime.parse("2026-06-29T12:00:03"),
                3000,
                10,
                2,
                1,
                0,
                false,
                null,
                null,
                "wf-1",
                "orders-workflow",
                "wv1",
                21L,
                entryAgentId,
                "orders-agent",
                "WORKFLOW",
                "wf-1",
                Map.of("entryAgentId", entryAgentId, "workflowId", "wf-1"));
        RuntimeRunOpsSpanView span = new RuntimeRunOpsSpanView(
                1L,
                "span-1",
                null,
                "AGENT_RUN",
                "LANGGRAPH4J",
                null,
                null,
                "SUCCESS",
                inputSummary,
                "ok",
                Map.of(),
                null,
                null,
                3000,
                10,
                LocalDateTime.parse("2026-06-29T12:00:00"),
                LocalDateTime.parse("2026-06-29T12:00:03"));
        RuntimeRunOpsToolCallView tool = new RuntimeRunOpsToolCallView(
                1L,
                "orders.lookup",
                "Order Agent",
                "session-1",
                "user-1",
                "ORDER_QA",
                "orders",
                true,
                inputSummary == null ? "{}" : "{\"message\":\"tool message\"}",
                "ok",
                null,
                30,
                1,
                LocalDateTime.parse("2026-06-29T12:00:01"));
        return new RuntimeRunOpsDetailView(summary, List.of(span), List.of(tool), List.of(), null, List.of(), List.of());
    }
}
