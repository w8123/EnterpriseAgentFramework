package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
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

class ControlAgentEvalPublicControllerTest {

    @Test
    void keepsAgentEvalPublicRoutesOnControlService() throws Exception {
        Method listDatasets = ControlRuntimePublicController.class
                .getDeclaredMethod("listEvalDatasets", String.class);
        Method createDataset = ControlRuntimePublicController.class
                .getDeclaredMethod("createEvalDataset", Map.class);
        Method importCases = ControlRuntimePublicController.class
                .getDeclaredMethod("importEvalCases", Long.class, Map.class);
        Method listCases = ControlRuntimePublicController.class
                .getDeclaredMethod("listEvalCases", Long.class);
        Method startRun = ControlRuntimePublicController.class
                .getDeclaredMethod("startEvalRun", Map.class);
        Method getRun = ControlRuntimePublicController.class
                .getDeclaredMethod("getEvalRun", Long.class);
        Method listRunResults = ControlRuntimePublicController.class
                .getDeclaredMethod("listEvalRunResults", Long.class);

        assertArrayEquals(new String[] {"/api/runtime/evals/datasets"},
                listDatasets.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/evals/datasets"},
                createDataset.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/evals/datasets/{datasetId}/cases/import"},
                importCases.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/evals/datasets/{datasetId}/cases"},
                listCases.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/evals/runs"},
                startRun.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/evals/runs/{runId}"},
                getRun.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/evals/runs/{runId}/results"},
                listRunResults.getAnnotation(GetMapping.class).value());
        assertEquals(RequestParam.class, listDatasets.getParameters()[0].getAnnotation(RequestParam.class).annotationType());
        assertEquals(RequestBody.class, createDataset.getParameters()[0].getAnnotation(RequestBody.class).annotationType());
        assertEquals(PathVariable.class, importCases.getParameters()[0].getAnnotation(PathVariable.class).annotationType());
    }

    @Test
    void delegatesAgentEvalPublicRoutesToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
        Map<String, Object> request = Map.of(
                "agentId", "agent-1",
                "name", "smoke",
                "cases", List.of(Map.of("caseNo", "c1", "message", "hello")));
        ResponseEntity<Object> response = ResponseEntity.ok(Map.of("id", 1L));
        ResponseEntity<Object> list = ResponseEntity.ok(List.of(Map.of("id", 1L)));
        when(runtimeProxyClient.listEvalDatasets("agent-1")).thenReturn(list);
        when(runtimeProxyClient.createEvalDataset(request)).thenReturn(response);
        when(runtimeProxyClient.importEvalCases(1L, request)).thenReturn(response);
        when(runtimeProxyClient.listEvalCases(1L)).thenReturn(list);
        when(runtimeProxyClient.startEvalRun(request)).thenReturn(response);
        when(runtimeProxyClient.getEvalRun(2L)).thenReturn(response);
        when(runtimeProxyClient.listEvalRunResults(2L)).thenReturn(list);

        assertEquals(list, controller.listEvalDatasets("agent-1"));
        assertEquals(response, controller.createEvalDataset(request));
        assertEquals(response, controller.importEvalCases(1L, request));
        assertEquals(list, controller.listEvalCases(1L));
        assertEquals(response, controller.startEvalRun(request));
        assertEquals(response, controller.getEvalRun(2L));
        assertEquals(list, controller.listEvalRunResults(2L));
        verify(runtimeProxyClient).listEvalDatasets("agent-1");
        verify(runtimeProxyClient).createEvalDataset(request);
        verify(runtimeProxyClient).importEvalCases(1L, request);
        verify(runtimeProxyClient).listEvalCases(1L);
        verify(runtimeProxyClient).startEvalRun(request);
        verify(runtimeProxyClient).getEvalRun(2L);
        verify(runtimeProxyClient).listEvalRunResults(2L);
    }
}
