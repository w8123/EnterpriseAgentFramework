package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.route.RuntimeRouteEvaluationService;
import com.enterprise.ai.runtime.route.RuntimeRouteEvaluationView;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionService;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsQueryService;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsReplayService;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsComparisonView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDiagnosticsView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDetailView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSummaryView;
import com.enterprise.ai.runtime.trace.RuntimeTraceDetailView;
import com.enterprise.ai.runtime.trace.RuntimeTraceNodeView;
import com.enterprise.ai.runtime.trace.RuntimeTraceQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimePublicCompatibilityControllerTest {

    @Test
    void keepsPublicRuntimeRouteShapeOnRuntimeService() throws Exception {
        Method executeAgent = RuntimePublicCompatibilityController.class
                .getDeclaredMethod("executeAgent", Map.class);
        Method executeAgentDetailed = RuntimePublicCompatibilityController.class
                .getDeclaredMethod("executeAgentDetailed", Map.class);
        Method routeEvaluation = RuntimePublicCompatibilityController.class.getDeclaredMethod("routeEvaluation", int.class);
        Method getTrace = RuntimePublicCompatibilityController.class.getDeclaredMethod("getTrace", String.class);
        Method listRecentTraces = RuntimePublicCompatibilityController.class
                .getDeclaredMethod("listRecentTraces", String.class, int.class, int.class);
        Method runOpsDetail = RuntimePublicCompatibilityController.class.getDeclaredMethod("runOpsDetail", String.class);
        Method runOpsRecent = RuntimePublicCompatibilityController.class
                .getDeclaredMethod("runOpsRecent", String.class, int.class, int.class);
        Method runOpsDiagnostics = RuntimePublicCompatibilityController.class
                .getDeclaredMethod("runOpsDiagnostics", String.class, int.class, int.class);
        Method runOpsCompare = RuntimePublicCompatibilityController.class
                .getDeclaredMethod("runOpsCompare", String.class, String.class);
        Method runOpsReplay = RuntimePublicCompatibilityController.class
                .getDeclaredMethod("runOpsReplay", String.class, RuntimeRunOpsReplayService.ReplayRequest.class);

        assertArrayEquals(new String[] {"/api/agent/execute", "/api/runtime/agents/execute"},
                executeAgent.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/agent/execute/detailed", "/api/runtime/agents/execute/detailed"},
                executeAgentDetailed.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/agent/route-evaluation", "/api/runtime/agents/route-evaluation"},
                routeEvaluation.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/traces/{traceId}"}, getTrace.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/traces/recent"}, listRecentTraces.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/runops/traces/{traceId}"}, runOpsDetail.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/runops/traces/recent"}, runOpsRecent.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/runops/diagnostics"}, runOpsDiagnostics.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/runops/traces/{traceId}/compare/{candidateTraceId}"},
                runOpsCompare.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/runops/traces/{traceId}/replay"},
                runOpsReplay.getAnnotation(PostMapping.class).value());
    }

    @Test
    void executeAgentDelegatesToRuntimeAgentExecutionService() {
        RuntimeAgentExecutionService executionService = mock(RuntimeAgentExecutionService.class);
        RuntimePublicCompatibilityController controller = controller(mock(RuntimeTraceQueryService.class), executionService);
        Map<String, Object> request = Map.of("agentDefinitionId", "orders-bot", "message", "hello");
        Map<String, Object> expected = Map.of(
                "success", true,
                "answer", "hello from Runtime GraphSpec",
                "metadata", Map.of("code", "RUNTIME_GRAPH_EXECUTED"));
        when(executionService.execute(request, false)).thenReturn(expected);

        ResponseEntity<Map<String, Object>> response = controller.executeAgent(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
        verify(executionService).execute(request, false);
    }

    @Test
    void executeAgentDetailedDelegatesToRuntimeAgentExecutionService() {
        RuntimeAgentExecutionService executionService = mock(RuntimeAgentExecutionService.class);
        RuntimePublicCompatibilityController controller = controller(mock(RuntimeTraceQueryService.class), executionService);
        Map<String, Object> request = Map.of("agentDefinitionId", "orders-bot", "message", "hello");
        Map<String, Object> expected = Map.of(
                "success", true,
                "answer", "hello from Runtime GraphSpec",
                "steps", List.of(Map.of("name", "resolve-workflow", "detail", "wf-orders")),
                "metadata", Map.of("code", "RUNTIME_GRAPH_EXECUTED"));
        when(executionService.execute(request, true)).thenReturn(expected);

        ResponseEntity<Map<String, Object>> response = controller.executeAgentDetailed(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
        verify(executionService).execute(request, true);
    }

    @Test
    void delegatesTraceDetailToRuntimeTraceQueryService() {
        RuntimeTraceQueryService traceQueryService = mock(RuntimeTraceQueryService.class);
        RuntimePublicCompatibilityController controller = controller(traceQueryService);
        RuntimeTraceDetailView detail = new RuntimeTraceDetailView("trace-1", List.of(new RuntimeTraceNodeView(
                1L,
                "tool_call_log",
                "trace-1",
                "Order Agent",
                "order.lookup",
                null,
                null,
                null,
                null,
                null,
                "{}",
                "ok",
                true,
                null,
                12,
                30,
                List.of(),
                LocalDateTime.parse("2026-06-29T12:00:00")
        )));
        when(traceQueryService.getTraceDetail("trace-1")).thenReturn(Optional.of(detail));

        ResponseEntity<?> response = controller.getTrace("trace-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(detail, response.getBody());
        verify(traceQueryService).getTraceDetail("trace-1");
    }

    @Test
    void returnsNotFoundWhenTraceDoesNotExist() {
        RuntimeTraceQueryService traceQueryService = mock(RuntimeTraceQueryService.class);
        RuntimePublicCompatibilityController controller = controller(traceQueryService);
        when(traceQueryService.getTraceDetail("missing")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getTrace("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void delegatesRecentTraceListToRuntimeTraceQueryService() {
        RuntimeTraceQueryService traceQueryService = mock(RuntimeTraceQueryService.class);
        RuntimePublicCompatibilityController controller = controller(traceQueryService);
        List<com.enterprise.ai.runtime.trace.RuntimeTraceSummaryView> summaries = List.of(
                new com.enterprise.ai.runtime.trace.RuntimeTraceSummaryView(
                        "trace-1",
                        "session-1",
                        "user-1",
                        "Order Agent",
                        "ORDER_QA",
                        2,
                        1,
                        LocalDateTime.parse("2026-06-29T12:00:00"),
                        LocalDateTime.parse("2026-06-29T12:00:03")
                )
        );
        when(traceQueryService.listRecentTraces("user-1", 10, 7)).thenReturn(summaries);

        ResponseEntity<?> response = controller.listRecentTraces("user-1", 7, 10);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(summaries, response.getBody());
        verify(traceQueryService).listRecentTraces("user-1", 10, 7);
    }

    @Test
    void delegatesRouteEvaluationToRuntimeRouteEvaluationService() {
        RuntimeTraceQueryService traceQueryService = mock(RuntimeTraceQueryService.class);
        RuntimeRouteEvaluationService routeEvaluationService = mock(RuntimeRouteEvaluationService.class);
        RuntimePublicCompatibilityController controller =
                new RuntimePublicCompatibilityController(
                        traceQueryService,
                        mock(RuntimeAgentExecutionService.class),
                        routeEvaluationService,
                        mock(RuntimeRunOpsQueryService.class),
                        mock(RuntimeRunOpsReplayService.class));
        RuntimeRouteEvaluationView expected = new RuntimeRouteEvaluationView(
                30,
                11,
                7,
                3,
                Map.of("GENERAL_CHAT", 5L),
                Map.of("agent-a", 2L),
                true,
                false,
                "keep collecting trace");
        when(routeEvaluationService.evaluate(30)).thenReturn(expected);

        ResponseEntity<RuntimeRouteEvaluationView> response = controller.routeEvaluation(30);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
        verify(routeEvaluationService).evaluate(30);
    }

    @Test
    void delegatesRunOpsDetailToRuntimeRunOpsQueryService() {
        RuntimeRunOpsQueryService runOpsQueryService = mock(RuntimeRunOpsQueryService.class);
        RuntimePublicCompatibilityController controller = new RuntimePublicCompatibilityController(
                mock(RuntimeTraceQueryService.class),
                mock(RuntimeAgentExecutionService.class),
                mock(RuntimeRouteEvaluationService.class),
                runOpsQueryService,
                mock(RuntimeRunOpsReplayService.class));
        RuntimeRunOpsDetailView expected = new RuntimeRunOpsDetailView(
                runOpsSummary("trace-1"), List.of(), List.of(), List.of(), null, List.of(), List.of());
        when(runOpsQueryService.detail("trace-1")).thenReturn(expected);

        ResponseEntity<?> response = controller.runOpsDetail("trace-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
        verify(runOpsQueryService).detail("trace-1");
    }

    @Test
    void returnsBadRequestWhenRunOpsDetailIsMissing() {
        RuntimeRunOpsQueryService runOpsQueryService = mock(RuntimeRunOpsQueryService.class);
        RuntimePublicCompatibilityController controller = new RuntimePublicCompatibilityController(
                mock(RuntimeTraceQueryService.class),
                mock(RuntimeAgentExecutionService.class),
                mock(RuntimeRouteEvaluationService.class),
                runOpsQueryService,
                mock(RuntimeRunOpsReplayService.class));
        when(runOpsQueryService.detail("missing")).thenThrow(new IllegalArgumentException("RunOps 运行记录不存在: missing"));

        ResponseEntity<?> response = controller.runOpsDetail("missing");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(new RuntimePublicCompatibilityController.ApiErrorResponse(
                "RunOps 运行记录不存在: missing"), response.getBody());
    }

    @Test
    void delegatesRunOpsRecentToRuntimeRunOpsQueryService() {
        RuntimeRunOpsQueryService runOpsQueryService = mock(RuntimeRunOpsQueryService.class);
        RuntimePublicCompatibilityController controller = new RuntimePublicCompatibilityController(
                mock(RuntimeTraceQueryService.class),
                mock(RuntimeAgentExecutionService.class),
                mock(RuntimeRouteEvaluationService.class),
                runOpsQueryService,
                mock(RuntimeRunOpsReplayService.class));
        List<RuntimeRunOpsSummaryView> expected = List.of(runOpsSummary("trace-1"));
        when(runOpsQueryService.recent("user-1", 25, 14)).thenReturn(expected);

        ResponseEntity<List<RuntimeRunOpsSummaryView>> response = controller.runOpsRecent("user-1", 25, 14);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
        verify(runOpsQueryService).recent("user-1", 25, 14);
    }

    @Test
    void delegatesRunOpsCompareToRuntimeRunOpsQueryService() {
        RuntimeRunOpsQueryService runOpsQueryService = mock(RuntimeRunOpsQueryService.class);
        RuntimePublicCompatibilityController controller = new RuntimePublicCompatibilityController(
                mock(RuntimeTraceQueryService.class),
                mock(RuntimeAgentExecutionService.class),
                mock(RuntimeRouteEvaluationService.class),
                runOpsQueryService,
                mock(RuntimeRunOpsReplayService.class));
        RuntimeRunOpsComparisonView expected = new RuntimeRunOpsComparisonView(
                runOpsSummary("baseline"), runOpsSummary("candidate"), List.of(), List.of(), List.of(), List.of());
        when(runOpsQueryService.compare("baseline", "candidate")).thenReturn(expected);

        ResponseEntity<?> response = controller.runOpsCompare("baseline", "candidate");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
        verify(runOpsQueryService).compare("baseline", "candidate");
    }

    @Test
    void delegatesRunOpsDiagnosticsToRuntimeRunOpsQueryService() {
        RuntimeRunOpsQueryService runOpsQueryService = mock(RuntimeRunOpsQueryService.class);
        RuntimePublicCompatibilityController controller = new RuntimePublicCompatibilityController(
                mock(RuntimeTraceQueryService.class),
                mock(RuntimeAgentExecutionService.class),
                mock(RuntimeRouteEvaluationService.class),
                runOpsQueryService,
                mock(RuntimeRunOpsReplayService.class));
        RuntimeRunOpsDiagnosticsView expected = new RuntimeRunOpsDiagnosticsView(List.of(), List.of());
        when(runOpsQueryService.diagnostics("user-1", 25, 14)).thenReturn(expected);

        ResponseEntity<RuntimeRunOpsDiagnosticsView> response = controller.runOpsDiagnostics("user-1", 25, 14);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
        verify(runOpsQueryService).diagnostics("user-1", 25, 14);
    }

    @Test
    void runOpsReplayDelegatesToRuntimeReplayService() {
        RuntimeRunOpsReplayService replayService = mock(RuntimeRunOpsReplayService.class);
        RuntimePublicCompatibilityController controller = new RuntimePublicCompatibilityController(
                mock(RuntimeTraceQueryService.class),
                mock(RuntimeAgentExecutionService.class),
                mock(RuntimeRouteEvaluationService.class),
                mock(RuntimeRunOpsQueryService.class),
                replayService);
        RuntimeRunOpsReplayService.ReplayRequest request =
                new RuntimeRunOpsReplayService.ReplayRequest("hello", "session-replay", "user-1", List.of(), true);
        RuntimeRunOpsReplayService.ReplayResult expected =
                new RuntimeRunOpsReplayService.ReplayResult(
                        "trace-1", "trace-replay", "session-replay", "user-1", "agent-1", "Order Agent",
                        "v1", 1L, "hello", true, "ok", Map.of(), "wf-1", "orders-workflow",
                        "wv1", 2L, "agent-1", "orders-agent", "WORKFLOW", "wf-1",
                        "RUNTIME_AGENT_EXECUTION", null);
        when(replayService.replay("trace-1", request)).thenReturn(expected);

        ResponseEntity<?> response = controller.runOpsReplay("trace-1", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
        verify(replayService).replay("trace-1", request);
    }

    private RuntimePublicCompatibilityController controller(RuntimeTraceQueryService traceQueryService) {
        return controller(traceQueryService, mock(RuntimeAgentExecutionService.class));
    }

    private RuntimePublicCompatibilityController controller(RuntimeTraceQueryService traceQueryService,
                                                           RuntimeAgentExecutionService executionService) {
        return new RuntimePublicCompatibilityController(
                traceQueryService,
                executionService,
                mock(RuntimeRouteEvaluationService.class),
                mock(RuntimeRunOpsQueryService.class),
                mock(RuntimeRunOpsReplayService.class));
    }

    private RuntimeRunOpsSummaryView runOpsSummary(String traceId) {
        return new RuntimeRunOpsSummaryView(
                traceId,
                "SUCCESS",
                "agent-1",
                "Order Agent",
                null,
                null,
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
                1,
                1,
                0,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of());
    }
}
