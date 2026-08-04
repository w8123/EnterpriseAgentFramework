package com.enterprise.ai.runtime.api;

import com.enterprise.ai.runtime.route.RuntimeRouteEvaluationService;
import com.enterprise.ai.runtime.route.RuntimeRouteEvaluationView;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionCancellation;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimePublicControllerTest {

    @Test
    void keepsPublicRuntimeRouteShapeOnRuntimeService() throws Exception {
        Method executeAgent = RuntimePublicController.class
                .getDeclaredMethod("executeAgent", Map.class);
        Method executeAgentDetailed = RuntimePublicController.class
                .getDeclaredMethod("executeAgentDetailed", Map.class);
        Method executeAgentStream = RuntimePublicController.class
                .getDeclaredMethod("executeAgentStream", Map.class);
        Method clearAgentSession = RuntimePublicController.class
                .getDeclaredMethod("clearAgentSession", String.class);
        Method routeEvaluation = RuntimePublicController.class.getDeclaredMethod("routeEvaluation", int.class);
        Method getTrace = RuntimePublicController.class.getDeclaredMethod("getTrace", String.class);
        Method listRecentTraces = RuntimePublicController.class
                .getDeclaredMethod("listRecentTraces", String.class, int.class, int.class);
        Method runOpsDetail = RuntimePublicController.class.getDeclaredMethod("runOpsDetail", String.class);
        Method runOpsRecent = RuntimePublicController.class
                .getDeclaredMethod("runOpsRecent", String.class, String.class, String.class, String.class,
                        String.class, String.class, String.class, int.class, int.class);
        Method runOpsDiagnostics = RuntimePublicController.class
                .getDeclaredMethod("runOpsDiagnostics", String.class, String.class, String.class, String.class,
                        String.class, String.class, String.class, int.class, int.class);
        Method runOpsCompare = RuntimePublicController.class
                .getDeclaredMethod("runOpsCompare", String.class, String.class);
        Method runOpsReplay = RuntimePublicController.class
                .getDeclaredMethod("runOpsReplay", String.class, RuntimeRunOpsReplayService.ReplayRequest.class);

        assertArrayEquals(new String[] {"/api/runtime/agents/execute"},
                executeAgent.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/agents/execute/detailed"},
                executeAgentDetailed.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/agents/execute/stream"},
                executeAgentStream.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/agents/sessions/{sessionId}"},
                clearAgentSession.getAnnotation(DeleteMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/agents/route-evaluation"},
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
    void toExecutionErrorPayloadKeepsCodeTraceAndOmitsReasoning() {
        Map<String, Object> result = Map.of(
                "success", false,
                "answer", "模型在生成最终答案前达到最大输出长度，请提高最大输出或关闭思考模式后重试。",
                "sessionId", "s1",
                "metadata", Map.of(
                        "code", "MODEL_OUTPUT_TOKEN_LIMIT",
                        "traceId", "t1",
                        "model", Map.of(
                                "finishReason", "length",
                                "modelInstanceId", "seed-deepseek-v4-flash",
                                "reasoningContent", "secret-chain",
                                "usage", Map.of("completionTokens", 4096),
                                "eventCounts", Map.of("reasoningDeltaCount", 3)
                        )));
        Map<String, Object> payload = RuntimePublicController.toExecutionErrorPayload(
                result, Map.of("sessionId", "s1"));

        assertEquals("MODEL_OUTPUT_TOKEN_LIMIT", payload.get("code"));
        assertEquals("t1", payload.get("traceId"));
        assertEquals("s1", payload.get("sessionId"));
        assertTrue(String.valueOf(payload.get("message")).contains("最大输出长度"));
        assertFalse(String.valueOf(payload).contains("secret-chain"));
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) payload.get("metadata");
        assertEquals("length", metadata.get("finishReason"));
        assertEquals("seed-deepseek-v4-flash", metadata.get("modelInstanceId"));
    }

    @Test
    void isExecutionFailureDetectsSuccessFalse() {
        assertTrue(RuntimePublicController.isExecutionFailure(Map.of("success", false)));
        assertFalse(RuntimePublicController.isExecutionFailure(Map.of("success", true)));
    }

    @Test
    void isSupervisorCancelledReadsMetadataCode() {
        assertTrue(RuntimePublicController.isSupervisorCancelled(Map.of(
                "success", false,
                "metadata", Map.of("code", "SUPERVISOR_CANCELLED"))));
        assertFalse(RuntimePublicController.isSupervisorCancelled(Map.of(
                "success", false,
                "metadata", Map.of("code", "AGENT_EXECUTION_FAILED"))));
    }

    @Test
    void executeAgentStreamRegistersLifecycleCancelBeforeAsyncStart() throws Exception {
        RuntimeAgentExecutionService executionService = mock(RuntimeAgentExecutionService.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean cancelledSeen = new AtomicBoolean(false);
        AtomicReference<RuntimeAgentExecutionCancellation> captured = new AtomicReference<>();
        when(executionService.execute(any(), eq(true), any(), any())).thenAnswer(invocation -> {
            RuntimeAgentExecutionCancellation cancellation = invocation.getArgument(3);
            captured.set(cancellation);
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            cancelledSeen.set(cancellation.isCancelled());
            return Map.of(
                    "success", true,
                    "answer", "ok",
                    "metadata", Map.of("code", "SUPERVISOR_COMPLETED", "contentStreamed", true));
        });

        RuntimePublicController controller = controller(mock(RuntimeTraceQueryService.class), executionService);
        org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.SseEmitter> response =
                controller.executeAgentStream(Map.of("agentId", "a1", "message", "hi"));
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = response.getBody();
        assertNotNull(emitter);
        assertEquals("no", response.getHeaders().getFirst("X-Accel-Buffering"));
        assertTrue(started.await(3, TimeUnit.SECONDS));
        assertNotNull(captured.get());
        assertFalse(captured.get().isCancelled());
        // 无 Servlet 初始化时 emitter.complete() 不会派发回调；直接触发已注册的 onCompletion
        invokeEmitterCallback(emitter, "completionCallback");
        release.countDown();
        Thread.sleep(200);
        assertTrue(cancelledSeen.get(), "onCompletion must cancel request-level cancellation");
    }

    @Test
    void executeAgentStreamTimeoutCallbackCancelsRequest() throws Exception {
        RuntimeAgentExecutionService executionService = mock(RuntimeAgentExecutionService.class);
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<RuntimeAgentExecutionCancellation> captured = new AtomicReference<>();
        when(executionService.execute(any(), eq(true), any(), any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(3));
            started.countDown();
            Thread.sleep(500);
            return Map.of("success", true, "answer", "ok",
                    "metadata", Map.of("code", "SUPERVISOR_COMPLETED", "contentStreamed", true));
        });
        RuntimePublicController controller = controller(mock(RuntimeTraceQueryService.class), executionService);
        org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.SseEmitter> response =
                controller.executeAgentStream(Map.of("agentId", "a1", "message", "hi"));
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = response.getBody();
        assertNotNull(emitter);
        assertEquals("no", response.getHeaders().getFirst("X-Accel-Buffering"));
        assertTrue(started.await(3, TimeUnit.SECONDS));
        invokeEmitterCallback(emitter, "timeoutCallback");
        assertTrue(captured.get().isCancelled(), "onTimeout must cancel request-level cancellation");
    }

    @Test
    void executeAgentStreamErrorCallbackCancelsRequest() throws Exception {
        RuntimeAgentExecutionService executionService = mock(RuntimeAgentExecutionService.class);
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<RuntimeAgentExecutionCancellation> captured = new AtomicReference<>();
        when(executionService.execute(any(), eq(true), any(), any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(3));
            started.countDown();
            Thread.sleep(500);
            return Map.of("success", true, "answer", "ok",
                    "metadata", Map.of("code", "SUPERVISOR_COMPLETED", "contentStreamed", true));
        });
        RuntimePublicController controller = controller(mock(RuntimeTraceQueryService.class), executionService);
        org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.SseEmitter> response =
                controller.executeAgentStream(Map.of("agentId", "a1", "message", "hi"));
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = response.getBody();
        assertNotNull(emitter);
        assertEquals("no", response.getHeaders().getFirst("X-Accel-Buffering"));
        assertTrue(started.await(3, TimeUnit.SECONDS));
        invokeEmitterErrorCallback(emitter);
        assertTrue(captured.get().isCancelled(), "onError must cancel request-level cancellation");
    }

    private static void invokeEmitterCallback(
            org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter,
            String fieldName) throws Exception {
        Class<?> type = org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.class;
        java.lang.reflect.Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        Object callback = field.get(emitter);
        assertNotNull(callback, fieldName + " must exist");
        java.lang.reflect.Field delegates = callback.getClass().getDeclaredField("delegates");
        delegates.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Runnable> list = (List<Runnable>) delegates.get(callback);
        assertFalse(list == null || list.isEmpty(), fieldName + " must be registered before async start");
        ((Runnable) callback).run();
    }

    private static void invokeEmitterErrorCallback(
            org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter) throws Exception {
        Class<?> type = org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.class;
        java.lang.reflect.Field field = type.getDeclaredField("errorCallback");
        field.setAccessible(true);
        Object callback = field.get(emitter);
        assertNotNull(callback);
        java.lang.reflect.Field delegates = callback.getClass().getDeclaredField("delegates");
        delegates.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<?> list = (List<?>) delegates.get(callback);
        assertFalse(list == null || list.isEmpty(), "errorCallback must be registered before async start");
        @SuppressWarnings("unchecked")
        java.util.function.Consumer<Throwable> consumer =
                (java.util.function.Consumer<Throwable>) callback;
        consumer.accept(new IOException("client disconnected"));
    }

    @Test
    void executeAgentDelegatesToRuntimeAgentExecutionService() {
        RuntimeAgentExecutionService executionService = mock(RuntimeAgentExecutionService.class);
        RuntimePublicController controller = controller(mock(RuntimeTraceQueryService.class), executionService);
        Map<String, Object> request = Map.of("agentId", "orders-bot", "message", "hello");
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
        RuntimePublicController controller = controller(mock(RuntimeTraceQueryService.class), executionService);
        Map<String, Object> request = Map.of("agentId", "orders-bot", "message", "hello");
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
    void clearAgentSessionDelegatesToExecutionService() {
        RuntimeAgentExecutionService executionService = mock(RuntimeAgentExecutionService.class);
        RuntimePublicController controller = controller(mock(RuntimeTraceQueryService.class), executionService);

        ResponseEntity<Void> response = controller.clearAgentSession("session-1");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(executionService).clearSession("session-1");
    }

    @Test
    void delegatesTraceDetailToRuntimeTraceQueryService() {
        RuntimeTraceQueryService traceQueryService = mock(RuntimeTraceQueryService.class);
        RuntimePublicController controller = controller(traceQueryService);
        RuntimeTraceDetailView detail = new RuntimeTraceDetailView("trace-1", List.of(new RuntimeTraceNodeView(
                1L,
                "runtime_tool_call_log",
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
        RuntimePublicController controller = controller(traceQueryService);
        when(traceQueryService.getTraceDetail("missing")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getTrace("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void delegatesRecentTraceListToRuntimeTraceQueryService() {
        RuntimeTraceQueryService traceQueryService = mock(RuntimeTraceQueryService.class);
        RuntimePublicController controller = controller(traceQueryService);
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
        RuntimePublicController controller =
                new RuntimePublicController(
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
        RuntimePublicController controller = new RuntimePublicController(
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
        RuntimePublicController controller = new RuntimePublicController(
                mock(RuntimeTraceQueryService.class),
                mock(RuntimeAgentExecutionService.class),
                mock(RuntimeRouteEvaluationService.class),
                runOpsQueryService,
                mock(RuntimeRunOpsReplayService.class));
        when(runOpsQueryService.detail("missing")).thenThrow(new IllegalArgumentException("RunOps 运行记录不存在: missing"));

        ResponseEntity<?> response = controller.runOpsDetail("missing");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(new RuntimePublicController.ApiErrorResponse(
                "RunOps 运行记录不存在: missing"), response.getBody());
    }

    @Test
    void delegatesRunOpsRecentToRuntimeRunOpsQueryService() {
        RuntimeRunOpsQueryService runOpsQueryService = mock(RuntimeRunOpsQueryService.class);
        RuntimePublicController controller = new RuntimePublicController(
                mock(RuntimeTraceQueryService.class),
                mock(RuntimeAgentExecutionService.class),
                mock(RuntimeRouteEvaluationService.class),
                runOpsQueryService,
                mock(RuntimeRunOpsReplayService.class));
        List<RuntimeRunOpsSummaryView> expected = List.of(runOpsSummary("trace-1"));
        when(runOpsQueryService.recent(
                "qmssmp", "SUCCESS", "AGENT", "DEBUG", "agent-1", "user-1", null, 25, 14))
                .thenReturn(expected);

        ResponseEntity<List<RuntimeRunOpsSummaryView>> response = controller.runOpsRecent(
                "qmssmp", "SUCCESS", "AGENT", "DEBUG", "agent-1", "user-1", null, 25, 14);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
        verify(runOpsQueryService).recent(
                "qmssmp", "SUCCESS", "AGENT", "DEBUG", "agent-1", "user-1", null, 25, 14);
    }

    @Test
    void delegatesRunOpsCompareToRuntimeRunOpsQueryService() {
        RuntimeRunOpsQueryService runOpsQueryService = mock(RuntimeRunOpsQueryService.class);
        RuntimePublicController controller = new RuntimePublicController(
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
        RuntimePublicController controller = new RuntimePublicController(
                mock(RuntimeTraceQueryService.class),
                mock(RuntimeAgentExecutionService.class),
                mock(RuntimeRouteEvaluationService.class),
                runOpsQueryService,
                mock(RuntimeRunOpsReplayService.class));
        RuntimeRunOpsDiagnosticsView expected = new RuntimeRunOpsDiagnosticsView(List.of(), List.of());
        when(runOpsQueryService.diagnostics(
                "qmssmp", "FAILED", "AGENT", "EMBED", "agent-1", "user-1", null, 25, 14))
                .thenReturn(expected);

        ResponseEntity<RuntimeRunOpsDiagnosticsView> response = controller.runOpsDiagnostics(
                "qmssmp", "FAILED", "AGENT", "EMBED", "agent-1", "user-1", null, 25, 14);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
        verify(runOpsQueryService).diagnostics(
                "qmssmp", "FAILED", "AGENT", "EMBED", "agent-1", "user-1", null, 25, 14);
    }

    @Test
    void runOpsReplayDelegatesToRuntimeReplayService() {
        RuntimeRunOpsReplayService replayService = mock(RuntimeRunOpsReplayService.class);
        RuntimePublicController controller = new RuntimePublicController(
                mock(RuntimeTraceQueryService.class),
                mock(RuntimeAgentExecutionService.class),
                mock(RuntimeRouteEvaluationService.class),
                mock(RuntimeRunOpsQueryService.class),
                replayService);
        RuntimeRunOpsReplayService.ReplayRequest request =
                new RuntimeRunOpsReplayService.ReplayRequest("hello", "session-replay", "user-1", List.of());
        RuntimeRunOpsReplayService.ReplayResult expected =
                new RuntimeRunOpsReplayService.ReplayResult(
                        "trace-1", "trace-replay", "session-replay", "user-1", "agent-1", "Order Agent",
                        1L, 1, "hello", true, "ok", Map.of());
        when(replayService.replay("trace-1", request)).thenReturn(expected);

        ResponseEntity<?> response = controller.runOpsReplay("trace-1", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
        verify(replayService).replay("trace-1", request);
    }

    private RuntimePublicController controller(RuntimeTraceQueryService traceQueryService) {
        return controller(traceQueryService, mock(RuntimeAgentExecutionService.class));
    }

    private RuntimePublicController controller(RuntimeTraceQueryService traceQueryService,
                                                           RuntimeAgentExecutionService executionService) {
        return new RuntimePublicController(
                traceQueryService,
                executionService,
                mock(RuntimeRouteEvaluationService.class),
                mock(RuntimeRunOpsQueryService.class),
                mock(RuntimeRunOpsReplayService.class));
    }

    private RuntimeRunOpsSummaryView runOpsSummary(String traceId) {
        return new RuntimeRunOpsSummaryView(
                traceId,
                "AGENT",
                "DEBUG",
                "COMPLETED",
                null,
                "orders",
                "tenant-1",
                "session-1",
                "user-1",
                "agent-1",
                "orders-agent",
                "Order Agent",
                1L,
                1,
                null,
                null,
                null,
                null,
                null,
                "AGENTSCOPE",
                "hello",
                "answer",
                null,
                null,
                LocalDateTime.parse("2026-06-29T12:00:00"),
                LocalDateTime.parse("2026-06-29T12:00:03"),
                3000,
                10,
                1,
                0,
                0,
                0,
                0,
                0,
                null,
                Map.of());
    }
}
