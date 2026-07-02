package com.enterprise.ai.runtime.trace;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeTraceQueryServiceTest {

    @Test
    void mergesToolLogsAndTraceSpansIntoSortedTraceNodes() {
        RuntimeToolCallLogMapper toolLogMapper = mock(RuntimeToolCallLogMapper.class);
        RuntimeAgentTraceSpanMapper spanMapper = mock(RuntimeAgentTraceSpanMapper.class);
        RuntimeTraceQueryService service = new RuntimeTraceQueryService(toolLogMapper, spanMapper, new ObjectMapper());
        when(toolLogMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeToolCallLogEntity>>any())).thenReturn(List.of(toolLog(
                20L,
                "trace-1",
                "order.lookup",
                "{\"orderId\":\"A-1\"}",
                "lookup ok",
                true,
                "[{\"docId\":\"doc-1\",\"score\":0.91}]",
                LocalDateTime.parse("2026-06-29T12:00:02")
        )));
        when(spanMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeAgentTraceSpanEntity>>any())).thenReturn(List.of(span(
                10L,
                "trace-1",
                "llm",
                "LLM",
                "llm_1",
                "gpt-4o-mini",
                "question",
                "answer",
                "SUCCESS",
                LocalDateTime.parse("2026-06-29T12:00:01")
        )));

        RuntimeTraceDetailView detail = service.getTraceDetail("trace-1").orElseThrow();

        assertEquals("trace-1", detail.traceId());
        assertEquals(2, detail.nodes().size());
        assertEquals("runtime_agent_trace_span", detail.nodes().get(0).source());
        assertEquals("span:llm_1", detail.nodes().get(0).toolName());
        assertEquals("runtime_tool_call_log", detail.nodes().get(1).source());
        assertEquals("order.lookup", detail.nodes().get(1).toolName());
        assertEquals(List.of(Map.of("docId", "doc-1", "score", 0.91D)),
                detail.nodes().get(1).retrievalCandidates());
    }

    @Test
    void returnsEmptyWhenTraceHasNoLogsOrSpans() {
        RuntimeToolCallLogMapper toolLogMapper = mock(RuntimeToolCallLogMapper.class);
        RuntimeAgentTraceSpanMapper spanMapper = mock(RuntimeAgentTraceSpanMapper.class);
        RuntimeTraceQueryService service = new RuntimeTraceQueryService(toolLogMapper, spanMapper, new ObjectMapper());
        when(toolLogMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeToolCallLogEntity>>any())).thenReturn(List.of());
        when(spanMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeAgentTraceSpanEntity>>any())).thenReturn(List.of());

        Optional<RuntimeTraceDetailView> detail = service.getTraceDetail("missing");

        assertTrue(detail.isEmpty());
    }

    @Test
    void listsRecentTracesGroupedByTraceIdWithMostRecentGroupsFirst() {
        RuntimeToolCallLogMapper toolLogMapper = mock(RuntimeToolCallLogMapper.class);
        RuntimeAgentTraceSpanMapper spanMapper = mock(RuntimeAgentTraceSpanMapper.class);
        RuntimeTraceQueryService service = new RuntimeTraceQueryService(toolLogMapper, spanMapper, new ObjectMapper());
        when(toolLogMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeToolCallLogEntity>>any())).thenReturn(List.of(
                toolLog(30L, "trace-b", "inventory.reserve", "{}", "reserved", true, null,
                        LocalDateTime.parse("2026-06-29T12:02:00")),
                toolLog(20L, "trace-a", "order.lookup", "{}", "lookup failed", false, null,
                        LocalDateTime.parse("2026-06-29T12:01:00")),
                toolLog(10L, "trace-a", "order.intent", "{}", "intent ok", true, null,
                        LocalDateTime.parse("2026-06-29T12:00:00"))
        ));

        List<RuntimeTraceSummaryView> summaries = service.listRecentTraces("user-1", 20, 7);

        assertEquals(2, summaries.size());
        assertEquals("trace-b", summaries.get(0).traceId());
        assertEquals(1, summaries.get(0).callCount());
        assertEquals(1L, summaries.get(0).successCount());
        assertEquals("trace-a", summaries.get(1).traceId());
        assertEquals(2, summaries.get(1).callCount());
        assertEquals(1L, summaries.get(1).successCount());
        assertEquals(LocalDateTime.parse("2026-06-29T12:00:00"), summaries.get(1).startedAt());
        assertEquals(LocalDateTime.parse("2026-06-29T12:01:00"), summaries.get(1).endedAt());
    }

    private RuntimeToolCallLogEntity toolLog(Long id,
                                             String traceId,
                                             String toolName,
                                             String argsJson,
                                             String resultSummary,
                                             boolean success,
                                             String retrievalTraceJson,
                                             LocalDateTime createTime) {
        RuntimeToolCallLogEntity entity = new RuntimeToolCallLogEntity();
        entity.setId(id);
        entity.setTraceId(traceId);
        entity.setAgentName("Order Agent");
        entity.setToolName(toolName);
        entity.setArgsJson(argsJson);
        entity.setResultSummary(resultSummary);
        entity.setSuccess(success);
        entity.setElapsedMs(42);
        entity.setTokenCost(0);
        entity.setRetrievalTraceJson(retrievalTraceJson);
        entity.setCreateTime(createTime);
        return entity;
    }

    private RuntimeAgentTraceSpanEntity span(Long id,
                                             String traceId,
                                             String spanType,
                                             String runtimeType,
                                             String nodeId,
                                             String modelInstanceId,
                                             String inputSummary,
                                             String outputSummary,
                                             String status,
                                             LocalDateTime startedAt) {
        RuntimeAgentTraceSpanEntity entity = new RuntimeAgentTraceSpanEntity();
        entity.setId(id);
        entity.setTraceId(traceId);
        entity.setSpanType(spanType);
        entity.setRuntimeType(runtimeType);
        entity.setNodeId(nodeId);
        entity.setModelInstanceId(modelInstanceId);
        entity.setAgentName("Order Agent");
        entity.setInputSummary(inputSummary);
        entity.setOutputSummary(outputSummary);
        entity.setStatus(status);
        entity.setLatencyMs(87);
        entity.setTokenCost(30);
        entity.setStartedAt(startedAt);
        entity.setCreatedAt(startedAt.plusSeconds(1));
        return entity;
    }
}
