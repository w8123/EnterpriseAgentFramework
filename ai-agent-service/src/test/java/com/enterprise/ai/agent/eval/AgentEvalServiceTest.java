package com.enterprise.ai.agent.eval;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.runtime.AgentRuntimeAdapter;
import com.enterprise.ai.agent.runtime.GraphRuntimeContext;
import com.enterprise.ai.agent.runtime.host.LangGraph4jRuntimeAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentEvalServiceTest {

    private static final GraphSpec EVAL_GRAPH = GraphSpec.builder()
            .code("eval")
            .entry("answer")
            .node(GraphSpec.Node.builder().id("answer").type("ANSWER").build())
            .build();

    private static final GraphRuntimeContext EVAL_CONTEXT = GraphRuntimeContext.builder()
            .sourceType("WORKFLOW_DRAFT")
            .sourceId("agent-1")
            .name("售后流程")
            .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
            .allowIrreversible(true)
            .build();

    @Test
    void runsDatasetForConfiguredRepeatCountAndAggregatesSummary() {
        Fixture fx = new Fixture();
        fx.withCases(List.of(
                fx.caseRow(11L, "case-1", "查询订单1001", """
                        {"contains":["订单1001"]}
                        """),
                fx.caseRow(12L, "case-2", "查询订单2002", """
                        {"contains":["订单2002"]}
                        """)));
        when(fx.runtime.debugRun(any(), any(), any(), any(), any()))
                .thenReturn(debugResult(true, "订单1001 已支付", "llm_1", 100))
                .thenReturn(debugResult(false, "没有找到订单", "tool_order_query", 300))
                .thenReturn(debugResult(true, "订单1001 已支付", "llm_1", 120))
                .thenReturn(debugResult(true, "未查询到目标订单", "llm_1", 180))
                .thenReturn(debugResult(true, "订单1001 已支付", "llm_1", 90))
                .thenReturn(debugResult(true, "未查询到目标订单", "llm_1", 160));

        AgentEvalService.RunView view = fx.service.startRun(new AgentEvalService.StartRunRequest(
                1L,
                "agent-1",
                "售后流程",
                "发布前评测",
                3,
                EVAL_GRAPH,
                EVAL_CONTEXT,
                Map.of("version", 2)));

        assertEquals(6, fx.resultRows().size());
        assertEquals("DONE", view.run().getStatus());
        assertEquals(6, view.summary().totalExecutions());
        assertEquals(5, view.summary().runtimeSuccessCount());
        assertEquals(3, view.summary().passedExecutions());
        assertEquals(5D / 6D, view.summary().runtimeSuccessRate());
        assertEquals(0.5D, view.summary().accuracyRate());
        assertEquals(180, view.summary().p95LatencyMs());
        assertEquals(3, view.summary().biasCount());
        assertTrue(view.suggestion().summary().contains("tool_order_query"));
    }

    @Test
    void sandboxedContextDisablesIrreversibleTools() {
        Fixture fx = new Fixture();
        fx.withCases(List.of(fx.caseRow(11L, "case-1", "hello", "{}")));
        when(fx.runtime.debugRun(any(), any(), any(), any(), any()))
                .thenReturn(debugResult(true, "hello", "answer", 50));
        ArgumentCaptor<GraphRuntimeContext> contextCaptor = ArgumentCaptor.forClass(GraphRuntimeContext.class);

        fx.service.startRun(new AgentEvalService.StartRunRequest(
                1L,
                "agent-1",
                "流程",
                "评测",
                1,
                EVAL_GRAPH,
                EVAL_CONTEXT,
                Map.of()));

        org.mockito.Mockito.verify(fx.runtime).debugRun(any(), contextCaptor.capture(), any(), any(), any());
        assertFalse(contextCaptor.getValue().isAllowIrreversible());
    }

    @Test
    void importsDatasetAndNormalizesCaseJson() {
        Fixture fx = new Fixture();

        AgentEvalDatasetEntity dataset = fx.service.importDataset(new AgentEvalService.DatasetImportRequest(
                "agent-1",
                "售后流程",
                "售后回归集",
                "发布前评测",
                List.of(new AgentEvalService.CaseImportRow(
                        "",
                        "查询订单1001",
                        Map.of("question", "查询订单1001"),
                        Map.of("contains", List.of("订单1001")),
                        Map.of("semanticEnabled", true),
                        "smoke"))));

        assertEquals(101L, dataset.getId());
        assertEquals(1, dataset.getCaseCount());
        ArgumentCaptor<AgentEvalCaseEntity> captor = ArgumentCaptor.forClass(AgentEvalCaseEntity.class);
        org.mockito.Mockito.verify(fx.caseMapper).insert(captor.capture());
        AgentEvalCaseEntity row = captor.getValue();
        assertEquals("case-1", row.getCaseNo());
        assertEquals(101L, row.getDatasetId());
        assertTrue(row.getExpectedJson().contains("订单1001"));
        assertTrue(row.getJudgeConfigJson().contains("semanticEnabled"));
    }

    private static LangGraph4jRuntimeAdapter.WorkflowDebugRunResult debugResult(boolean success,
                                                                                String answer,
                                                                                String nodeId,
                                                                                long elapsedMs) {
        return LangGraph4jRuntimeAdapter.WorkflowDebugRunResult.builder()
                .runId("debug-run")
                .traceId("trace-" + nodeId)
                .success(success)
                .status(success ? "SUCCESS" : "ERROR")
                .answer(answer)
                .finalState(Map.of("answer", answer))
                .steps(List.of(LangGraph4jRuntimeAdapter.WorkflowDebugStepResult.builder()
                        .index(0)
                        .nodeId(nodeId)
                        .nodeType("ANSWER")
                        .nodeName(nodeId)
                        .status(success ? "SUCCESS" : "ERROR")
                        .elapsedMs(elapsedMs)
                        .errorMessage(success ? null : "simulated failure")
                        .build()))
                .errorCode(success ? null : "SIMULATED_ERROR")
                .errorMessage(success ? null : "simulated failure")
                .build();
    }

    private static class Fixture {
        final AgentEvalDatasetMapper datasetMapper = mock(AgentEvalDatasetMapper.class);
        final AgentEvalCaseMapper caseMapper = mock(AgentEvalCaseMapper.class);
        final AgentEvalRunMapper runMapper = mock(AgentEvalRunMapper.class);
        final AgentEvalCaseResultMapper resultMapper = mock(AgentEvalCaseResultMapper.class);
        final LangGraph4jRuntimeAdapter runtime = mock(LangGraph4jRuntimeAdapter.class);
        final ObjectMapper objectMapper = new ObjectMapper();
        final AgentEvalService service = new AgentEvalService(
                datasetMapper,
                caseMapper,
                runMapper,
                resultMapper,
                runtime,
                new AgentEvalJudgeService(objectMapper),
                new AgentEvalSuggestionService(),
                objectMapper);

        Fixture() {
            AtomicLong ids = new AtomicLong(100);
            doAnswer(invocation -> {
                AgentEvalRunEntity row = invocation.getArgument(0);
                row.setId(ids.incrementAndGet());
                return 1;
            }).when(runMapper).insert(any());
            doAnswer(invocation -> {
                AgentEvalDatasetEntity row = invocation.getArgument(0);
                row.setId(ids.incrementAndGet());
                return 1;
            }).when(datasetMapper).insert(any());
            doAnswer(invocation -> {
                AgentEvalCaseEntity row = invocation.getArgument(0);
                row.setId(ids.incrementAndGet());
                return 1;
            }).when(caseMapper).insert(any());
            doAnswer(invocation -> 1).when(runMapper).updateById(any());
            doAnswer(invocation -> {
                AgentEvalCaseResultEntity row = invocation.getArgument(0);
                row.setId(ids.incrementAndGet());
                return 1;
            }).when(resultMapper).insert(any());
        }

        void withCases(List<AgentEvalCaseEntity> rows) {
            when(caseMapper.selectList(any())).thenReturn(rows);
        }

        AgentEvalCaseEntity caseRow(Long id, String caseNo, String message, String expectedJson) {
            AgentEvalCaseEntity row = new AgentEvalCaseEntity();
            row.setId(id);
            row.setDatasetId(1L);
            row.setCaseNo(caseNo);
            row.setMessage(message);
            row.setInputParamsJson("{\"question\":\"" + message + "\"}");
            row.setExpectedJson(expectedJson);
            row.setJudgeConfigJson("{}");
            row.setEnabled(true);
            return row;
        }

        List<AgentEvalCaseResultEntity> resultRows() {
            ArgumentCaptor<AgentEvalCaseResultEntity> captor =
                    ArgumentCaptor.forClass(AgentEvalCaseResultEntity.class);
            org.mockito.Mockito.verify(resultMapper, org.mockito.Mockito.atLeast(0)).insert(captor.capture());
            return captor.getAllValues();
        }
    }
}
