package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionEventSink;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolSnapshot;
import com.enterprise.ai.runtime.chat.RuntimeChatMemoryStore;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelUsage;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.execution.RuntimeWorkflowInteractionSessionService;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Workflow-as-Tool 必须接入请求级取消；取消不得伪装为 TIMEOUT。
 */
class SupervisorWorkflowCancellationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeWorkflowDefinitionMapper workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
    private final RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
    private final RuntimeGraphSpecExecutor graphExecutor = mock(RuntimeGraphSpecExecutor.class);
    private final SupervisorExecutionTraceService traceService = mock(SupervisorExecutionTraceService.class);
    private final RuntimeWorkflowInteractionSessionService interactionSessionService =
            mock(RuntimeWorkflowInteractionSessionService.class);
    private final Map<String, RuntimeWorkflowDefinitionEntity> workflowTargets = new LinkedHashMap<>();
    private final Map<Long, RuntimeWorkflowVersionEntity> workflowVersions = new LinkedHashMap<>();

    @Test
    void cancelBeforeWorkflowDoesNotCallGraphExecutor() {
        RuntimeAgentWorkflowToolSnapshot tool = tool("wf-1", "query_team");
        stubWorkflow(tool);
        RuntimeAgentExecutionCancellation cancellation = new RuntimeAgentExecutionCancellation();
        cancellation.cancel();
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(request -> {
            throw new AssertionError("cancelled run must not call model");
        });

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(tool),
                        Map.of("message", "查询", "sessionId", "s-pre", "projectCode", "qmssmp"),
                        null, RuntimeAgentExecutionEventSink.NOOP, cancellation));

        assertFalse(result.success());
        assertEquals("SUPERVISOR_CANCELLED", result.code());
        verify(graphExecutor, never()).execute(any(), any(), any(), any(), any(), any());
    }

    @Test
    void cancelDuringWorkflowDoesNotMapToTimeoutAndDoesNotWriteSuccessMemory() throws Exception {
        RuntimeAgentWorkflowToolSnapshot tool = tool("wf-1", "query_team");
        stubWorkflow(tool);
        RuntimeAgentExecutionCancellation cancellation = new RuntimeAgentExecutionCancellation();
        CountDownLatch inWorkflow = new CountDownLatch(1);
        AtomicInteger graphCalls = new AtomicInteger();
        when(graphExecutor.execute(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            graphCalls.incrementAndGet();
            RuntimeGraphSpecExecutionCancellation workflowCancel = invocation.getArgument(3);
            inWorkflow.countDown();
            long deadline = System.currentTimeMillis() + 5_000;
            while (!workflowCancel.isCancelled() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            return new RuntimeGraphSpecExecutionResult(false, "RUNTIME_GRAPH_CANCELLED",
                    "Workflow execution cancelled", null, null, List.of(), Map.of());
        });

        RuntimeChatMemoryStore memory = new RuntimeChatMemoryStore(20);
        AgentScopeSupervisorRuntimeAdapter adapter = adapterWithMemory(modelForPlanAndTool(), memory);
        java.util.concurrent.atomic.AtomicReference<SupervisorRuntimeAdapter.SupervisorResult> resultRef =
                new java.util.concurrent.atomic.AtomicReference<>();

        Thread runner = new Thread(() -> resultRef.set(adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(tool),
                        Map.of("message", "查询班组", "sessionId", "s-mid", "projectCode", "qmssmp"),
                        null, RuntimeAgentExecutionEventSink.NOOP, cancellation))));
        runner.start();
        assertTrue(inWorkflow.await(5, TimeUnit.SECONDS));
        cancellation.cancel();
        runner.join(10_000);

        assertEquals(1, graphCalls.get());
        assertTrue(memory.getHistory("s-mid").isEmpty(), "cancelled run must not write success chat memory");
        SupervisorRuntimeAdapter.SupervisorResult result = resultRef.get();
        assertFalse(result == null || result.success());
        assertEquals("SUPERVISOR_CANCELLED", result.code());
        assertFalse("SUPERVISOR_WORKFLOW_TIMEOUT".equals(result.code()));
    }

    @Test
    void parallelWorkflowsAreAllCancelledOnRequestCancel() throws Exception {
        RuntimeAgentWorkflowToolSnapshot teamTool = tool("wf-team", "query_team");
        RuntimeAgentWorkflowToolSnapshot ownerTool = tool("wf-owner", "query_owner");
        stubWorkflow(teamTool);
        stubWorkflow(ownerTool);
        RuntimeAgentExecutionCancellation cancellation = new RuntimeAgentExecutionCancellation();
        CountDownLatch bothStarted = new CountDownLatch(2);
        List<RuntimeGraphSpecExecutionCancellation> captured =
                Collections.synchronizedList(new ArrayList<>());
        when(graphExecutor.execute(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            RuntimeGraphSpecExecutionCancellation workflowCancel = invocation.getArgument(3);
            captured.add(workflowCancel);
            bothStarted.countDown();
            long deadline = System.currentTimeMillis() + 5_000;
            while (!workflowCancel.isCancelled() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            return new RuntimeGraphSpecExecutionResult(false, "RUNTIME_GRAPH_CANCELLED",
                    "Workflow execution cancelled", null, null, List.of(), Map.of());
        });

        AgentScopeSupervisorRuntimeAdapter adapter = adapter(modelForParallelTools());
        Thread runner = new Thread(() -> adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(true), List.of(teamTool, ownerTool),
                        Map.of("message", "并行查询", "sessionId", "s-par", "projectCode", "qmssmp"),
                        null, RuntimeAgentExecutionEventSink.NOOP, cancellation)));
        runner.start();
        assertTrue(bothStarted.await(5, TimeUnit.SECONDS), "both parallel workflows should start");
        cancellation.cancel();
        runner.join(10_000);

        assertEquals(2, captured.size());
        assertTrue(captured.stream().allMatch(RuntimeGraphSpecExecutionCancellation::isCancelled));
    }

    @Test
    void cancelAfterSuccessfulWorkflowIsIdempotent() {
        RuntimeAgentWorkflowToolSnapshot tool = tool("wf-1", "query_team");
        stubWorkflow(tool);
        when(graphExecutor.execute(any(), any(), any(), any(), any(), any())).thenReturn(
                new RuntimeGraphSpecExecutionResult(true, "OK", "done", null, null, List.of(), Map.of()));
        RuntimeAgentExecutionCancellation cancellation = new RuntimeAgentExecutionCancellation();
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(modelForPlanAndToolThenAnswer());

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(tool),
                        Map.of("message", "查询", "sessionId", "s-done", "projectCode", "qmssmp"),
                        null, RuntimeAgentExecutionEventSink.NOOP, cancellation));

        assertTrue(result.success());
        cancellation.cancel();
        cancellation.cancel();
        assertTrue(result.success());
        assertEquals("SUPERVISOR_COMPLETED", result.code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", WorkflowInteractionCodes.WAITING, "LATE_FAILURE"})
    void timedOutWorkflowCancelsExecutorAndDiscardsLateResult(String lateCode) throws Exception {
        RuntimeAgentWorkflowToolSnapshot tool = tool("wf-1", "query_team");
        stubWorkflow(tool);
        CountDownLatch releaseGraph = new CountDownLatch(1);
        AtomicReference<RuntimeGraphSpecExecutionCancellation> graphCancellation = new AtomicReference<>();
        AtomicReference<Thread> graphThread = new AtomicReference<>();
        List<String> tracedCodes = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            tracedCodes.add(invocation.getArgument(10));
            return null;
        }).when(traceService).workflow(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
        when(interactionSessionService.createWaitingSession(any())).thenReturn(
                new RuntimeWorkflowInteractionSessionService.WaitingSession("late-interaction", "{}"));
        when(interactionSessionService.readMap(any())).thenReturn(Map.of("late", true));
        when(graphExecutor.execute(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            graphCancellation.set(invocation.getArgument(3));
            graphThread.set(Thread.currentThread());
            // Model a dependency that cannot be stopped with Thread.interrupt().
            awaitIgnoringInterrupts(releaseGraph);
            return new RuntimeGraphSpecExecutionResult("SUCCESS".equals(lateCode), lateCode,
                    "late workflow result", "answer", "ANSWER", List.of(),
                    Map.of("uiRequest", Map.of("late", true)), Map.of("answer", "late"));
        });
        RuntimeModelServiceClient initialModel = modelForPlanAndTool();
        AtomicInteger rounds = new AtomicInteger();
        RuntimeModelServiceClient model = request -> {
            int round = rounds.incrementAndGet();
            if (round <= 2) return initialModel.chat(request);
            if (round == 3) {
                releaseGraph.countDown();
                awaitWorkflowReturn(graphThread.get());
                return toolCalls(List.of(Map.of("id", "abandon", "type", "function", "function", Map.of(
                        "name", "record_supervisor_plan", "arguments",
                        "{\"summary\":\"Report the timeout\",\"steps\":[\"Explain timeout\"],\"workflowToolNames\":[]}"))));
            }
            if (round == 4) return toolCalls(List.of(Map.of("id", "final", "type", "function",
                    "function", Map.of("name", "begin_final_answer", "arguments", "{}"))));
            return text("The workflow timed out.");
        };
        RuntimeAgentConfigSnapshot config = config(false).toBuilder().workflowTimeoutMs(1_000).build();
        RuntimeAgentExecutionCancellation cancellation = new RuntimeAgentExecutionCancellation();
        SupervisorRuntimeAdapter.SupervisorResult result;
        try {
            result = adapter(model).execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                    agent(), config, List.of(tool), Map.of("message", "query", "sessionId", "s-timeout"),
                    null, RuntimeAgentExecutionEventSink.NOOP, cancellation,
                    WorkflowExecutionIdentity.fromAgent("tenant-a", 7L, "qmssmp", "user-a")));
        } finally {
            releaseGraph.countDown();
            awaitWorkflowReturn(graphThread.get());
        }
        assertAll(
                () -> assertTrue(graphCancellation.get() != null && graphCancellation.get().isCancelled(),
                        "the workflow deadline must reach the executor's cooperative cancellation"),
                () -> assertFalse(cancellation.isCancelled(), "a local timeout must not cancel the Agent request"),
                () -> assertEquals(List.of("SUPERVISOR_WORKFLOW_TIMEOUT"), tracedCodes,
                        "a late outcome must not add another workflow trace"),
                () -> verifyNoInteractions(interactionSessionService),
                () -> assertTrue(result.success(), result.code() + " " + result.answer()),
                () -> assertEquals(null, result.uiRequest(), "late presentation must not survive in the final response"));
    }

    @Test
    void lateAttemptDoesNotCancelOrReplaceTheRetriedWorkflowResult() throws Exception {
        RuntimeAgentWorkflowToolSnapshot tool = tool("wf-1", "query_team");
        stubWorkflow(tool);
        CountDownLatch releaseFirstGraph = new CountDownLatch(1);
        AtomicReference<Thread> firstThread = new AtomicReference<>();
        List<RuntimeGraphSpecExecutionCancellation> graphCancellations =
                Collections.synchronizedList(new ArrayList<>());
        List<String> tracedCodes = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            tracedCodes.add(invocation.getArgument(10));
            return null;
        }).when(traceService).workflow(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
        when(graphExecutor.execute(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            graphCancellations.add(invocation.getArgument(3));
            if (graphCancellations.size() == 1) {
                firstThread.set(Thread.currentThread());
                awaitIgnoringInterrupts(releaseFirstGraph);
                return new RuntimeGraphSpecExecutionResult(false, "LATE_FAILURE", "old attempt", null, null,
                        List.of(), Map.of());
            }
            releaseFirstGraph.countDown();
            awaitWorkflowReturn(firstThread.get());
            return new RuntimeGraphSpecExecutionResult(true, "SUCCESS", "fresh result", null, null,
                    List.of(), Map.of("uiRequest", Map.of("fresh", true)));
        });
        RuntimeModelServiceClient initialModel = modelForPlanAndTool();
        AtomicInteger rounds = new AtomicInteger();
        RuntimeModelServiceClient model = request -> {
            int round = rounds.incrementAndGet();
            if (round <= 2) return initialModel.chat(request);
            if (round == 3) return toolCalls(List.of(Map.of("id", "retry-plan", "type", "function",
                    "function", Map.of("name", "record_supervisor_plan", "arguments",
                            "{\"summary\":\"Retry read\",\"steps\":[\"Query again\"],\"workflowToolNames\":[\"query_team\"]}"))));
            if (round == 4) return toolCalls(List.of(Map.of("id", "retry", "type", "function",
                    "function", Map.of("name", "query_team", "arguments", "{}"))));
            if (round == 5) return toolCalls(List.of(Map.of("id", "final", "type", "function",
                    "function", Map.of("name", "begin_final_answer", "arguments", "{}"))));
            return text("Fresh result received.");
        };
        SupervisorRuntimeAdapter.SupervisorResult result;
        try {
            result = adapter(model).execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                    agent(), config(false).toBuilder().workflowTimeoutMs(1_000).build(), List.of(tool),
                    Map.of("message", "query", "sessionId", "s-retry"), null,
                    RuntimeAgentExecutionEventSink.NOOP, new RuntimeAgentExecutionCancellation()));
        } finally {
            releaseFirstGraph.countDown();
            awaitWorkflowReturn(firstThread.get());
        }
        assertEquals(2, graphCancellations.size(), "a revised read-only plan must permit one retry");
        assertAll(
                () -> assertTrue(graphCancellations.get(0).isCancelled(), "only the expired invocation is cancelled"),
                () -> assertFalse(graphCancellations.get(1).isCancelled(), "retry owns a distinct cancellation"),
                () -> assertEquals(List.of("SUPERVISOR_WORKFLOW_TIMEOUT", "SUCCESS"), tracedCodes),
                () -> assertTrue(result.success(), result.code() + " " + result.answer()),
                () -> assertEquals(Map.of("fresh", true), result.uiRequest()),
                () -> assertEquals(2, result.metadata().get("planCount")),
                () -> assertEquals(1, result.metadata().get("plannedWorkflowCursor")));
    }

    @Test
    void workflowTimeoutDoesNotDeliverStartedAfterFailedWhenTheEventSinkIsSlow() throws Exception {
        RuntimeAgentWorkflowToolSnapshot tool = tool("wf-1", "query_team");
        stubWorkflow(tool);
        CountDownLatch releaseStarted = new CountDownLatch(1);
        AtomicReference<Thread> phaseThread = new AtomicReference<>();
        List<Map<String, Object>> delivered = Collections.synchronizedList(new ArrayList<>());
        RuntimeAgentExecutionEventSink sink = (name, data) -> {
            if (!"supervisor.step".equals(name) || !(data instanceof Map<?, ?> phase)
                    || !"workflow-1".equals(phase.get("stepId"))) return;
            if ("started".equals(phase.get("state"))) {
                phaseThread.set(Thread.currentThread());
                try { awaitIgnoringInterrupts(releaseStarted); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }
            Map<String, Object> received = new LinkedHashMap<>();
            phase.forEach((key, value) -> received.put(String.valueOf(key), value));
            delivered.add(received);
        };
        when(graphExecutor.execute(any(), any(), any(), any(), any(), any())).thenReturn(
                new RuntimeGraphSpecExecutionResult(false, "RUNTIME_GRAPH_CANCELLED", "cancelled", null, null,
                        List.of(), Map.of()));
        RuntimeModelServiceClient initialModel = modelForPlanAndTool();
        AtomicInteger rounds = new AtomicInteger();
        RuntimeModelServiceClient model = request -> {
            int round = rounds.incrementAndGet();
            if (round <= 2) return initialModel.chat(request);
            if (round == 3) {
                // The timeout must release the tool result while the first event consumer is slow.
                releaseStarted.countDown();
                awaitWorkflowReturn(phaseThread.get());
                return toolCalls(List.of(Map.of("id", "abandon", "type", "function", "function", Map.of(
                        "name", "record_supervisor_plan", "arguments",
                        "{\"summary\":\"Report timeout\",\"steps\":[\"Explain timeout\"],\"workflowToolNames\":[]}"))));
            }
            if (round == 4) return toolCalls(List.of(Map.of("id", "final", "type", "function",
                    "function", Map.of("name", "begin_final_answer", "arguments", "{}"))));
            return text("The workflow timed out.");
        };
        SupervisorRuntimeAdapter.SupervisorResult result;
        try {
            result = adapter(model).execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                    agent(), config(false).toBuilder().workflowTimeoutMs(1_000).build(), List.of(tool),
                    Map.of("message", "query", "sessionId", "s-phase-order"), null, sink,
                    new RuntimeAgentExecutionCancellation()));
        } finally {
            releaseStarted.countDown();
            awaitWorkflowReturn(phaseThread.get());
        }
        assertTrue(result.success(), result.code() + " " + result.answer());
        assertEquals("failed", result.steps().stream().filter(step -> "workflow-1".equals(step.get("stepId")))
                .findFirst().orElseThrow().get("state"));
        assertEquals(List.of("started", "failed"), delivered.stream().map(step -> step.get("state")).toList(),
                "public progress must not regress after the workflow has failed");
        assertEquals(1, delivered.stream().map(step -> step.get("sequence")).distinct().count());
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (latch.getCount() != 0 && System.nanoTime() < deadline) {
            try {
                if (latch.await(10, TimeUnit.MILLISECONDS)) return;
            } catch (InterruptedException ignored) {
                // Deliberate test behavior: returning late is independent of Reactor interruption.
            }
        }
        assertEquals(0, latch.getCount(), "test must release the non-cooperative dependency");
    }

    private static void awaitWorkflowReturn(Thread thread) {
        if (thread == null) return;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            boolean running = java.util.Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                    frame.getClassName().equals(AgentScopeSupervisorRuntimeAdapter.class.getName() + "$RunState")
                            && frame.getMethodName().equals("runWorkflow"));
            if (!running) return;
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        throw new AssertionError("workflow callable did not finish after the dependency returned");
    }

    private RuntimeModelServiceClient modelForPlanAndTool() {
        AtomicInteger n = new AtomicInteger();
        return request -> {
            int call = n.incrementAndGet();
            if (call == 1) {
                return toolCalls(List.of(Map.of(
                        "id", "p1", "type", "function",
                        "function", Map.of(
                                "name", "record_supervisor_plan",
                                "arguments", "{\"summary\":\"查\",\"steps\":[\"查询班组\"],\"workflowToolNames\":[\"query_team\"]}"))));
            }
            if (call == 2) {
                return toolCalls(List.of(Map.of(
                        "id", "w1", "type", "function",
                        "function", Map.of("name", "query_team", "arguments", "{}"))));
            }
            return text("should-not-answer");
        };
    }

    private RuntimeModelServiceClient modelForPlanAndToolThenAnswer() {
        AtomicInteger n = new AtomicInteger();
        return request -> {
            int call = n.incrementAndGet();
            if (call == 1) {
                return toolCalls(List.of(Map.of(
                        "id", "p1", "type", "function",
                        "function", Map.of(
                                "name", "record_supervisor_plan",
                                "arguments", "{\"summary\":\"查\",\"steps\":[\"查询班组\"],\"workflowToolNames\":[\"query_team\"]}"))));
            }
            if (call == 2) {
                return toolCalls(List.of(Map.of(
                        "id", "w1", "type", "function",
                        "function", Map.of("name", "query_team", "arguments", "{}"))));
            }
            if (call == 3) {
                return toolCalls(List.of(Map.of(
                        "id", "f1", "type", "function",
                        "function", Map.of("name", "begin_final_answer", "arguments", "{}"))));
            }
            return text("完成");
        };
    }

    private RuntimeModelServiceClient modelForParallelTools() {
        AtomicInteger n = new AtomicInteger();
        return request -> {
            int call = n.incrementAndGet();
            if (call == 1) {
                return toolCalls(List.of(Map.of(
                        "id", "p1", "type", "function",
                        "function", Map.of(
                                "name", "record_supervisor_plan",
                                "arguments", "{\"summary\":\"并行\",\"steps\":[\"班组\",\"负责人\"],\"workflowToolNames\":[\"query_team\",\"query_owner\"]}"))));
            }
            if (call == 2) {
                return toolCalls(List.of(
                        Map.of("id", "t1", "type", "function",
                                "function", Map.of("name", "query_team", "arguments", "{}")),
                        Map.of("id", "o1", "type", "function",
                                "function", Map.of("name", "query_owner", "arguments", "{}"))));
            }
            return text("should-not");
        };
    }

    private ModelChatResult toolCalls(List<Map<String, Object>> calls) {
        try {
            return new ModelChatResult(200, "success",
                    new ModelChatData(null, "test", "test", new ModelUsage(1, 1, 2),
                            null, objectMapper.valueToTree(calls), "tool_calls"));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private ModelChatResult text(String content) {
        return new ModelChatResult(200, "success",
                new ModelChatData(content, "test", "test", new ModelUsage(1, 1, 2),
                        null, null, "stop"));
    }

    private AgentScopeSupervisorRuntimeAdapter adapter(RuntimeModelServiceClient modelClient) {
        return adapterWithMemory(modelClient, new RuntimeChatMemoryStore(20));
    }

    private AgentScopeSupervisorRuntimeAdapter adapterWithMemory(RuntimeModelServiceClient modelClient,
                                                                 RuntimeChatMemoryStore memory) {
        SupervisorExecutionTraceService.TraceHandle handle =
                new SupervisorExecutionTraceService.TraceHandle("trace-1", "span-1", 1L, LocalDateTime.now());
        when(traceService.begin(any(), any(), any(), any())).thenReturn(handle);
        when(traceService.begin(any(), any(), any(), any(), any())).thenReturn(handle);
        when(traceService.beginOrResume(any(), any(), any(), any())).thenReturn(handle);
        when(traceService.beginOrResume(any(), any(), any(), any(), any())).thenReturn(handle);
        SupervisorToolPolicyService policy = new SupervisorToolPolicyService(
                org.mockito.Mockito.mock(com.enterprise.ai.runtime.runops.RuntimeGuardDecisionWriter.class), mock(SupervisorApprovalInteractionService.class), objectMapper);
        return new AgentScopeSupervisorRuntimeAdapter(
                modelClient, null, null, new com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionReader(workflowMapper, versionMapper), graphExecutor,
                interactionSessionService,
                memory, policy, traceService, objectMapper);
    }

    private void stubWorkflow(RuntimeAgentWorkflowToolSnapshot tool) {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId(tool.getWorkflowId());
        workflow.setKeySlug(tool.getToolName());
        workflow.setName(tool.getToolName());
        workflow.setDescription(tool.getToolName());
        workflow.setInputSchemaJson("{\"type\":\"object\",\"additionalProperties\":true}");
        workflow.setStatus("ACTIVE");
        RuntimeWorkflowVersionEntity version = new RuntimeWorkflowVersionEntity();
        version.setId((long) Math.abs(tool.getWorkflowId().hashCode()));
        version.setWorkflowId(tool.getWorkflowId());
        version.setVersion("1.0.0");
        version.setStatus("ACTIVE");
        version.setSnapshotJson("{\"defaultModelInstanceId\":null}");
        version.setGraphSpecSnapshotJson("{\"entryNodeId\":\"a\",\"nodes\":[{\"id\":\"a\",\"type\":\"ANSWER\"}]}");
        when(workflowMapper.selectById(tool.getWorkflowId())).thenReturn(workflow);
        when(versionMapper.selectById(version.getId())).thenReturn(version);
        workflowTargets.put(workflow.getId(), workflow);
        workflowVersions.put(version.getId(), version);
        when(workflowMapper.selectBatchIds(any())).thenAnswer(invocation -> {
            List<?> ids = invocation.getArgument(0);
            if (ids == null) return List.copyOf(workflowTargets.values());
            return ids.stream().map(String::valueOf).map(workflowTargets::get)
                    .filter(java.util.Objects::nonNull).toList();
        });
        when(versionMapper.selectBatchIds(any())).thenAnswer(invocation -> {
            List<?> ids = invocation.getArgument(0);
            if (ids == null) return List.copyOf(workflowVersions.values());
            return ids.stream().filter(Number.class::isInstance).map(Number.class::cast)
                    .map(Number::longValue).map(workflowVersions::get)
                    .filter(java.util.Objects::nonNull).toList();
        });
    }

    private RuntimeAgentWorkflowToolSnapshot tool(String workflowId, String toolName) {
        RuntimeAgentWorkflowToolSnapshot tool = RuntimeAgentWorkflowToolSnapshot.builder()
                .agentId("agent-1")
                .agentConfigVersionId(7L)
                .workflowId(workflowId)
                .workflowVersionId((long) Math.abs(workflowId.hashCode()))
                .toolName(toolName)
                .riskLevel("READ")
                .permissionKey(toolName + ":read")
                .enabled(true)
                .readOnly(true)
                .build();
        return tool;
    }

    private RuntimeAgentView agent() {
        return new RuntimeAgentView(
                "agent-1", 7L, "qmssmp", "team-assistant", "班组助手", null,
                "PROJECT", null, true,
                7L, 7L, 1, "ACTIVE", "AGENTSCOPE", 2, null, null);
    }

    private RuntimeAgentConfigSnapshot config(boolean parallelReadOnly) {
        RuntimeAgentConfigSnapshot config = RuntimeAgentConfigSnapshot.builder()
                .id(7L)
                .agentId("agent-1")
                .versionNo(1)
                .runtimeType("AGENTSCOPE")
                .systemPrompt("你是班组助手")
                .modelInstanceId("model-1")
                .maxPlanSteps(6)
                .maxWorkflowCalls(4)
                .maxReplans(1)
                .totalTimeoutMs(15_000)
                .workflowTimeoutMs(10_000)
                .pageBridgeTimeoutMs(2_000)
                .parallelReadOnly(parallelReadOnly)
                .policyProfile("DEV_ALLOW_ALL")
                .toolCatalogMode("ALLOW_LIST")
                .configJson("{}")
                .build();
        return config;
    }
}
