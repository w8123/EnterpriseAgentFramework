package com.enterprise.ai.runtime.eval;

import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeAgentEvalServiceTest {

    private final RuntimeAgentEvalDatasetMapper datasetMapper = mock(RuntimeAgentEvalDatasetMapper.class);
    private final RuntimeAgentEvalCaseMapper caseMapper = mock(RuntimeAgentEvalCaseMapper.class);
    private final RuntimeAgentEvalRunMapper runMapper = mock(RuntimeAgentEvalRunMapper.class);
    private final RuntimeAgentEvalCaseResultMapper resultMapper = mock(RuntimeAgentEvalCaseResultMapper.class);
    private final RuntimeAgentExecutionService executionService = mock(RuntimeAgentExecutionService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeAgentEvalService service = new RuntimeAgentEvalService(
            datasetMapper, caseMapper, runMapper, resultMapper, objectMapper, executionService);

    @Test
    void executesAgentRuntimeAndGradesWorkflowAndPolicyAssertions() throws Exception {
        RuntimeAgentEvalDatasetEntity dataset = dataset();
        RuntimeAgentEvalCaseEntity workflowCase = evalCase(
                11L, "single-workflow", "查询第一条有效班组信息",
                Map.of("roles", List.of("team:user"), "tenantId", "tenant-a", "projectCode", "qmssmp"),
                Map.of("success", true, "answerContains", "一班", "minWorkflowCalls", 1,
                        "maxWorkflowCalls", 1, "calledTools", List.of("query_team")));
        RuntimeAgentEvalCaseEntity deniedCase = evalCase(
                12L, "unauthorized", "查询班组信息",
                Map.of("roles", List.of("guest"), "tenantId", "tenant-a", "projectCode", "qmssmp"),
                Map.of("success", true, "maxWorkflowCalls", 0, "policyDecision", "DENY",
                        "forbiddenTools", List.of("query_team")));
        when(datasetMapper.selectById(1L)).thenReturn(dataset);
        when(caseMapper.selectList(any())).thenReturn(List.of(workflowCase, deniedCase));
        doAnswer(invocation -> {
            RuntimeAgentEvalRunEntity run = invocation.getArgument(0);
            run.setId(21L);
            return 1;
        }).when(runMapper).insert(any());
        List<RuntimeAgentEvalCaseResultEntity> storedResults = new ArrayList<>();
        doAnswer(invocation -> {
            RuntimeAgentEvalCaseResultEntity result = invocation.getArgument(0);
            result.setId(31L + storedResults.size());
            storedResults.add(result);
            return 1;
        }).when(resultMapper).insert(any());
        when(resultMapper.selectList(any())).thenAnswer(invocation -> List.copyOf(storedResults));
        when(executionService.execute(any(), eq(true)))
                .thenReturn(Map.of(
                        "success", true,
                        "answer", "第一条有效班组是一班",
                        "steps", List.of(
                                Map.of("name", "plan", "detail", Map.of("planNo", 1)),
                                Map.of("name", "workflow", "detail", Map.of("toolName", "query_team"))),
                        "metadata", Map.of(
                                "code", "SUPERVISOR_COMPLETED", "traceId", "trace-1",
                                "planCount", 1, "replanCount", 0, "workflowCallCount", 1)))
                .thenReturn(Map.of(
                        "success", true,
                        "answer", "当前身份无权查询班组信息",
                        "steps", List.of(Map.of(
                                "name", "policy",
                                "detail", Map.of("toolName", "query_team", "decision", "DENY"))),
                        "metadata", Map.of(
                                "code", "SUPERVISOR_COMPLETED", "traceId", "trace-2",
                                "planCount", 1, "replanCount", 0, "workflowCallCount", 0)));

        RuntimeAgentEvalRunView view = service.startRun(Map.of(
                "datasetId", 1L,
                "repeatCount", 1,
                "runtimeContext", Map.of("origin", "agent-eval")));

        assertEquals("COMPLETED", view.run().status());
        assertEquals(2, view.results().size());
        assertEquals(2, view.summary().get("runtimeSuccessCount"));
        assertEquals(2, view.summary().get("passedExecutions"));
        assertEquals(1.0, view.summary().get("accuracyRate"));
        assertTrue(storedResults.stream().allMatch(RuntimeAgentEvalCaseResultEntity::getAssertionPassed));
        assertTrue(storedResults.stream().allMatch(result -> result.getScore() == 1.0));
        assertTrue(storedResults.stream().allMatch(result -> result.getErrorCode() == null));
        assertTrue(storedResults.get(0).getJudgeResultJson().contains("calledTools"));
        assertEquals("trace-1", storedResults.get(0).getTraceId());
        assertNull(storedResults.get(0).getSemanticScore());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(executionService, org.mockito.Mockito.times(2)).execute(inputCaptor.capture(), eq(true));
        assertEquals("agent-1", inputCaptor.getAllValues().get(0).get("agentId"));
        assertEquals("AGENT_EVAL", inputCaptor.getAllValues().get(0).get("intentHint"));
        assertEquals(true, inputCaptor.getAllValues().get(0).get("evalMode"));
    }

    @Test
    void recordsAssertionFailureAndActionableSuggestion() throws Exception {
        RuntimeAgentEvalCaseEntity evalCase = evalCase(
                11L, "wrong-tool", "查询班组",
                Map.of(),
                Map.of("success", true, "calledTools", List.of("query_team"), "maxReplanCount", 0));
        when(datasetMapper.selectById(1L)).thenReturn(dataset());
        when(caseMapper.selectList(any())).thenReturn(List.of(evalCase));
        doAnswer(invocation -> {
            ((RuntimeAgentEvalRunEntity) invocation.getArgument(0)).setId(21L);
            return 1;
        }).when(runMapper).insert(any());
        List<RuntimeAgentEvalCaseResultEntity> stored = new ArrayList<>();
        doAnswer(invocation -> {
            stored.add(invocation.getArgument(0));
            return 1;
        }).when(resultMapper).insert(any());
        when(resultMapper.selectList(any())).thenAnswer(invocation -> List.copyOf(stored));
        when(executionService.execute(any(), eq(true))).thenReturn(Map.of(
                "success", true,
                "answer", "完成",
                "steps", List.of(Map.of("name", "workflow", "detail", Map.of("toolName", "other_tool"))),
                "metadata", Map.of("workflowCallCount", 1, "replanCount", 1)));

        RuntimeAgentEvalRunView view = service.startRun(Map.of("datasetId", 1L, "repeatCount", 1));

        assertEquals(0, view.summary().get("passedExecutions"));
        assertEquals("EVAL_ASSERTION_FAILED", stored.get(0).getErrorCode());
        assertTrue(stored.get(0).getErrorMessage().contains("calledTool:query_team"));
        assertFalse(((List<?>) view.suggestion().get("items")).isEmpty());
    }

    private RuntimeAgentEvalDatasetEntity dataset() {
        RuntimeAgentEvalDatasetEntity dataset = new RuntimeAgentEvalDatasetEntity();
        dataset.setId(1L);
        dataset.setAgentId("agent-1");
        dataset.setAgentName("班组助手");
        dataset.setName("Supervisor regression");
        dataset.setCaseCount(2);
        dataset.setCreateTime(LocalDateTime.now());
        dataset.setUpdateTime(LocalDateTime.now());
        return dataset;
    }

    private RuntimeAgentEvalCaseEntity evalCase(Long id,
                                                String caseNo,
                                                String message,
                                                Map<String, Object> input,
                                                Map<String, Object> expected) throws Exception {
        RuntimeAgentEvalCaseEntity evalCase = new RuntimeAgentEvalCaseEntity();
        evalCase.setId(id);
        evalCase.setDatasetId(1L);
        evalCase.setCaseNo(caseNo);
        evalCase.setMessage(message);
        evalCase.setInputParamsJson(objectMapper.writeValueAsString(input));
        evalCase.setExpectedJson(objectMapper.writeValueAsString(expected));
        evalCase.setJudgeConfigJson("{}");
        evalCase.setEnabled(true);
        return evalCase;
    }
}
