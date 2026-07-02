package com.enterprise.ai.runtime.eval;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeAgentEvalControllerTest {

    @Test
    void keepsAgentEvalRoutesOnRuntimeService() throws Exception {
        Method listDatasets = RuntimeAgentEvalController.class
                .getDeclaredMethod("listDatasets", String.class);
        Method createDataset = RuntimeAgentEvalController.class
                .getDeclaredMethod("createDataset", Map.class);
        Method importCases = RuntimeAgentEvalController.class
                .getDeclaredMethod("importCases", Long.class, Map.class);
        Method listCases = RuntimeAgentEvalController.class
                .getDeclaredMethod("listCases", Long.class);
        Method startRun = RuntimeAgentEvalController.class
                .getDeclaredMethod("startRun", Map.class);
        Method getRun = RuntimeAgentEvalController.class
                .getDeclaredMethod("getRun", Long.class);
        Method listRunResults = RuntimeAgentEvalController.class
                .getDeclaredMethod("listRunResults", Long.class);

        assertArrayEquals(new String[] {"/api/agent/evals/datasets", "/api/runtime/evals/datasets"},
                listDatasets.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/agent/evals/datasets", "/api/runtime/evals/datasets"},
                createDataset.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/agent/evals/datasets/{datasetId}/cases/import",
                        "/api/runtime/evals/datasets/{datasetId}/cases/import"},
                importCases.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/agent/evals/datasets/{datasetId}/cases",
                        "/api/runtime/evals/datasets/{datasetId}/cases"},
                listCases.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/agent/evals/runs", "/api/runtime/evals/runs"},
                startRun.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/agent/evals/runs/{runId}", "/api/runtime/evals/runs/{runId}"},
                getRun.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/agent/evals/runs/{runId}/results",
                        "/api/runtime/evals/runs/{runId}/results"},
                listRunResults.getAnnotation(GetMapping.class).value());
        assertEquals(RequestParam.class, listDatasets.getParameters()[0].getAnnotation(RequestParam.class).annotationType());
        assertEquals(RequestBody.class, createDataset.getParameters()[0].getAnnotation(RequestBody.class).annotationType());
        assertEquals(PathVariable.class, importCases.getParameters()[0].getAnnotation(PathVariable.class).annotationType());
    }

    @Test
    void delegatesAgentEvalOperationsToRuntimeService() {
        RuntimeAgentEvalService service = mock(RuntimeAgentEvalService.class);
        RuntimeAgentEvalController controller = new RuntimeAgentEvalController(service);
        Map<String, Object> request = Map.of(
                "agentId", "agent-1",
                "name", "smoke",
                "cases", List.of(Map.of("caseNo", "c1", "message", "hello")));
        RuntimeAgentEvalDatasetView dataset = dataset(1L);
        RuntimeAgentEvalCaseView evalCase = evalCase(11L);
        RuntimeAgentEvalRunView runView = runView(21L);
        when(service.listDatasets("agent-1")).thenReturn(List.of(dataset));
        when(service.createDataset(request)).thenReturn(dataset);
        when(service.importCases(1L, request)).thenReturn(dataset);
        when(service.listCases(1L)).thenReturn(List.of(evalCase));
        when(service.startRun(request)).thenReturn(runView);
        when(service.getRun(21L)).thenReturn(runView.run());
        when(service.listRunResults(21L)).thenReturn(runView.results());

        assertEquals(List.of(dataset), controller.listDatasets("agent-1"));
        assertEquals(dataset, controller.createDataset(request));
        assertEquals(dataset, controller.importCases(1L, request));
        assertEquals(List.of(evalCase), controller.listCases(1L));
        assertEquals(runView, controller.startRun(request));
        assertEquals(runView.run(), controller.getRun(21L));
        assertEquals(runView.results(), controller.listRunResults(21L));
        verify(service).listDatasets("agent-1");
        verify(service).createDataset(request);
        verify(service).importCases(1L, request);
        verify(service).listCases(1L);
        verify(service).startRun(request);
        verify(service).getRun(21L);
        verify(service).listRunResults(21L);
    }

    private RuntimeAgentEvalDatasetView dataset(Long id) {
        return new RuntimeAgentEvalDatasetView(id, "agent-1", "Orders Agent", "smoke",
                "basic cases", "IMPORT", 1, null, null);
    }

    private RuntimeAgentEvalCaseView evalCase(Long id) {
        return new RuntimeAgentEvalCaseView(id, 1L, "c1", "hello",
                "{}", "{}", "{}", "smoke", true);
    }

    private RuntimeAgentEvalRunView runView(Long id) {
        RuntimeAgentEvalRunDetail run = new RuntimeAgentEvalRunDetail(id, 1L, "agent-1",
                "Orders Agent", "smoke run", 1, "COMPLETED",
                "{\"caseCount\":1}", "{\"summary\":\"Runtime execution is not attached yet\"}",
                null, null);
        RuntimeAgentEvalCaseResultView result = new RuntimeAgentEvalCaseResultView(31L, id, 1L,
                11L, "c1", 1, "COMPLETED", false, false, null, 0.0, 0,
                null, null, "EVAL_RUNTIME_NOT_ATTACHED", "Runtime execution is not attached yet");
        return new RuntimeAgentEvalRunView(run, Map.of("caseCount", 1), Map.of("items", List.of()), List.of(result));
    }
}
