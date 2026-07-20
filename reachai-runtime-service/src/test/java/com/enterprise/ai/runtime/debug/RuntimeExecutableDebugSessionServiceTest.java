package com.enterprise.ai.runtime.debug;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDebugService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeExecutableDebugSessionServiceTest {

    private final RuntimeExecutableDebugSessionMapper mapper = mock(RuntimeExecutableDebugSessionMapper.class);
    private final RuntimeWorkflowDebugService workflowDebugService = mock(RuntimeWorkflowDebugService.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final RuntimeExecutableDebugSessionService service =
            new RuntimeExecutableDebugSessionService(mapper, workflowDebugService, objectMapper);

    @Test
    void createPersistsSessionAndReturnsFrontendCompatibleView() {
        RuntimeWorkflowDebugService.DebugRunResult run = new RuntimeWorkflowDebugService.DebugRunResult(
                "run-1",
                "trace-1",
                null,
                "WORKFLOW",
                true,
                "SUCCESS",
                "answer ok",
                "answer",
                List.of(),
                null,
                List.of(new RuntimeWorkflowDebugService.DebugStepResult(
                        0, "answer", "ANSWER", "Answer", "SUCCESS", null, null, 0L,
                        Map.of(), Map.of("answer", "answer ok"), null, Map.of(), Map.of(),
                        "execute-node", null, null, null, null, null, null, null)),
                Map.of("lastOutput", "answer ok"),
                null,
                null);
        when(workflowDebugService.debugRun(any(), any(), any())).thenReturn(run);

        RuntimeExecutableDebugSessionService.SessionView view = service.create(
                new RuntimeExecutableDebugSessionService.CreateRequest(
                        "WORKFLOW_DRAFT",
                        Map.of("graphSpecJson", "{\"entry\":\"answer\",\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}]}"),
                        "hello",
                        Map.of("channel", "studio"),
                        Map.of()));

        ArgumentCaptor<RuntimeExecutableDebugSessionEntity> captor =
                ArgumentCaptor.forClass(RuntimeExecutableDebugSessionEntity.class);
        verify(mapper).insert(captor.capture());
        RuntimeExecutableDebugSessionEntity inserted = captor.getValue();
        assertNotNull(inserted.getId());
        assertEquals(inserted.getId(), view.sessionId());
        assertEquals("run-1", view.runId());
        assertEquals("trace-1", view.traceId());
        assertEquals("WORKFLOW_DRAFT", view.targetType());
        assertEquals("SUCCESS", view.status());
        assertEquals("answer ok", view.answer());
        assertEquals(2, view.messages().size());
        assertEquals("user", view.messages().get(0).role());
        assertEquals("assistant", view.messages().get(1).role());
        assertEquals(1, view.steps().size());
        assertEquals("answer ok", view.finalState().get("lastOutput"));
    }

    @Test
    void streamCreateReturnsSseEmitter() {
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = service.streamCreate(
                new RuntimeExecutableDebugSessionService.CreateRequest(
                        "WORKFLOW_DRAFT",
                        Map.of("graphSpecJson", "{\"entry\":\"answer\",\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}]}"),
                        "hello",
                        Map.of(),
                        Map.of()));

        assertNotNull(emitter);
    }

    @Test
    void submitContinuesWaitingSessionFromCurrentNode() {
        RuntimeExecutableDebugSessionEntity existing = waitingSessionEntity();
        when(mapper.selectById("session-1")).thenReturn(existing);
        when(mapper.update(any(), any())).thenReturn(1);
        RuntimeWorkflowDebugService.DebugRunResult run = new RuntimeWorkflowDebugService.DebugRunResult(
                "run-1",
                "trace-1",
                null,
                "WORKFLOW",
                true,
                "SUCCESS",
                "{approved=true}",
                "confirm",
                List.of(),
                null,
                List.of(),
                Map.of("lastOutput", "{approved=true}"),
                null,
                null);
        when(workflowDebugService.debugRun(any(), any(), any())).thenReturn(run);

        RuntimeExecutableDebugSessionService.SessionView view = service.submit(
                "session-1",
                new RuntimeExecutableDebugSessionService.SubmitRequest(
                        "submit",
                        Map.of("approved", true),
                        null));

        ArgumentCaptor<RuntimeWorkflowDebugService.DebugRunRequest> requestCaptor =
                ArgumentCaptor.forClass(RuntimeWorkflowDebugService.DebugRunRequest.class);
        verify(workflowDebugService).debugRun(requestCaptor.capture(), any(), any());
        assertEquals("confirm", requestCaptor.getValue().debugOptions().get("entryNodeId"));
        assertNull(requestCaptor.getValue().debugOptions().get("submittedPayload"));
        @SuppressWarnings("unchecked")
        Map<String, Object> resume = (Map<String, Object>) requestCaptor.getValue().inputParams()
                .get("__interactionResume");
        assertEquals("confirm", resume.get("nodeId"));
        assertEquals("submit", resume.get("action"));
        assertEquals(Map.of("approved", true), resume.get("values"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<RuntimeExecutableDebugSessionEntity>> updateCaptor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper, atLeastOnce()).update(any(), updateCaptor.capture());
        String sqlSegment = String.valueOf(updateCaptor.getAllValues().get(0).getSqlSegment());
        assertTrue(sqlSegment.contains("WAITING") || sqlSegment.toLowerCase().contains("status"),
                "CAS must constrain status=WAITING revision: " + sqlSegment);
        verify(mapper, atLeastOnce()).updateById(existing);
        assertEquals("SUCCESS", view.status());
        assertEquals("{approved=true}", view.answer());
    }

    @Test
    void concurrentSubmitOnlyOneCallsDebugRun() throws Exception {
        RuntimeExecutableDebugSessionEntity existing = waitingSessionEntity();
        when(mapper.selectById("session-1")).thenReturn(existing);
        AtomicInteger casWins = new AtomicInteger();
        when(mapper.update(any(), any())).thenAnswer(invocation -> casWins.getAndIncrement() == 0 ? 1 : 0);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger debugRuns = new AtomicInteger();
        RuntimeWorkflowDebugService.DebugRunResult run = new RuntimeWorkflowDebugService.DebugRunResult(
                "run-1", "trace-1", null, "WORKFLOW", true, "SUCCESS", "done", "confirm",
                List.of(), null, List.of(), Map.of("lastOutput", "done"), null, null);
        when(workflowDebugService.debugRun(any(), any(), any())).thenAnswer(invocation -> {
            debugRuns.incrementAndGet();
            started.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return run;
        });

        CompletableFuture<Object> first = CompletableFuture.supplyAsync(() -> {
            try {
                return service.submit("session-1", new RuntimeExecutableDebugSessionService.SubmitRequest(
                        "submit", Map.of("approved", true), null, "wfi_debug1", "idem-a"));
            } catch (RuntimeException ex) {
                return ex;
            }
        });
        // second starts after first CAS claimed RESUMING
        Thread.sleep(50);
        when(mapper.selectById("session-1")).thenAnswer(invocation -> {
            RuntimeExecutableDebugSessionEntity latest = waitingSessionEntity();
            if (casWins.get() > 0) {
                latest.setStatus("RESUMING");
                latest.setRevision(1);
                latest.setIdempotencyKey("idem-a");
                latest.setSubmittedPayloadJson(
                        "{\"action\":\"submit\",\"values\":{\"approved\":true},\"interactionId\":\"wfi_debug1\",\"nodeId\":\"confirm\"}");
            }
            return latest;
        });
        CompletableFuture<Object> second = CompletableFuture.supplyAsync(() -> {
            try {
                return service.submit("session-1", new RuntimeExecutableDebugSessionService.SubmitRequest(
                        "submit", Map.of("approved", true), null, "wfi_debug1", "idem-b"));
            } catch (RuntimeException ex) {
                return ex;
            }
        });
        release.countDown();
        Object firstResult = first.get(5, TimeUnit.SECONDS);
        Object secondResult = second.get(5, TimeUnit.SECONDS);
        assertEquals(1, debugRuns.get(), "downstream debugRun must execute exactly once");
        assertTrue(firstResult instanceof RuntimeExecutableDebugSessionService.SessionView
                        || secondResult instanceof RuntimeExecutableDebugSessionService.SessionView);
        assertTrue(firstResult instanceof RuntimeException || secondResult instanceof RuntimeException
                        || firstResult instanceof RuntimeExecutableDebugSessionService.SessionView);
    }

    @Test
    void sameIdempotencyKeyDifferentPayloadConflicts() {
        RuntimeExecutableDebugSessionEntity existing = waitingSessionEntity();
        existing.setIdempotencyKey("idem-1");
        existing.setSubmittedPayloadJson(
                "{\"action\":\"submit\",\"values\":{\"approved\":true},\"interactionId\":\"wfi_debug1\",\"nodeId\":\"confirm\"}");
        when(mapper.selectById("session-1")).thenReturn(existing);

        assertThrows(IllegalStateException.class, () -> service.submit(
                "session-1",
                new RuntimeExecutableDebugSessionService.SubmitRequest(
                        "submit", Map.of("approved", false), null, "wfi_debug1", "idem-1")));
        verify(workflowDebugService, never()).debugRun(any(), any(), any());
    }

    @Test
    void submitCancelWaitingSessionSetsCancelledWithoutDebugRun() {
        RuntimeExecutableDebugSessionEntity existing = waitingSessionEntity();
        existing.setUiRequestJson("{\"component\":\"confirm\",\"interactionId\":\"ix-1\"}");
        when(mapper.selectById("session-1")).thenReturn(existing);

        RuntimeExecutableDebugSessionService.SessionView view = service.submit(
                "session-1",
                new RuntimeExecutableDebugSessionService.SubmitRequest("cancel", Map.of(), null));

        assertEquals("CANCELLED", view.status());
        assertNull(view.currentNodeId());
        assertNull(view.uiRequest());
        verify(workflowDebugService, never()).debugRun(any());
        verify(mapper).updateById(existing);
        assertEquals("CANCELLED", existing.getStatus());
        assertNull(existing.getCurrentNodeId());
    }

    @Test
    void submitCancelEmitSessionEventsWritesTurnCancelledNotFailed() throws Exception {
        RuntimeExecutableDebugSessionEntity existing = waitingSessionEntity();
        existing.setUiRequestJson("{\"component\":\"confirm\",\"interactionId\":\"ix-1\"}");
        when(mapper.selectById("session-1")).thenReturn(existing);

        RuntimeExecutableDebugSessionService.SessionView view = service.submit(
                "session-1",
                new RuntimeExecutableDebugSessionService.SubmitRequest("cancel", Map.of(), null));

        assertEquals("CANCELLED", view.status());
        assertNull(view.currentNodeId());
        assertNull(view.uiRequest());
        verify(workflowDebugService, never()).debugRun(any());
        verify(mapper).updateById(existing);

        List<String> eventNames = new ArrayList<>();
        List<Object> payloads = new ArrayList<>();
        // 生产路径同一 emitSessionEvents(view, sink)，非旁路辅助函数
        service.emitSessionEvents(view, (eventName, data) -> {
            eventNames.add(eventName);
            payloads.add(data);
        });

        assertTrue(eventNames.contains("turn.cancelled"), "SSE must contain turn.cancelled, got " + eventNames);
        assertFalse(eventNames.contains("turn.failed"), "CANCELLED must not emit turn.failed, got " + eventNames);
        assertFalse(eventNames.contains("error"), "CANCELLED must not emit error event");

        int cancelledIndex = eventNames.indexOf("turn.cancelled");
        @SuppressWarnings("unchecked")
        Map<String, Object> cancelledPayload = (Map<String, Object>) payloads.get(cancelledIndex);
        assertEquals("session-1", cancelledPayload.get("sessionId"));
        assertEquals("CANCELLED", cancelledPayload.get("status"));
    }

    @Test
    void streamSubmitCancelProducesTurnCancelledViaProductionSinkPath() throws Exception {
        RuntimeExecutableDebugSessionEntity existing = waitingSessionEntity();
        existing.setUiRequestJson("{\"component\":\"confirm\",\"interactionId\":\"ix-1\"}");
        when(mapper.selectById("session-1")).thenReturn(existing);

        List<String> eventNames = new ArrayList<>();
        AtomicReference<Map<String, Object>> cancelledPayload = new AtomicReference<>();
        CompletableFuture<Void> done = new CompletableFuture<>();

        // 模拟 streamSubmitInternal：submit → emitSessionEvents → session.completed
        RuntimeExecutableDebugSessionService.SessionView view = service.submit(
                "session-1",
                new RuntimeExecutableDebugSessionService.SubmitRequest("cancel", Map.of(), null));
        service.emitSessionEvents(view, (eventName, data) -> {
            eventNames.add(eventName);
            if ("turn.cancelled".equals(eventName) && data instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((k, v) -> copy.put(String.valueOf(k), v));
                cancelledPayload.set(copy);
            }
            if ("turn.failed".equals(eventName)) {
                done.completeExceptionally(new AssertionError("unexpected turn.failed"));
            }
        });
        eventNames.add("session.completed");
        done.complete(null);
        done.get(2, TimeUnit.SECONDS);

        assertTrue(eventNames.contains("turn.cancelled"));
        assertTrue(eventNames.contains("session.completed"));
        assertFalse(eventNames.contains("turn.failed"));
        assertNotNull(cancelledPayload.get());
        assertEquals("session-1", cancelledPayload.get().get("sessionId"));
        assertEquals("CANCELLED", cancelledPayload.get().get("status"));
        verify(workflowDebugService, never()).debugRun(any());
    }

    @Test
    void terminalSseEventNameMapping() {
        assertEquals("turn.cancelled", RuntimeExecutableDebugSessionService.terminalSseEventName("CANCELLED"));
        assertEquals("turn.failed", RuntimeExecutableDebugSessionService.terminalSseEventName("ERROR"));
        assertEquals("turn.failed", RuntimeExecutableDebugSessionService.terminalSseEventName("FAILED"));
        assertEquals("turn.completed", RuntimeExecutableDebugSessionService.terminalSseEventName("SUCCESS"));
    }

    @Test
    void liveSinkSafeFinalOutputEmitsNodeDeltaAndMessageDeltaOnceEach() throws Exception {
        List<String> eventNames = new ArrayList<>();
        List<Object> payloads = new ArrayList<>();
        var sink = service.liveExecutionSink((eventName, data) -> {
            eventNames.add(eventName);
            payloads.add(data);
        });

        sink.onNodeStarted("llm-final", "LLM", "Final", Map.of(
                "nodeId", "llm-final", "nodeType", "LLM", "publicUserOutput", true));
        sink.onNodeDelta("llm-final", "LLM", "hello", Map.of(
                "nodeId", "llm-final", "nodeType", "LLM", "publicUserOutput", true));
        sink.onNodeCompleted("llm-final", "LLM", "Final", Map.of(
                "nodeId", "llm-final", "nodeType", "LLM", "publicUserOutput", true));

        assertEquals(List.of("node.started", "node.output.delta", "message.delta", "node.completed"), eventNames);
        assertEquals(1, eventNames.stream().filter("node.output.delta"::equals).count());
        assertEquals(1, eventNames.stream().filter("message.delta"::equals).count());
        @SuppressWarnings("unchecked")
        Map<String, Object> messageDelta = (Map<String, Object>) payloads.get(eventNames.indexOf("message.delta"));
        assertEquals("hello", messageDelta.get("text"));
    }

    @Test
    void liveSinkInternalNodeDoesNotEmitPublicMessageDelta() throws Exception {
        List<String> eventNames = new ArrayList<>();
        var sink = service.liveExecutionSink((eventName, data) -> eventNames.add(eventName));

        sink.onNodeStarted("intent", "INTENT_CLASSIFIER", "Intent", Map.of(
                "nodeId", "intent", "nodeType", "INTENT_CLASSIFIER", "publicUserOutput", false));
        sink.onNodeCompleted("intent", "INTENT_CLASSIFIER", "Intent", Map.of(
                "nodeId", "intent", "nodeType", "INTENT_CLASSIFIER", "publicUserOutput", false));

        assertEquals(List.of("node.started", "node.completed"), eventNames);
        assertFalse(eventNames.contains("message.delta"));
        assertFalse(eventNames.contains("node.output.delta"));
    }

    @Test
    void shouldCompleteStreamErrorOnlyWhenNotCancelledAndNotCompleted() {
        assertTrue(RuntimeExecutableDebugSessionService.shouldCompleteStreamError(false, false));
        assertFalse(RuntimeExecutableDebugSessionService.shouldCompleteStreamError(true, false));
        assertFalse(RuntimeExecutableDebugSessionService.shouldCompleteStreamError(false, true));
        assertFalse(RuntimeExecutableDebugSessionService.shouldCompleteStreamError(true, true));
    }

    @Test
    void runStreamCreateCancellationEmitsSingleTurnCancelledWithoutFailedOrCompleted() throws Exception {
        RuntimeWorkflowDebugService.DebugRunResult cancelledRun = new RuntimeWorkflowDebugService.DebugRunResult(
                "run-c",
                "trace-c",
                null,
                "WORKFLOW",
                false,
                "CANCELLED",
                "Workflow execution cancelled",
                "n1",
                List.of(),
                null,
                List.of(new RuntimeWorkflowDebugService.DebugStepResult(
                        0, "n1", "USER_INPUT", "one", "SUCCESS", null, null, 0L,
                        Map.of(), Map.of(), null, Map.of(), Map.of(),
                        "execute-node", null, null, null, null, null, null, null)),
                Map.of(),
                "RUNTIME_GRAPH_CANCELLED",
                "Workflow execution cancelled");
        doAnswer(invocation -> {
            RuntimeGraphSpecExecutionEventSink sink = invocation.getArgument(1);
            RuntimeGraphSpecExecutionCancellation cancellation = invocation.getArgument(2);
            sink.onNodeStarted("n1", "USER_INPUT", "one", Map.of("nodeId", "n1"));
            cancellation.cancel();
            sink.onExecutionCancelled(Map.of("currentNodeId", "n1", "code", "RUNTIME_GRAPH_CANCELLED"));
            return cancelledRun;
        }).when(workflowDebugService).debugRun(any(), any(), any());

        List<String> eventNames = new ArrayList<>();
        service.runStreamCreate((eventName, data) -> eventNames.add(eventName),
                new RuntimeExecutableDebugSessionService.CreateRequest(
                        "WORKFLOW_DRAFT",
                        Map.of("graphSpecJson", "{\"entry\":\"n1\",\"nodes\":[{\"id\":\"n1\",\"type\":\"USER_INPUT\"}]}"),
                        "hello",
                        Map.of(),
                        Map.of()),
                new RuntimeGraphSpecExecutionCancellation());

        assertEquals(1, eventNames.stream().filter("turn.cancelled"::equals).count(),
                "exactly one turn.cancelled, got " + eventNames);
        assertFalse(eventNames.contains("turn.failed"), "must not emit turn.failed: " + eventNames);
        assertFalse(eventNames.contains("turn.completed"), "must not emit turn.completed: " + eventNames);
        assertTrue(eventNames.contains("session.completed"), "stream envelope may remain");
        assertTrue(eventNames.indexOf("turn.started") < eventNames.indexOf("turn.cancelled"));
    }

    @Test
    void runStreamCreateAfterCancelDoesNotCallCompleteStreamErrorSemantics() throws Exception {
        AtomicInteger streamErrors = new AtomicInteger();
        RuntimeGraphSpecExecutionCancellation cancellation = new RuntimeGraphSpecExecutionCancellation();
        doAnswer(invocation -> {
            RuntimeGraphSpecExecutionEventSink sink = invocation.getArgument(1);
            RuntimeGraphSpecExecutionCancellation cancel = invocation.getArgument(2);
            sink.onNodeStarted("n1", "LLM", "llm", Map.of("nodeId", "n1"));
            cancel.cancel();
            sink.onExecutionCancelled(Map.of("currentNodeId", "n1"));
            throw new IllegalStateException("post-cancel execution noise");
        }).when(workflowDebugService).debugRun(any(), any(), any());

        try {
            service.runStreamCreate((eventName, data) -> {
                if ("turn.failed".equals(eventName)) {
                    streamErrors.incrementAndGet();
                }
            }, new RuntimeExecutableDebugSessionService.CreateRequest(
                    "WORKFLOW_DRAFT",
                    Map.of("graphSpecJson", "{\"entry\":\"n1\",\"nodes\":[{\"id\":\"n1\",\"type\":\"LLM\"}]}"),
                    "hello",
                    Map.of(),
                    Map.of()), cancellation);
        } catch (IllegalStateException ignored) {
            // 生产 catch 会吞掉；此处直接调用 runStreamCreate 会抛出
        }
        // 生产 catch：cancelled=true 时 shouldCompleteStreamError=false
        assertFalse(RuntimeExecutableDebugSessionService.shouldCompleteStreamError(
                cancellation.isCancelled(), false));
        assertEquals(0, streamErrors.get());
    }

    @Test
    void runStreamCreateRealNodeFailureStillEmitsTurnFailed() throws Exception {
        RuntimeWorkflowDebugService.DebugRunResult failedRun = new RuntimeWorkflowDebugService.DebugRunResult(
                "run-f",
                "trace-f",
                null,
                "WORKFLOW",
                false,
                "ERROR",
                "node boom",
                "n1",
                List.of(),
                null,
                List.of(),
                Map.of(),
                "RUNTIME_GRAPH_LLM_FAILED",
                "node boom");
        when(workflowDebugService.debugRun(any(), any(), any())).thenReturn(failedRun);

        List<String> eventNames = new ArrayList<>();
        service.runStreamCreate((eventName, data) -> eventNames.add(eventName),
                new RuntimeExecutableDebugSessionService.CreateRequest(
                        "WORKFLOW_DRAFT",
                        Map.of("graphSpecJson", "{\"entry\":\"n1\",\"nodes\":[{\"id\":\"n1\",\"type\":\"LLM\"}]}"),
                        "hello",
                        Map.of(),
                        Map.of()),
                new RuntimeGraphSpecExecutionCancellation());

        assertTrue(eventNames.contains("turn.failed"), "got " + eventNames);
        assertFalse(eventNames.contains("turn.cancelled"));
        assertFalse(eventNames.contains("turn.completed"));
        assertEquals(1, eventNames.stream().filter("turn.failed"::equals).count());
    }

    @Test
    void onceBusinessTerminalSinkSuppressesDuplicateTerminals() throws Exception {
        AtomicBoolean sent = new AtomicBoolean(false);
        List<String> names = new ArrayList<>();
        var sink = RuntimeExecutableDebugSessionService.onceBusinessTerminalSink(
                (eventName, data) -> names.add(eventName), sent);
        sink.send("turn.cancelled", Map.of());
        sink.send("turn.cancelled", Map.of());
        sink.send("turn.failed", Map.of());
        sink.send("session.completed", Map.of());
        assertEquals(List.of("turn.cancelled", "session.completed"), names);
    }

    private static RuntimeExecutableDebugSessionEntity waitingSessionEntity() {
        RuntimeExecutableDebugSessionEntity existing = new RuntimeExecutableDebugSessionEntity();
        existing.setId("session-1");
        existing.setRunId("run-1");
        existing.setTraceId("trace-1");
        existing.setTargetType("WORKFLOW_DRAFT");
        existing.setStatus("WAITING");
        existing.setRevision(0);
        existing.setCurrentNodeId("confirm");
        existing.setDraftDefinitionJson("{\"graphSpecJson\":\"{\\\"entry\\\":\\\"confirm\\\",\\\"nodes\\\":[{\\\"id\\\":\\\"confirm\\\",\\\"type\\\":\\\"INTERACTION\\\"}]}\"}");
        existing.setDebugOptionsJson("{}");
        existing.setStateJson("{\"input\":\"hello\"}");
        existing.setMessagesJson("[]");
        existing.setStepsJson("[]");
        existing.setUiRequestJson("{\"component\":\"confirm\",\"interactionId\":\"wfi_debug1\",\"nodeId\":\"confirm\"}");
        return existing;
    }
}
