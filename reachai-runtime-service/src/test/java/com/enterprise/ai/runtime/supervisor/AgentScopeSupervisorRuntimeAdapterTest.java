package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionEventSink;
import com.enterprise.ai.runtime.execution.policy.RuntimeEvalExecutionContext;
import com.enterprise.ai.runtime.configuration.RuntimeContextEngineeringProperties;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolSnapshot;
import com.enterprise.ai.runtime.chat.RuntimeChatMemoryStore;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelUsage;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.memory.RuntimeSessionMemoryService;
import com.enterprise.ai.runtime.memory.RuntimeToolResultArtifactService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentScopeSupervisorRuntimeAdapterTest {

    @Test
    void returnsConfirmationWithoutAnotherModelRoundAfterCreatingTheCard() throws Exception {
        var tool = tool("wf-confirm", "confirm_inventory").toBuilder().riskLevel("WRITE").readOnly(false).build();
        stubWorkflow(tool);
        when(approvalService.create(any(), any(), any(), any(RuntimeAgentWorkflowToolSnapshot.class), any(), any(), any(), any()))
                .thenReturn(new SupervisorApprovalInteractionService.ApprovalRequest("spv-pause", Map.of("type", "CONFIRM")));
        var responses = List.of(calls(call("plan", "record_supervisor_plan", Map.of(
                        "summary", "inventory", "steps", List.of("inventory"), "workflowToolNames", List.of("confirm_inventory")))),
                calls(call("inventory", "confirm_inventory", Map.of("sku", "SKU-001"))));
        AtomicInteger rounds = new AtomicInteger();
        var runtime = adapter(request -> {
            int round = rounds.getAndIncrement();
            return new ModelChatResult(200, "success", round < responses.size() ? responses.get(round) : text("Please confirm"));
        });
        var result = runtime.execute(new SupervisorRuntimeAdapter.SupervisorRequest(agent(), config(false), List.of(tool),
                Map.of("message", "inventory", "sessionId", "confirmation-pause")));
        assertEquals("SUPERVISOR_CONFIRMATION_REQUIRED", result.code());
        assertEquals(2, rounds.get(), "A durable confirmation must pause without another model request");
        assertEquals(2, result.metadata().get("modelRoundCount"), "A paused turn must retain its actual model diagnostics");
        assertEquals(Map.of("type", "CONFIRM"), result.uiRequest());
        verifyNoInteractions(graphExecutor);
    }

    @Test
    void persistentTurnNeverLoadsAnonymousDefaultAgentState() {
        var memoryService = mock(RuntimeSessionMemoryService.class);
        var key = new com.enterprise.ai.runtime.memory.RuntimeSessionMemoryKey(
                true, "default", "1", "agent-1", "public-session", "trusted-user-key", "trusted-session-key", "AGENT");
        when(memoryService.resolve(any(), any(), any())).thenReturn(key);
        when(memoryService.acquireTurn(any(), any(), any())).thenReturn("lease");
        var store = mock(io.agentscope.core.state.AgentStateStore.class);
        var saved = io.agentscope.core.state.AgentState.builder()
                .userId(key.stateUserKey()).sessionId(key.stateSessionKey()).build();
        saved.setShutdownInterrupted(true);
        List<String> invalid = new ArrayList<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            String user = invocation.getArgument(0), session = invocation.getArgument(1);
            if (!key.stateUserKey().equals(user) || !key.stateSessionKey().equals(session)) {
                invalid.add(user + "/" + session + " via " + StackWalker.getInstance().walk(frames -> frames
                        .filter(f -> f.getClassName().startsWith("io.agentscope"))
                        .limit(8).map(f -> f.getClassName() + "." + f.getMethodName()).toList()));
                throw new com.enterprise.ai.runtime.memory.RuntimeSessionOwnershipException("AgentScope state access escaped the leased session slot");
            }
            return java.util.Optional.of(saved);
        }).when(store).get(org.mockito.ArgumentMatchers.nullable(String.class), any(), any(), any());
        when(memoryService.stateStoreForTurn(key, "lease")).thenReturn(store);
        var result = adapter(model(List.of(text("Hello"))), null, memoryService).execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(agent(), config(false), List.of(), Map.of("message", "Hello")));
        assertTrue(result.success(), result.code());
        assertTrue(invalid.isEmpty(), "Unexpected anonymous state access: " + invalid);
        assertFalse(saved.isShutdownInterrupted(), "The shutdown check must consume the active owned state's marker");
    }

    @Test
    @SuppressWarnings("unchecked")
    void approvalPersistsPlanAndResumesExactArgumentsWithoutRepeatingCompletedSteps() throws Exception {
        var first = tool("wf-first", "first_query");
        var approved = tool("wf-write", "write_inventory").toBuilder().riskLevel("WRITE").readOnly(false).build();
        var last = tool("wf-last", "last_query");
        List.of(first, approved, last).forEach(this::stubWorkflow);
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenReturn(success("done"));
        AtomicReference<Map<String, Object>> savedInput = new AtomicReference<>();
        when(approvalService.create(any(), any(), any(), any(RuntimeAgentWorkflowToolSnapshot.class), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    savedInput.set(objectMapper.convertValue(invocation.getArgument(4), Map.class));
                    return new SupervisorApprovalInteractionService.ApprovalRequest("spv-multi", Map.of("type", "CONFIRM"));
                });
        var initial = adapter(model(List.of(
                calls(call("plan", "record_supervisor_plan", Map.of("summary", "three steps", "steps", List.of("read", "write", "read"),
                        "workflowToolNames", List.of("first_query", "write_inventory", "last_query")))),
                calls(call("out-of-order-write", "write_inventory", Map.of("sku", "FORGED"))),
                calls(call("first", "first_query", Map.of())),
                calls(call("write", "write_inventory", Map.of("sku", "SKU-001"))), text("Please confirm"))));
        var waiting = initial.execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                agent(), config(false), List.of(first, approved, last), Map.of("message", "Update inventory")));
        assertEquals("SUPERVISOR_CONFIRMATION_REQUIRED", waiting.code());
        assertEquals(1, waiting.metadata().get("workflowCallCount"));
        var continuation = (Map<?, ?>) savedInput.get().get("__supervisorContinuation");
        assertEquals(1, continuation.get("plannedWorkflowCursor"));
        assertEquals(List.of("first_query"), continuation.get("completedWorkflowToolNames"));
        var grant = new com.enterprise.ai.runtime.execution.RuntimeSupervisorApprovalPort.PolicyApprovalGrant(
                "spv-multi", approved.getPermissionKey(), approved.getToolName(), Map.of("sku", "SKU-001"), "1");
        var resumed = adapter(model(List.of(
                calls(call("repeat-first", "first_query", Map.of())),
                calls(call("change-approved", "write_inventory", Map.of("sku", "FORGED"))),
                calls(call("last", "last_query", Map.of())),
                calls(call("final", "begin_final_answer", Map.of())), text("done"))));
        var result = resumed.execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                agent(), config(false), List.of(first, approved, last), savedInput.get(), grant));
        assertTrue(result.success(), result.code() + " " + result.metadata());
        assertEquals(3, result.metadata().get("workflowCallCount"));
        assertEquals(1, result.metadata().get("planCount"));
        var inputs = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(graphExecutor, org.mockito.Mockito.times(3)).execute(any(), inputs.capture(), any(), any(), any());
        assertEquals("SKU-001", inputs.getAllValues().get(1).get("sku"));
        verify(approvalService).create(any(), any(), any(), any(RuntimeAgentWorkflowToolSnapshot.class), any(), any(), any(), any());
    }

    @Test
    void confirmedWorkflowExecutesBeforeModelCanSkipTheApprovedAction() {
        var tool = tool("wf-confirm", "confirm_inventory").toBuilder()
                .riskLevel("WRITE").readOnly(false).build();
        stubWorkflow(tool);
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenReturn(success("Inventory: 12"));
        var input = Map.<String, Object>of("message", "Check inventory", "__supervisorContinuation", Map.of(
                "planNo", 1,
                "recordedPlan", Map.of("workflowToolNames", List.of("confirm_inventory")),
                "plannedWorkflowToolNames", List.of("confirm_inventory"),
                "plannedWorkflowCursor", 0,
                "completedWorkflowToolNames", List.of()));
        var grant = new com.enterprise.ai.runtime.execution.RuntimeSupervisorApprovalPort.PolicyApprovalGrant(
                "spv-confirm", tool.getPermissionKey(), tool.getToolName(), Map.of("sku", "SKU-001"), "1");
        var runtime = adapter(model(List.of(text("Please confirm"), text("Inventory: 12"))));

        var result = runtime.execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                agent(), config(false), List.of(tool), input, grant));

        verify(graphExecutor).execute(any(), any(), any(), any(), any());
        assertTrue(result.success(), result.code());
        assertEquals(1, result.metadata().get("workflowCallCount"));
    }

    @Test
    void confirmationWithoutSavedPlanCannotReportCompletionWithoutBusinessExecution() {
        var tool = tool("wf-confirm", "confirm_inventory");
        stubWorkflow(tool);
        var grant = new com.enterprise.ai.runtime.execution.RuntimeSupervisorApprovalPort.PolicyApprovalGrant(
                "spv-confirm", tool.getPermissionKey(), tool.getToolName(), Map.of(), "1");
        var runtime = adapter(model(List.of(text("Please confirm"))));

        var result = runtime.execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                agent(), config(false), List.of(tool), Map.of("message", "Check inventory"), grant));

        assertFalse(result.success(), "An approval must never turn a skipped business action into success");
        verifyNoInteractions(graphExecutor);
    }

    @Test
    void confirmedRemoteAgentExecutesOnceBeforeModelFinalAnswer() {
        var remote = new SupervisorRuntimeAdapter.RemoteAgentBinding(1L, 2L, 3L, 4L,
                "remote-orders", "send_orders", "Send order", "[\"orders\"]", "[]", "WRITE", "orders:write", true);
        var delegation = mock(com.enterprise.ai.runtime.a2a.RuntimeA2aDelegationService.class);
        when(delegation.send(any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(new com.enterprise.ai.runtime.client.control.RuntimeA2aControlClient.SendResponse(
                        "test", "task-1", "context-1", "remote-task-1", "remote-context-1", "TASK_STATE_COMPLETED",
                        "Order accepted", null, false, List.of("done"), List.of()));
        var input = Map.<String, Object>of("message", "Send order", "__supervisorContinuation", Map.of(
                "planNo", 1, "recordedPlan", Map.of("workflowToolNames", List.of("send_orders")),
                "plannedWorkflowToolNames", List.of("send_orders"), "plannedWorkflowCursor", 0,
                "completedWorkflowToolNames", List.of()));
        var grant = new com.enterprise.ai.runtime.execution.RuntimeSupervisorApprovalPort.PolicyApprovalGrant(
                "spv-remote", "orders:write", "send_orders", Map.of("text", "Order 42", "protocolSkillId", "orders"), "1");
        var runtime = adapter(model(List.of(text("Please confirm"), text("Order accepted"))));
        runtime.setA2aDelegationService(delegation);
        var result = runtime.execute(new SupervisorRuntimeAdapter.SupervisorRequest(agent(), config(false), List.of(),
                input, grant, null, null, null, List.of(), null, List.of(), List.of(remote)));
        assertTrue(result.success(), result.code());
        var sent = org.mockito.ArgumentCaptor.forClass(com.enterprise.ai.runtime.a2a.RuntimeA2aDelegationService.DelegationRequest.class);
        verify(delegation).send(org.mockito.ArgumentMatchers.eq("agent-1"), org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(1L), sent.capture());
        assertEquals("Order 42", sent.getValue().text());
        assertEquals("orders", sent.getValue().protocolSkillId());
        verifyNoInteractions(graphExecutor);
    }

    @Test
    void approvalPermissionMismatchFailsBeforeAnyBusinessOrModelCall() {
        var tool = tool("wf-confirm", "confirm_inventory");
        stubWorkflow(tool);
        var input = Map.<String, Object>of("message", "Check inventory", "__supervisorContinuation", Map.of(
                "planNo", 1, "recordedPlan", Map.of("workflowToolNames", List.of("confirm_inventory")),
                "plannedWorkflowToolNames", List.of("confirm_inventory"), "plannedWorkflowCursor", 0,
                "completedWorkflowToolNames", List.of()));
        var grant = new com.enterprise.ai.runtime.execution.RuntimeSupervisorApprovalPort.PolicyApprovalGrant(
                "spv-confirm", "wrong-permission", tool.getToolName(), Map.of(), "1");
        var modelClient = mock(RuntimeModelServiceClient.class);
        var result = adapter(modelClient).execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                agent(), config(false), List.of(tool), input, grant));
        assertFalse(result.success());
        assertEquals("SUPERVISOR_APPROVAL_CONTINUATION_INVALID", result.code());
        verifyNoInteractions(graphExecutor, modelClient);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("invalidContinuationNumbers")
    void rejectsLossyContinuationNumbersBeforeCompletingTheTurn(String field, Number value) {
        var query = tool("wf-query", "query_team");
        stubWorkflow(query);
        var continuation = new LinkedHashMap<String, Object>();
        continuation.put("traceId", "trace-1");
        continuation.put("continuationSchemaVersion", 3);
        continuation.put("executionCallCounts", Map.of("workflow",1,"a2a",0,"managed",0));
        continuation.put("plannedWorkflowCursor", 0);
        continuation.put("waitingToolName", "query_team");
        continuation.put("plannedWorkflowToolNames", List.of("query_team"));
        continuation.put("completedWorkflowToolNames", List.of());
        continuation.put("recordedPlan", Map.of("workflowToolNames", List.of("query_team")));
        continuation.put(field, value);
        var result = adapter(model(List.of())).continueAfterWorkflowInteraction(continuation,
                Map.of("success", true, "status", "COMPLETED", "answer", "ok"),
                new SupervisorRuntimeAdapter.SupervisorRequest(agent(), config(false), List.of(query), Map.of("traceId", "trace-1")));
        assertFalse(result.success());
        assertEquals("RUNTIME_INTERACTION_CONTINUATION_INVALID", result.code());
        verifyNoInteractions(graphExecutor);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidContinuationNumbers() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("continuationSchemaVersion", 3.5),
                org.junit.jupiter.params.provider.Arguments.of("continuationSchemaVersion", 4294967299L),
                org.junit.jupiter.params.provider.Arguments.of("plannedWorkflowCursor", 0.5),
                org.junit.jupiter.params.provider.Arguments.of("plannedWorkflowCursor", -0.5),
                org.junit.jupiter.params.provider.Arguments.of("plannedWorkflowCursor", 4294967296L),
                org.junit.jupiter.params.provider.Arguments.of("plannedWorkflowCursor", Double.NaN));
    }

    @Test
    void concurrentInitialPlansAcceptOnlyOneExecutionSequence() throws Exception {
        RuntimeAgentWorkflowToolSnapshot tool = org.mockito.Mockito.spy(tool("wf-team", "query_team"));
        var planning = new java.util.concurrent.atomic.AtomicBoolean();
        var observations = new AtomicInteger();
        var bothPlanningCalls = new java.util.concurrent.CountDownLatch(2);
        org.mockito.Mockito.doAnswer(invocation -> {
            // Force overlap only at the old admission lookup, never at prompt rendering or tool registration.
            // The extracted state receives this immutable allowlist before model execution starts.
            boolean admissionLookup = StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                    frame.getMethodName().equals("recordPlan")
                            && frame.getClassName().equals(AgentScopeSupervisorRuntimeAdapter.class.getName() + "$RunState")));
            if (planning.get() && admissionLookup && observations.getAndIncrement() < 2) {
                bothPlanningCalls.countDown();
                assertTrue(bothPlanningCalls.await(3, java.util.concurrent.TimeUnit.SECONDS),
                        "Both concurrent plans must reach admission before either one is committed");
            }
            return "query_team";
        }).when(tool).getToolName();
        stubWorkflow(tool);
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenReturn(success("查询成功"));
        var sequence = List.of(
                calls(call("first-plan", "record_supervisor_plan", Map.of(
                                "summary", "第一次规划", "steps", List.of("查询"), "workflowToolNames", List.of("query_team"))),
                        call("second-plan", "record_supervisor_plan", Map.of(
                                "summary", "重复规划", "steps", List.of("查询"), "workflowToolNames", List.of("query_team")))),
                calls(call("query", "query_team", Map.of())), text("查询成功"), text("查询成功"));
        AtomicInteger modelRound = new AtomicInteger();
        AgentScopeSupervisorRuntimeAdapter runtime = adapter(request -> {
            int index = modelRound.getAndIncrement();
            planning.set(index == 0);
            return new ModelChatResult(200, "success", sequence.get(index));
        });

        var result = runtime.execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                agent(), config(true), List.of(tool), Map.of("message", "查询班组", "sessionId", "parallel-plan-state")));

        assertTrue(result.success(), String.valueOf(result.metadata()));
        assertEquals(1, result.metadata().get("planCount"), "Concurrent plan submission must accept only one initial plan");
        assertEquals(1, result.metadata().get("workflowCallCount"));
    }

    @Test
    void rejectedPlanDoesNotIncrementPlanCount() throws Exception {
        RuntimeAgentWorkflowToolSnapshot tool = tool("wf-team", "query_team");
        stubWorkflow(tool);
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenReturn(success("查询成功"));
        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("summary", null);
        invalid.put("steps", List.of("查询班组"));
        invalid.put("workflowToolNames", List.of("query_team"));
        AgentScopeSupervisorRuntimeAdapter runtime = adapter(model(List.of(
                calls(call("invalid-plan", "record_supervisor_plan", invalid)),
                calls(call("valid-plan", "record_supervisor_plan", Map.of(
                        "summary", "查询班组", "steps", List.of("查询"), "workflowToolNames", List.of("query_team")))),
                calls(call("query", "query_team", Map.of())), text("查询成功"), text("查询成功"))));

        var result = runtime.execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                agent(), config(false), List.of(tool), Map.of("message", "查询班组", "sessionId", "invalid-plan-state")));

        assertTrue(result.success(), String.valueOf(result.metadata()));
        assertEquals(1, result.metadata().get("planCount"), "A rejected plan must not consume a plan number");
        assertEquals(0, result.metadata().get("replanCount"));
        assertEquals(1, result.metadata().get("workflowCallCount"));
    }

    @Test
    void rejectedReplanDoesNotPreventValidRecovery() throws Exception {
        RuntimeAgentWorkflowToolSnapshot tool = tool("wf-team", "query_team");
        stubWorkflow(tool);
        AtomicInteger calls = new AtomicInteger();
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> calls.incrementAndGet() == 1
                ? new RuntimeGraphSpecExecutionResult(false, "UPSTREAM_FAILED", "临时失败", null, null, List.of(), Map.of())
                : success("重试成功"));
        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("reason", null);
        invalid.put("steps", List.of("重试"));
        invalid.put("workflowToolNames", List.of("query_team"));
        AgentScopeSupervisorRuntimeAdapter runtime = adapter(model(List.of(
                calls(call("plan", "record_supervisor_plan", Map.of(
                        "summary", "查询", "steps", List.of("查询"), "workflowToolNames", List.of("query_team")))),
                calls(call("first-query", "query_team", Map.of())),
                calls(call("invalid-replan", "record_supervisor_plan", invalid)),
                calls(call("valid-replan", "record_supervisor_plan", Map.of(
                        "summary", "重试", "steps", List.of("重试"), "workflowToolNames", List.of("query_team")))),
                calls(call("second-query", "query_team", Map.of())), text("重试成功"), text("重试成功"))));

        var result = runtime.execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                agent(), config(false), List.of(tool), Map.of("message", "查询班组", "sessionId", "invalid-replan-state")));

        assertTrue(result.success(), String.valueOf(result.metadata()));
        assertEquals(2, result.metadata().get("planCount"));
        assertEquals(1, result.metadata().get("replanCount"));
        assertEquals(2, calls.get(), "A rejected replan must leave the legitimate retry available");
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeWorkflowDefinitionMapper workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
    private final RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
    private final RuntimeGraphSpecExecutor graphExecutor = mock(RuntimeGraphSpecExecutor.class);
    private final SupervisorExecutionTraceService traceService = mock(SupervisorExecutionTraceService.class);
    private final SupervisorApprovalInteractionService approvalService = mock(SupervisorApprovalInteractionService.class);
    private final Map<String, RuntimeWorkflowDefinitionEntity> workflowTargets = new LinkedHashMap<>();
    private final Map<Long, RuntimeWorkflowVersionEntity> workflowVersions = new LinkedHashMap<>();

    AgentScopeSupervisorRuntimeAdapterTest() {
        // These tests exercise ordinary Supervisor execution and intentionally keep their
        // per-case behavior on the legacy overload. Eval-specific tests cover propagation of
        // RuntimeEvalExecutionContext; delegate the new overload so existing workflow stubs do
        // not silently return Mockito's null default after the Eval execution path is introduced.
        when(graphExecutor.execute(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> graphExecutor.execute(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        invocation.getArgument(4)));
    }

    @Test
    void answersDirectlyWithAnImplicitZeroToolPlan() {
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(text("你好，我是班组助手"))));
        List<String> events = new ArrayList<>();

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                agent(), config(false), List.of(), Map.of(
                        "message", "你好", "sessionId", "s-zero", "projectCode", "qmssmp"),
                null, (event, data) -> {
                    events.add(event);
                }));

        assertTrue(result.success(), String.valueOf(result.metadata()));
        assertEquals("你好，我是班组助手", result.answer());
        assertTrue(events.contains("message.delta"));
        assertEquals(0, result.metadata().get("planCount"),
                "implicit direct decision must not inflate AgentScope planCount");
        assertEquals("DIRECT", result.metadata().get("decisionMode"));
        assertEquals(1, result.metadata().get("modelRoundCount"));
        assertEquals("buffered_direct", result.metadata().get("streamMode"));
        assertEquals(false, result.metadata().get("workflowSelected"));
        assertEquals(0, result.metadata().get("workflowCallCount"));
        assertEquals("s-zero", result.metadata().get("sessionId"));
        assertTrue(events.stream().noneMatch("message.delta"::equals)
                        || "buffered_direct".equals(result.metadata().get("streamMode")),
                "DIRECT response must not depend on a forced PUBLIC_FINAL stream");
    }

    @Test
    void advertisesCallableToolNameWhenPublishedWorkflowUsesADifferentKey() throws Exception {
        RuntimeAgentWorkflowToolSnapshot workflowTool = tool("wf-published-check", "run_published_check");
        stubWorkflow(workflowTool);
        workflowTargets.get(workflowTool.getWorkflowId()).setKeySlug("published-check-workflow");
        when(graphExecutor.execute(any(), any(), any(), any(), any()))
                .thenReturn(success("published result"));
        AtomicReference<RuntimeModelServiceClient.ModelChatRequest> firstRequest = new AtomicReference<>();
        RuntimeModelServiceClient responses = model(List.of(
                calls(call("plan-1", "record_supervisor_plan", Map.of(
                        "summary", "run the published check", "steps", List.of("run the check"),
                        "workflowToolNames", List.of(workflowTool.getToolName())))),
                calls(call("workflow-1", workflowTool.getToolName(), Map.of())),
                text("published result"),
                text("published result")));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(request -> {
            firstRequest.compareAndSet(null, request);
            return responses.chat(request);
        });

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(agent(), config(false), List.of(workflowTool),
                        Map.of("message", "run the published check", "sessionId", "s-tool-alias"), null, null));

        assertTrue(result.success(), String.valueOf(result.metadata()));
        assertEquals(1, result.metadata().get("workflowCallCount"));
        String systemPrompt = firstRequest.get().getMessages().stream()
                .filter(message -> "system".equals(message.getRole()))
                .map(RuntimeModelServiceClient.ModelChatRequest.ChatMessage::getContent)
                .findFirst().orElseThrow();
        String workflowSection = systemPrompt.split("Permitted published Workflow tools:\\n", 2)[1]
                .split("Permitted fixed A2A remote-Agent tools:", 2)[0];
        List<String> advertisedNames = workflowSection.lines()
                .filter(line -> line.startsWith("- "))
                .map(line -> line.substring(2).split("\\s", 2)[0]).toList();
        List<String> registeredNames = new ArrayList<>();
        firstRequest.get().getTools().forEach(toolDefinition ->
                registeredNames.add(toolDefinition.path("function").path("name").asText()));
        assertEquals(List.of(workflowTool.getToolName()), advertisedNames,
                "the planning allowlist must use the callable binding name even when the Workflow key differs");
        assertTrue(registeredNames.containsAll(advertisedNames),
                "every advertised name must be a registered execution tool");
        verify(graphExecutor).execute(any(), any(), any(), any(), any(), any());
    }

    @Test
    void incompletePlanDoesNotPublishAForcedFinalAnswer() throws Exception {
        assertIncompletePlanDoesNotPublish(false);
    }

    @Test
    void rejectedExplicitFinalAnswerCannotBeBypassedByForcedFinalPass() throws Exception {
        assertIncompletePlanDoesNotPublish(true);
    }

    private void assertIncompletePlanDoesNotPublish(boolean explicitFinalAttempt) throws Exception {
        RuntimeAgentWorkflowToolSnapshot workflowTool = tool("wf-unfinished", "unfinished_query");
        stubWorkflow(workflowTool);
        List<ModelChatData> responses = new ArrayList<>();
        responses.add(calls(call("unfinished-plan", "record_supervisor_plan", Map.of(
                "summary", "run the requested query", "steps", List.of("run unfinished_query"),
                "workflowToolNames", List.of("unfinished_query")))));
        if (explicitFinalAttempt) responses.add(calls(call("blocked-final", "begin_final_answer", Map.of())));
        responses.add(text("internal draft that claims the work completed"));
        responses.add(text("The requested query has completed successfully."));
        AtomicInteger modelCalls = new AtomicInteger();
        RuntimeModelServiceClient script = model(responses);
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(request -> {
            modelCalls.incrementAndGet();
            return script.chat(request);
        });
        List<String> publicText = new java.util.concurrent.CopyOnWriteArrayList<>();

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(agent(), config(false), List.of(workflowTool),
                        Map.of("message", "run the requested query", "sessionId", "s-unfinished-plan"),
                        null, (event, data) -> {
                            if ("message.delta".equals(event)) publicText.add(String.valueOf(data));
                        }));

        assertFalse(result.success());
        assertEquals("SUPERVISOR_PLAN_INCOMPLETE", result.code());
        assertTrue(publicText.isEmpty(), "unfinished work must not publish a model-generated completion answer");
        assertEquals(explicitFinalAttempt ? 3 : 2, modelCalls.get(), "no forced model pass is allowed for an incomplete plan");
        assertEquals("FAILED", result.metadata().get("answerPhase"));
        assertEquals(false, result.metadata().get("contentStreamed"));
        assertTrue(result.steps().stream().noneMatch(step -> "final_answer".equals(step.get("name"))
                && "completed".equals(step.get("state"))));
        verify(graphExecutor, never()).execute(any(), any(), any(), any(), any(), any());
    }

    @Test
    void forcedPublicFinalPassRecoversOnceFromContextOverflowWithoutRepeatingWorkflow()
            throws Exception {
        RuntimeAgentWorkflowToolSnapshot workflowTool = tool("wf-query", "query_team");
        stubWorkflow(workflowTool);
        when(graphExecutor.execute(any(), any(), any(), any(), any()))
                .thenReturn(success("workflow completed once"));
        AtomicInteger toolEnabledCalls = new AtomicInteger();
        AtomicInteger noToolCalls = new AtomicInteger();
        AtomicInteger compactionCalls = new AtomicInteger();
        ModelChatData planResponse = calls(call(
                "plan-1",
                "record_supervisor_plan",
                Map.of(
                        "summary", "query the team",
                        "steps", List.of("run query_team"),
                        "workflowToolNames", List.of("query_team"))));
        ModelChatData workflowResponse = calls(call(
                "workflow-1", "query_team", Map.of()));
        RuntimeModelServiceClient modelClient = request -> {
            if (request.getTools() != null) {
                return switch (toolEnabledCalls.incrementAndGet()) {
                    case 1 -> new ModelChatResult(200, "success", planResponse);
                    case 2 -> new ModelChatResult(200, "success", workflowResponse);
                    case 3 -> new ModelChatResult(200, "success", text("internal answer draft"));
                    default -> throw new IllegalStateException("Unexpected tool-enabled model call");
                };
            }
            return switch (noToolCalls.incrementAndGet()) {
                case 1 -> new ModelChatResult(
                        400,
                        "context_length_exceeded: maximum context length reached",
                        null);
                case 2 -> {
                    compactionCalls.incrementAndGet();
                    yield new ModelChatResult(200, "success", text(
                            "INTENT: answer the query\n"
                                    + "VERIFIED STATE: workflow returned successfully\n"
                                    + "COMPLETED ACTIONS: query_team completed once\n"
                                    + "PENDING: provide the final answer"));
                }
                case 3 -> new ModelChatResult(200, "success", text("final answer after compaction"));
                default -> throw new IllegalStateException("Unexpected no-tool model call");
            };
        };
        RuntimeSessionMemoryService memoryService = RuntimeSessionMemoryService.transientOnly();
        RuntimeContextEngineeringProperties properties = new RuntimeContextEngineeringProperties(
                true,
                false,
                true,
                24,
                24_000,
                10,
                2,
                60_000,
                false,
                80_000,
                2_000,
                16_777_216,
                12_000,
                24,
                200,
                60_000,
                "test-key",
                "");
        @SuppressWarnings("unchecked")
        ObjectProvider<RuntimeToolResultArtifactService> artifactProvider = mock(ObjectProvider.class);
        when(artifactProvider.getIfAvailable()).thenReturn(null);
        RuntimeContextEngineeringService contextEngineeringService =
                new RuntimeContextEngineeringService(
                        properties,
                        modelClient,
                        null,
                        objectMapper,
                        memoryService,
                        artifactProvider);
        AgentScopeSupervisorRuntimeAdapter adapter = adapterWithContextEngineering(
                modelClient, memoryService, contextEngineeringService);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(),
                        config(false),
                        List.of(workflowTool),
                        Map.of("message", "query the team", "sessionId", "s-final-overflow")));

        assertTrue(result.success(), result.code() + " " + result.answer() + " " + result.metadata());
        assertEquals("final answer after compaction", result.answer());
        assertEquals(3, toolEnabledCalls.get());
        assertEquals(3, noToolCalls.get());
        assertEquals(1, compactionCalls.get());
        verify(graphExecutor).execute(any(), any(), any(), any(), any());
        @SuppressWarnings("unchecked")
        Map<String, Object> modelMetadata =
                (Map<String, Object>) result.metadata().get("model");
        assertEquals(1, modelMetadata.get("contextOverflowRecoveryCount"));
    }

    @Test
    void ruleFirstPageQueryUsesNaturalLanguageAndPublishedPageSchema() throws Exception {
        RuntimeAgentWorkflowToolSnapshot pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query").toBuilder()
                .inputSchemaOverrideJson("""
                {
                  "type":"object",
                  "properties":{
                    "status":{"type":"integer","description":"订单状态：0待付款，1待发货，4已关闭"},
                    "pageNum":{"type":"integer"},
                    "pageSize":{"type":"integer"}
                  }
                }
                """)
                .build();
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "query", "status", "pageNum", "pageSize");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        when(catalogClient.getPageAction("qmssmp", "orders", "query")).thenReturn(pageAction(
                "qmssmp", "orders", "query", "READ", false,
                Map.of("type", "object", "properties", Map.of(
                        "status", Map.of("type", "integer", "description", "订单状态：0待付款，1待发货，4已关闭"),
                        "pageNum", Map.of("type", "integer"),
                        "pageSize", Map.of("type", "integer"))), "ACTIVE"));
        List<Map<String, Object>> workflowInputs = new ArrayList<>();
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            workflowInputs.add(invocation.getArgument(1));
            return success("当前页面符合条件的订单共有 13 条");
        });
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of()), catalogClient);
        List<String> events = new ArrayList<>();

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "请帮我查一下已关闭的订单一共有多少条？只读查询，不要修改数据。",
                        "sessionId", "s-rule-first-page", "projectCode", "qmssmp", "pageKey", "orders"),
                        null, (event, data) -> events.add(event)));

        assertTrue(result.success(), String.valueOf(result.metadata()));
        assertEquals("当前页面符合条件的订单共有 13 条", result.answer());
        @SuppressWarnings("unchecked")
        Map<String, Object> modelMetadata = (Map<String, Object>) result.metadata().get("model");
        assertEquals("RULE_FIRST_PAGE_QUERY", modelMetadata.get("route"));
        assertEquals(1, result.metadata().get("workflowCallCount"));
        assertTrue(events.stream().noneMatch("message.delta"::equals),
                "rule-first result is returned once as the final response");
        assertEquals(1, workflowInputs.size());
        assertEquals(4, workflowInputs.get(0).get("status"));
        assertEquals(1, workflowInputs.get(0).get("pageNum"));
        assertEquals(10, workflowInputs.get(0).get("pageSize"));
        assertEquals(Map.of("status", 4, "pageNum", 1, "pageSize", 10), workflowInputs.get(0).get("params"));
        assertEquals(Map.of("status", 4, "pageNum", 1, "pageSize", 10), workflowInputs.get(0).get("input"));
    }

    @Test
    void ruleFirstPageQueryPreservesExplicitPagination() {
        RuntimeAgentWorkflowToolSnapshot pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "query", "pageNum", "pageSize");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        when(catalogClient.getPageAction("qmssmp", "orders", "query")).thenReturn(pageAction(
                "qmssmp", "orders", "query", "READ", false,
                Map.of("type", "object", "properties", Map.of(
                        "pageNum", Map.of("type", "integer"),
                        "pageSize", Map.of("type", "integer"))), "ACTIVE"));
        List<Map<String, Object>> workflowInputs = new ArrayList<>();
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            workflowInputs.add(invocation.getArgument(1));
            return success("分页查询完成");
        });
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of()), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "查询第 2 页订单，每页 25 条",
                        "sessionId", "s-page-two", "projectCode", "qmssmp", "pageKey", "orders")));

        assertTrue(result.success(), result.code() + " " + result.answer() + " " + result.metadata());
        assertEquals(2, workflowInputs.get(0).get("pageNum"));
        assertEquals(25, workflowInputs.get(0).get("pageSize"));
    }

    @Test
    void ruleFirstPageQueryKeepsNaturalLanguageSeparateWhenNoStructuredArgumentsExist() {
        RuntimeAgentWorkflowToolSnapshot pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "query");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        when(catalogClient.getPageAction("qmssmp", "orders", "query")).thenReturn(pageAction(
                "qmssmp", "orders", "query", "READ", false,
                Map.of("type", "object", "properties", Map.of()), "ACTIVE"));
        List<Map<String, Object>> workflowInputs = new ArrayList<>();
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            workflowInputs.add(invocation.getArgument(1));
            return success("查询完成");
        });
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of()), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "查询当前页面的记录", "sessionId", "s-natural-input",
                        "projectCode", "qmssmp", "pageKey", "orders")));

        assertTrue(result.success(), result.code() + " " + result.answer() + " " + result.metadata());
        assertEquals(1, workflowInputs.size());
        assertEquals("查询当前页面的记录", workflowInputs.get(0).get("userInput"));
        assertEquals("查询当前页面的记录", workflowInputs.get(0).get("message"));
        assertEquals("查询当前页面的记录", workflowInputs.get(0).get("input"));
        assertEquals(Map.of(), workflowInputs.get(0).get("params"));
    }

    @Test
    void ruleFirstPageQueryDoesNotRunAWorkflowForAnotherCurrentPage() {
        RuntimeAgentWorkflowToolSnapshot pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "query");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(text("当前页面没有可直接执行的查询动作"))), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "查询当前页面有多少条记录", "sessionId", "s-wrong-page",
                        "projectCode", "qmssmp", "pageKey", "customers")));

        assertTrue(result.success());
        assertEquals("当前页面没有可直接执行的查询动作", result.answer());
        assertEquals(0, result.metadata().get("workflowCallCount"));
        verifyNoInteractions(catalogClient, graphExecutor);
    }

    @Test
    void ruleFirstPageQueryDoesNotSilentlyDropUnsupportedFilters() {
        RuntimeAgentWorkflowToolSnapshot pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "query");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        when(catalogClient.getPageAction("qmssmp", "orders", "query")).thenReturn(pageAction(
                "qmssmp", "orders", "query", "READ", false,
                Map.of("type", "object", "properties", Map.of(
                        "orderSn", Map.of("type", "string", "description", "订单编号"),
                        "pageNum", Map.of("type", "integer"),
                        "pageSize", Map.of("type", "integer"))), "ACTIVE"));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(text("请确认完整订单编号后再查询"))), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "查询订单号 A001", "sessionId", "s-filter",
                        "projectCode", "qmssmp", "pageKey", "orders")));

        assertTrue(result.success());
        assertEquals("请确认完整订单编号后再查询", result.answer());
        assertEquals(0, result.metadata().get("workflowCallCount"));
        verifyNoInteractions(graphExecutor);
    }

    @Test
    void ruleFirstPageQueryFallsBackForOverflowingIntegerFilterWithoutDescription() {
        RuntimeAgentWorkflowToolSnapshot pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "query", "status", "pageNum", "pageSize");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        when(catalogClient.getPageAction("qmssmp", "orders", "query")).thenReturn(pageAction(
                "qmssmp", "orders", "query", "READ", false,
                Map.of("type", "object", "properties", Map.of(
                        "status", Map.of("type", "integer"),
                        "pageNum", Map.of("type", "integer"),
                        "pageSize", Map.of("type", "integer"))), "ACTIVE"));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(
                model(List.of(text("状态值超出可直接解析范围，请重新确认"))), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "查询状态 999999999999999999999999999999 的订单",
                        "sessionId", "s-overflow-filter", "projectCode", "qmssmp", "pageKey", "orders")));

        assertTrue(result.success());
        assertEquals("状态值超出可直接解析范围，请重新确认", result.answer());
        assertEquals(0, result.metadata().get("workflowCallCount"));
        verifyNoInteractions(graphExecutor);
    }

    @Test
    void ruleFirstPageQueryDoesNotTreatOneNegatedWriteAsAReadOnlyCompoundRequest() {
        RuntimeAgentWorkflowToolSnapshot pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "query", "pageNum", "pageSize");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(
                model(List.of(text("复合写操作未走只读快捷路径"))), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "查询订单，不要删除 A，但删除 B",
                        "sessionId", "s-compound-write", "projectCode", "qmssmp", "pageKey", "orders")));

        assertTrue(result.success());
        assertEquals("复合写操作未走只读快捷路径", result.answer());
        assertEquals(0, result.metadata().get("workflowCallCount"));
        verifyNoInteractions(catalogClient, graphExecutor);
    }

    @Test
    void ruleFirstPageQueryDoesNotDropEnglishWriteIntent() {
        RuntimeAgentWorkflowToolSnapshot pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "query", "pageNum", "pageSize");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(
                model(List.of(text("English compound request used Supervisor"))), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "query the orders and then delete one",
                        "sessionId", "s-english-write", "projectCode", "qmssmp", "pageKey", "orders")));

        assertTrue(result.success());
        assertEquals("English compound request used Supervisor", result.answer());
        assertEquals(0, result.metadata().get("workflowCallCount"));
        verifyNoInteractions(catalogClient, graphExecutor);
    }

    @Test
    void ruleFirstPageQueryRejectsUnsafeCatalogAction() {
        RuntimeAgentWorkflowToolSnapshot pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "query");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        when(catalogClient.getPageAction("qmssmp", "orders", "query")).thenReturn(pageAction(
                "qmssmp", "orders", "query", "WRITE", true,
                Map.of("type", "object", "properties", Map.of()), "ACTIVE"));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(text("该动作需要确认，未自动执行"))), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "查询当前页面有多少条记录", "sessionId", "s-unsafe",
                        "projectCode", "qmssmp", "pageKey", "orders")));

        assertTrue(result.success());
        assertEquals("该动作需要确认，未自动执行", result.answer());
        assertEquals(0, result.metadata().get("workflowCallCount"));
        verifyNoInteractions(graphExecutor);
    }

    @Test
    void ruleFirstPageQueryRejectsMisdeclaredReadActionWithWriteSemantics() {
        RuntimeAgentWorkflowToolSnapshot pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "queryAndDelete");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        when(catalogClient.getPageAction("qmssmp", "orders", "queryAndDelete")).thenReturn(pageAction(
                "qmssmp", "orders", "queryAndDelete", "READ", false,
                Map.of("type", "object", "properties", Map.of()), "ACTIVE"));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(
                model(List.of(text("写语义动作未走只读快捷路径"))), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "查询当前页面有多少条记录", "sessionId", "s-misdeclared-write",
                        "projectCode", "qmssmp", "pageKey", "orders")));

        assertTrue(result.success());
        assertEquals("写语义动作未走只读快捷路径", result.answer());
        assertEquals(0, result.metadata().get("workflowCallCount"));
        verifyNoInteractions(graphExecutor);
    }

    @Test
    void ruleFirstPageQuerySummarizesOnlySafeStructuredCount() {
        RuntimeAgentWorkflowToolSnapshot pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "query", "pageNum", "pageSize");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        when(catalogClient.getPageAction("qmssmp", "orders", "query")).thenReturn(pageAction(
                "qmssmp", "orders", "query", "READ", false,
                Map.of("type", "object", "properties", Map.of(
                        "pageNum", Map.of("type", "integer"),
                        "pageSize", Map.of("type", "integer"))), "ACTIVE"));
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenReturn(successWithStructuredCount(13));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of()), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "查询当前页面有多少条记录", "sessionId", "s-summary",
                        "projectCode", "qmssmp", "pageKey", "orders")));

        assertTrue(result.success());
        assertEquals("执行成功，共 13 条", result.answer());
    }

    @Test
    void ruleFirstPageQueryExplicitlyReportsNoMatchingRecords() {
        RuntimeAgentWorkflowToolSnapshot pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "query", "pageNum", "pageSize");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        when(catalogClient.getPageAction("qmssmp", "orders", "query")).thenReturn(pageAction(
                "qmssmp", "orders", "query", "READ", false,
                Map.of("type", "object", "properties", Map.of(
                        "pageNum", Map.of("type", "integer"),
                        "pageSize", Map.of("type", "integer"))), "ACTIVE"));
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenReturn(successWithPageActionSummary(0));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of()), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "查询当前页面有多少条记录", "sessionId", "s-empty-summary",
                        "projectCode", "qmssmp", "pageKey", "orders")));

        assertTrue(result.success());
        assertEquals("页面查询已完成，未查询到匹配记录。", result.answer());
    }

    @Test
    void ruleFirstPageQueryRejectsCompositeWorkflowWithAnotherExecutableNode() {
        RuntimeAgentWorkflowToolSnapshot pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        RuntimeWorkflowVersionEntity version = workflowVersions.get(pageQuery.getWorkflowVersionId());
        version.setGraphSpecSnapshotJson("""
                {"nodes":[
                  {"id":"page-action","type":"PAGE_ACTION","config":{"pageKey":"orders","actionKey":"query","inputMapping":{}}},
                  {"id":"write-api","type":"TOOL","config":{"qualifiedName":"orders:update"}},
                  {"id":"answer","type":"ANSWER","config":{"template":"done"}}
                ],"edges":[]}
                """);
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(text("复合工作流未自动执行"))), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "查询当前页面有多少条记录", "sessionId", "s-composite",
                        "projectCode", "qmssmp", "pageKey", "orders")));

        assertTrue(result.success());
        assertEquals("复合工作流未自动执行", result.answer());
        assertEquals(0, result.metadata().get("workflowCallCount"));
        verifyNoInteractions(catalogClient, graphExecutor);
    }

    @Test
    void executesMultipleReadOnlyWorkflowCallsInParallelAndAggregatesAnswer() throws Exception {
        RuntimeAgentWorkflowToolSnapshot teamTool = tool("wf-team", "query_team");
        RuntimeAgentWorkflowToolSnapshot ownerTool = tool("wf-owner", "query_owner");
        stubWorkflow(teamTool);
        stubWorkflow(ownerTool);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            Thread.sleep(150);
            active.decrementAndGet();
            Map<String, Object> input = invocation.getArgument(1);
            return success(String.valueOf(input.get("kind")));
        });
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("plan-1", "record_supervisor_plan", Map.of(
                        "summary", "查询班组与负责人", "steps", List.of("查询班组", "查询负责人"),
                        "workflowToolNames", List.of("query_team", "query_owner")))),
                calls(
                        call("team-1", "query_team", Map.of("kind", "team")),
                        call("owner-1", "query_owner", Map.of("kind", "owner"))),
                text("班组与负责人信息已汇总"),
                text("班组与负责人信息已汇总"))));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                agent(), config(true), List.of(teamTool, ownerTool), Map.of(
                        "message", "查询班组及负责人", "sessionId", "s-multi", "projectCode", "qmssmp")));

        assertTrue(result.success());
        assertEquals(2, result.metadata().get("workflowCallCount"));
        assertEquals(2, maxActive.get());
        assertEquals("班组与负责人信息已汇总", result.answer());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) result.metadata().get("steps");
        assertTrue(steps.stream().anyMatch(s ->
                "workflow".equals(s.get("name")) && "completed".equals(s.get("state"))));
    }

    @Test
    void modelToolArgumentsCannotOverrideSignedExecutionContext() throws Exception {
        RuntimeAgentWorkflowToolSnapshot teamTool = tool("wf-team", "query_team");
        stubWorkflow(teamTool);
        List<Map<String, Object>> workflowInputs = new ArrayList<>();
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            workflowInputs.add(invocation.getArgument(1));
            return success("ok");
        });
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("plan-secure", "record_supervisor_plan", Map.of(
                        "summary", "查询班组", "steps", List.of("查询班组"),
                        "workflowToolNames", List.of("query_team")))),
                calls(call("workflow-secure", "query_team", Map.of(
                        "kind", "team",
                        "projectCode", "attacker-project",
                        "tenantId", "attacker-tenant",
                        "roles", List.of("admin"),
                        "sessionId", "attacker-session",
                        "pageInstanceId", "attacker-page",
                        "origin", "https://attacker.example",
                        "metadata", Map.of("roles", List.of("admin"))))),
                text("查询完成"),
                text("查询完成"))));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(teamTool), Map.of(
                        "message", "查询班组", "sessionId", "trusted-session",
                        "projectCode", "qmssmp", "tenantId", "tenant-a",
                        "roles", List.of("reader"), "pageInstanceId", "trusted-page",
                        "origin", "https://mall.example")));

        assertTrue(result.success());
        assertEquals(1, workflowInputs.size());
        Map<String, Object> workflowInput = workflowInputs.get(0);
        assertEquals("qmssmp", workflowInput.get("projectCode"));
        assertEquals("tenant-a", workflowInput.get("tenantId"));
        assertEquals(List.of("reader"), workflowInput.get("roles"));
        assertEquals("trusted-session", workflowInput.get("sessionId"));
        assertEquals("trusted-page", workflowInput.get("pageInstanceId"));
        assertEquals("https://mall.example", workflowInput.get("origin"));
        assertEquals("team", workflowInput.get("kind"));
        @SuppressWarnings("unchecked")
        Map<String, Object> structuredArgs = (Map<String, Object>) workflowInput.get("params");
        assertEquals(Map.of("kind", "team"), structuredArgs);
        assertEquals(structuredArgs, workflowInput.get("input"));
    }

    @Test
    void surfacesNonBlockingWorkflowPresentationWithFinalAgentAnswer() throws Exception {
        RuntimeAgentWorkflowToolSnapshot tool = tool("wf-team", "query_team");
        stubWorkflow(tool);
        Map<String, Object> uiRequest = Map.of(
                "schemaVersion", "1.0",
                "type", "PRESENT_OUTPUT",
                "component", "list_card",
                "presentation", Map.of("mode", "card_only"),
                "data", Map.of("records", List.of(Map.of("name", "一班"))));
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenReturn(
                new RuntimeGraphSpecExecutionResult(
                        true,
                        "RUNTIME_GRAPH_EXECUTED",
                        "已展示查询结果",
                        "answer",
                        "ANSWER",
                        List.of(),
                        Map.of("uiRequest", uiRequest, "displayOnly", true)));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("plan-list", "record_supervisor_plan", Map.of(
                        "summary", "查询并展示班组", "steps", List.of("查询班组"),
                        "workflowToolNames", List.of("query_team")))),
                calls(call("workflow-list", "query_team", Map.of("teamName", "一班"))),
                text("已展示班组查询结果"),
                text("已展示班组查询结果"))));

        List<String> events = new ArrayList<>();
        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(tool), Map.of(
                        "message", "查询一班", "sessionId", "s-list-card", "projectCode", "qmssmp"),
                        null, (event, data) -> events.add(event)));

        assertTrue(result.success(), String.valueOf(result.metadata()));
        assertEquals(uiRequest, result.uiRequest());
        assertEquals("已展示班组查询结果", result.answer());
        assertFalse(events.contains("message.delta"));
        assertEquals("card_only", result.metadata().get("presentationMode"));
        assertEquals(true, result.metadata().get("answerTextSuppressed"));
    }

    @Test
    void serializesPageActionsEvenWhenParallelToolExecutionIsEnabled() throws Exception {
        RuntimeAgentWorkflowToolSnapshot firstPageAction = pageActionTool("wf-page-team", "open_team_page");
        RuntimeAgentWorkflowToolSnapshot secondPageAction = pageActionTool("wf-page-owner", "query_owner_on_page");
        stubWorkflow(firstPageAction);
        stubWorkflow(secondPageAction);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            Thread.sleep(120);
            active.decrementAndGet();
            return success("page action completed");
        });
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("plan-page", "record_supervisor_plan", Map.of(
                        "summary", "打开班组页面并在页面查询负责人",
                        "steps", List.of("打开班组页面", "在页面查询负责人"),
                        "workflowToolNames", List.of("open_team_page", "query_owner_on_page")))),
                calls(
                        call("page-1", "open_team_page", Map.of()),
                        call("page-2", "query_owner_on_page", Map.of())),
                text("页面操作已完成"),
                text("页面操作已完成"))));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                agent(), config(true), List.of(firstPageAction, secondPageAction), Map.of(
                        "message", "请打开班组页面，并在页面上查询负责人",
                        "sessionId", "s-page", "projectCode", "qmssmp")));

        assertTrue(result.success());
        assertEquals(2, result.metadata().get("workflowCallCount"));
        assertEquals(1, maxActive.get());
    }

    @Test
    void requiresBoundedReplanAfterFailureBeforeRetryingWorkflow() throws Exception {
        RuntimeAgentWorkflowToolSnapshot tool = tool("wf-team", "query_team");
        stubWorkflow(tool);
        AtomicInteger execution = new AtomicInteger();
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> execution.incrementAndGet() == 1
                ? new RuntimeGraphSpecExecutionResult(false, "UPSTREAM_FAILED", "第一次失败",
                null, null, List.of(), Map.of())
                : success("第二次成功"));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("plan-1", "record_supervisor_plan", Map.of(
                        "summary", "先查询", "steps", List.of("查询班组"),
                        "workflowToolNames", List.of("query_team")))),
                calls(call("team-1", "query_team", Map.of())),
                calls(call("replan-1", "record_supervisor_plan", Map.of(
                        "summary", "失败后重试", "steps", List.of("调整参数后重试"),
                        "workflowToolNames", List.of("query_team"), "reason", "上游暂时失败"))),
                calls(call("team-2", "query_team", Map.of())),
                text("重规划后查询成功"),
                text("重规划后查询成功"))));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                agent(), config(false), List.of(tool), Map.of(
                        "message", "查询班组", "sessionId", "s-replan", "projectCode", "qmssmp")));

        assertTrue(result.success(), String.valueOf(result.metadata()));
        assertEquals(2, result.metadata().get("planCount"));
        assertEquals(1, result.metadata().get("replanCount"));
        assertEquals(2, result.metadata().get("workflowCallCount"));
        assertEquals(2, execution.get());
    }

    @Test
    void neverRetriesTheSameSideEffectWorkflowAfterAnAmbiguousFailure() throws Exception {
        RuntimeAgentWorkflowToolSnapshot tool = pageActionTool("wf-team-toggle", "toggle_team_on_page");
        stubWorkflow(tool);
        AtomicInteger execution = new AtomicInteger();
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            execution.incrementAndGet();
            return new RuntimeGraphSpecExecutionResult(
                    false,
                    "PAGE_BRIDGE_ACTION_FAILED",
                    "Page Bridge action execution timed out after it was claimed",
                    null,
                    "PAGE_ACTION",
                    List.of(),
                    Map.of("pageBridgeStatus", "TIMEOUT"));
        });
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("plan-write", "record_supervisor_plan", Map.of(
                        "summary", "Disable the team from the page",
                        "steps", List.of("Run the page action"),
                        "workflowToolNames", List.of("toggle_team_on_page")))),
                calls(call("write-1", "toggle_team_on_page", Map.of("enabled", false))),
                calls(call("retry-plan", "record_supervisor_plan", Map.of(
                        "summary", "Retry the timed out page action",
                        "steps", List.of("Retry the page action"),
                        "workflowToolNames", List.of("toggle_team_on_page"),
                        "reason", "timeout"))),
                calls(call("abandon-plan", "record_supervisor_plan", Map.of(
                        "summary", "Stop because the write outcome is ambiguous",
                        "steps", List.of("Explain that the action was not retried"),
                        "workflowToolNames", List.of(),
                        "reason", "side-effect Workflow is non-retryable"))),
                calls(call("final-after-write-failure", "begin_final_answer", Map.of())),
                text("The page action timed out and was not retried automatically."),
                text("The page action timed out and was not retried automatically."))));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(tool), Map.of(
                        "message", "请在当前页面停用班组",
                        "sessionId", "s-side-effect-timeout",
                        "projectCode", "qmssmp")));

        assertTrue(result.success(), String.valueOf(result.metadata()));
        assertEquals(1, execution.get());
        assertEquals(1, result.metadata().get("workflowCallCount"));
        assertEquals(1, result.metadata().get("replanCount"));
    }

    @Test
    void replansToReadOnlyWorkflowAfterPageActionPolicyDenial() throws Exception {
        RuntimeAgentWorkflowToolSnapshot pageTool = pageActionTool("wf-page-team", "query_team_on_page");
        RuntimeAgentWorkflowToolSnapshot apiTool = tool("wf-api-team", "query_team_by_api");
        stubWorkflow(pageTool);
        stubWorkflow(apiTool);
        when(graphExecutor.execute(any(), any(), any(), any(), any()))
                .thenReturn(success("找到建设一工班"));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("plan-page", "record_supervisor_plan", Map.of(
                        "summary", "尝试在页面查询", "steps", List.of("在页面查询班组"),
                        "workflowToolNames", List.of("query_team_on_page")))),
                calls(call("page-denied", "query_team_on_page", Map.of("teamName", "建设一工班"))),
                calls(call("replan-api", "record_supervisor_plan", Map.of(
                        "summary", "改用只读接口查询", "steps", List.of("调用接口查询班组"),
                        "workflowToolNames", List.of("query_team_by_api"), "reason", "页面操作未获授权"))),
                calls(call("api-query", "query_team_by_api", Map.of("teamName", "建设一工班"))),
                text("已通过接口找到建设一工班"),
                text("已通过接口找到建设一工班"))));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageTool, apiTool), Map.of(
                        "message", "再查询一下建设一工班", "sessionId", "s-policy-replan",
                        "projectCode", "qmssmp")));

        assertTrue(result.success(), String.valueOf(result.metadata()));
        assertEquals("已通过接口找到建设一工班", result.answer());
        assertEquals(2, result.metadata().get("planCount"));
        assertEquals(1, result.metadata().get("replanCount"));
        assertEquals(1, result.metadata().get("workflowCallCount"));
        assertEquals(1, result.metadata().get("guardDenyCount"));
    }

    @Test
    void abandonsFailedWorkflowPlanBeforeReturningAUserFacingExplanation() throws Exception {
        RuntimeAgentWorkflowToolSnapshot pageTool = pageActionTool("wf-page-team", "query_team_on_page");
        stubWorkflow(pageTool);
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("plan-page", "record_supervisor_plan", Map.of(
                        "summary", "尝试在页面查询", "steps", List.of("在页面查询班组"),
                        "workflowToolNames", List.of("query_team_on_page")))),
                calls(call("page-denied", "query_team_on_page", Map.of("teamName", "建设一工班"))),
                calls(call("replan-abandon", "record_supervisor_plan", Map.of(
                        "summary", "页面操作未获授权，安全结束",
                        "steps", List.of("向用户说明未执行"),
                        "workflowToolNames", List.of(), "reason", "没有可安全继续的 Workflow"))),
                calls(call("final-after-denial", "begin_final_answer", Map.of())),
                text("页面操作未执行；请明确要求在页面上操作后重试。"),
                text("页面操作未执行；请明确要求在页面上操作后重试。"))));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageTool), Map.of(
                        "message", "再查询一下建设一工班", "sessionId", "s-policy-abandon",
                        "projectCode", "qmssmp")));

        assertTrue(result.success(), String.valueOf(result.metadata()));
        assertEquals("页面操作未执行；请明确要求在页面上操作后重试。", result.answer());
        assertEquals(2, result.metadata().get("planCount"));
        assertEquals(1, result.metadata().get("replanCount"));
        assertEquals(0, result.metadata().get("workflowCallCount"));
        assertEquals(1, result.metadata().get("guardDenyCount"));
        assertEquals(List.of(), result.metadata().get("plannedWorkflowToolNames"));
    }

    @Test
    void executesPublishedWorkflowWithModelDefaultFromPinnedVersionSnapshot() throws Exception {
        RuntimeAgentWorkflowToolSnapshot tool = tool("wf-model", "classify_intent");
        stubWorkflow(tool, "published-model", "newer-draft-model");
        List<Map<String, Object>> workflowInputs = new ArrayList<>();
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            workflowInputs.add(invocation.getArgument(1));
            return success("classified");
        });
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("plan-model", "record_supervisor_plan", Map.of(
                        "summary", "识别意图", "steps", List.of("执行分类 Workflow"),
                        "workflowToolNames", List.of("classify_intent")))),
                calls(call("workflow-model", "classify_intent", Map.of())),
                text("分类完成"),
                text("分类完成"))));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                agent(), config(false), List.of(tool), Map.of(
                        "message", "我要重置条件", "sessionId", "s-model", "projectCode", "qmssmp",
                        "modelInstanceId", "untrusted-input-model")));

        assertTrue(result.success());
        assertEquals(1, workflowInputs.size());
        assertEquals("published-model", workflowInputs.get(0).get("workflowDefaultModelInstanceId"));
    }

    @Test
    void resumesTheExactNextStructuredWorkflowAfterInteraction() throws Exception {
        RuntimeAgentWorkflowToolSnapshot queryTool = tool("wf-query", "query_team");
        RuntimeAgentWorkflowToolSnapshot disableTool = tool("wf-disable", "disable_team");
        stubWorkflow(queryTool);
        stubWorkflow(disableTool);
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenReturn(success("已停用"));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("disable-1", "disable_team", Map.of("teamId", "team-1"))),
                calls(call("final-1", "begin_final_answer", Map.of())),
                text("班组已停用"),
                text("班组已停用"))));
        Map<String, Object> recordedPlan = Map.of(
                "summary", "查询并停用班组",
                "steps", List.of("查询班组", "停用班组"),
                "workflowToolNames", List.of("query_team", "disable_team"),
                "planNo", 1);
        Map<String, Object> continuation = new LinkedHashMap<>();
        continuation.put("continuationSchemaVersion", 3);
        continuation.put("executionCallCounts", Map.of("workflow", 1, "a2a", 0, "managed", 0));
        continuation.put("agentId", "agent-1");
        continuation.put("agentConfigVersionId", 7L);
        continuation.put("traceId", "trace-1");
        continuation.put("planNo", 1);
        continuation.put("waitingToolName", "query_team");
        continuation.put("plannedWorkflowToolNames", List.of("query_team", "disable_team"));
        continuation.put("plannedWorkflowCursor", 0);
        continuation.put("completedWorkflowToolNames", List.of());
        continuation.put("recordedPlan", recordedPlan);
        continuation.put("originalInput", Map.of(
                "message", "停用一班", "sessionId", "s-resume", "userId", "u-1",
                "appId", "qmssmp", "projectCode", "qmssmp"));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.continueAfterWorkflowInteraction(
                continuation,
                Map.of("success", true, "status", "COMPLETED", "code", "OK", "answer", "找到一班"),
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(queryTool, disableTool), Map.of(
                        "message", "确认", "sessionId", "s-resume", "userId", "u-1",
                        "appId", "qmssmp", "projectCode", "qmssmp", "traceId", "trace-1")));

        assertTrue(result.success(), String.valueOf(result.metadata()));
        assertEquals("班组已停用", result.answer());
        assertEquals(2, result.metadata().get("workflowCallCount"));
        assertEquals(2, result.metadata().get("plannedWorkflowCursor"));
    }

    @Test
    void workflowContinuationCannotResetAnAlreadyConsumedCallBudget() throws Exception {
        var first = tool("wf-first", "first_query");
        var next = tool("wf-next", "next_query");
        stubWorkflow(first); stubWorkflow(next);
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenReturn(success("unexpected call"));
        var continuation = new LinkedHashMap<>(twoStepContinuation());
        continuation.put("executionCallCounts", Map.of("workflow",2,"a2a",0,"managed",0));
        var result = adapter(model(List.of(calls(call("next", "next_query", Map.of())),
                calls(call("final", "begin_final_answer", Map.of())), text("done"))))
                .continueAfterWorkflowInteraction(continuation,
                        Map.of("success",true,"status","COMPLETED","answer","first done"),
                        new SupervisorRuntimeAdapter.SupervisorRequest(agent(),config(false).toBuilder().maxWorkflowCalls(2).build(),
                                List.of(first,next),Map.of("sessionId","budget-resume")));
        assertFalse(result.success(), "the prior consumed budget must constrain the resumed plan");
        verify(graphExecutor, never()).execute(any(),any(),any(),any(),any());
    }

    @Test
    void continuationKeepsPublishedBindingsAndTrustedMemory() throws Exception {
        RuntimeAgentWorkflowToolSnapshot first = tool("wf-first", "first_query");
        RuntimeAgentWorkflowToolSnapshot next = tool("wf-next", "next_query");
        stubWorkflow(first);
        stubWorkflow(next);
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenReturn(success("next query complete"));
        AtomicReference<RuntimeModelServiceClient.ModelChatRequest> modelRequest = new AtomicReference<>();
        RuntimeModelServiceClient script = model(List.of(
                calls(call("next", "next_query", Map.of())),
                calls(call("final", "begin_final_answer", Map.of())), text("query complete")));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(request -> {
            modelRequest.compareAndSet(null, request);
            return script.chat(request);
        });
        var memory = new com.enterprise.ai.runtime.execution.TrustedPersonalMemoryContext(
                "reachai-personal-memory-context-v1", List.of(
                new com.enterprise.ai.runtime.execution.TrustedPersonalMemoryContext.MemorySnippet(
                        1L, "PREFERENCE", "format", "trusted-continuation-memory", null, "USER", 1D)), 0, false);
        var skill = com.enterprise.ai.runtime.agent.RuntimeAgentSkillBindingSnapshot.builder()
                .id(1L).agentId("agent-1").agentConfigVersionId(7L).skillId(2L).skillVersionId(3L)
                .publisher("test").standardName("query-format").version("1").sourceSha256("fixed").enabled(true).build();
        var remote = new SupervisorRuntimeAdapter.RemoteAgentBinding(
                1L, 2L, 3L, 4L, "remote-query", "remote_query", "Published remote query",
                "[\"query\"]", "[\"text/plain\"]", "READ", null, true);
        SupervisorRuntimeAdapter.SupervisorResult result = adapter.continueAfterWorkflowInteraction(
                twoStepContinuation(), Map.of("success", true, "status", "COMPLETED", "answer", "first complete"),
                new SupervisorRuntimeAdapter.SupervisorRequest(agent(), config(false), List.of(first, next),
                        Map.of("sessionId", "s-context", "personalMemory", "spoofed-input-memory"), null, null,
                        null, null, List.of(), memory, List.of(skill), List.of(remote), RuntimeEvalExecutionContext.none()));

        assertTrue(result.success(), String.valueOf(result.metadata()));
        String prompt = modelRequest.get().getMessages().stream()
                .filter(message -> "system".equals(message.getRole()))
                .map(RuntimeModelServiceClient.ModelChatRequest.ChatMessage::getContent).findFirst().orElseThrow();
        List<String> registeredTools = new ArrayList<>();
        modelRequest.get().getTools().forEach(tool -> registeredTools.add(tool.path("function").path("name").asText()));
        Map<?, ?> modelMetadata = (Map<?, ?>) result.metadata().get("model");
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(1, modelMetadata.get("skillBindingCount")),
                () -> assertEquals(1, modelMetadata.get("a2aRemoteAgentBindingCount")),
                () -> assertTrue(registeredTools.contains("remote_query")),
                () -> assertTrue(prompt.contains("trusted-continuation-memory")),
                () -> assertFalse(prompt.contains("spoofed-input-memory")));
        verify(graphExecutor).execute(any(), any(), any(), any(), any(), any());
    }

    @Test
    void continuationPreservesReadOnlyEvaluationPolicy() throws Exception {
        RuntimeAgentWorkflowToolSnapshot first = tool("wf-first", "first_query");
        RuntimeAgentWorkflowToolSnapshot next = tool("wf-next", "next_query");
        stubWorkflow(first);
        stubWorkflow(next);
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenReturn(success("next query complete"));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("next", "next_query", Map.of())),
                calls(call("final", "begin_final_answer", Map.of())), text("query complete"))));
        RuntimeEvalExecutionContext policy = RuntimeEvalExecutionContext.readOnly("exp-context", "item-context", "fixed-target");
        SupervisorRuntimeAdapter.SupervisorResult result = adapter.continueAfterWorkflowInteraction(
                twoStepContinuation(), Map.of("success", true, "status", "COMPLETED", "answer", "first complete"),
                new SupervisorRuntimeAdapter.SupervisorRequest(agent(), config(false), List.of(first, next),
                        Map.of("sessionId", "s-eval-context"), null, null, null, null, List.of(), null,
                        List.of(), List.of(), policy));

        assertTrue(result.success(), String.valueOf(result.metadata()));
        var executedPolicy = org.mockito.ArgumentCaptor.forClass(RuntimeEvalExecutionContext.class);
        verify(graphExecutor).execute(any(), any(), any(), any(), any(), executedPolicy.capture());
        assertEquals(policy, executedPolicy.getValue(), "the next GraphSpec execution must retain the server-owned evaluation policy");
    }

    private Map<String, Object> twoStepContinuation() {
        List<String> tools = List.of("first_query", "next_query");
        return Map.of("continuationSchemaVersion", 3, "agentId", "agent-1", "agentConfigVersionId", 7L,
                "executionCallCounts", Map.of("workflow",1,"a2a",0,"managed",0),
                "traceId", "trace-1", "waitingToolName", "first_query", "plannedWorkflowToolNames", tools,
                "plannedWorkflowCursor", 0, "completedWorkflowToolNames", List.of(),
                "recordedPlan", Map.of("summary", "two queries", "steps", tools, "workflowToolNames", tools, "planNo", 1));
    }

    @Test
    void rejectsLegacyOrUnstructuredInteractionContinuation() {
        RuntimeAgentWorkflowToolSnapshot queryTool = tool("wf-query", "query_team");
        stubWorkflow(queryTool);
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of()));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.continueAfterWorkflowInteraction(
                Map.of(
                        "traceId", "trace-1",
                        "waitingToolName", "query_team",
                        "recordedPlan", Map.of("steps", List.of("query_team"))),
                Map.of("success", true, "status", "COMPLETED", "answer", "ok"),
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(queryTool), Map.of("traceId", "trace-1")));

        assertFalse(result.success());
        assertEquals("RUNTIME_INTERACTION_CONTINUATION_INVALID", result.code());
    }

    @Test
    void persistsLastStepContinuationAsItsOwnTurn() {
        RuntimeSessionMemoryService memoryService = mock(RuntimeSessionMemoryService.class);
        com.enterprise.ai.runtime.memory.RuntimeSessionMemoryKey memoryKey =
                new com.enterprise.ai.runtime.memory.RuntimeSessionMemoryKey(
                        false, "default", null, "agent-1", "s-resume",
                        null, null, "UNTRUSTED");
        org.mockito.Mockito.doReturn("lease-1").when(memoryService)
                .acquireTurn(any(), any(), any());
        when(memoryService.resolve(any(), any(), any())).thenReturn(memoryKey);
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of()), null, memoryService);
        Map<String, Object> continuation = new LinkedHashMap<>();
        continuation.put("continuationSchemaVersion", 3);
        continuation.put("executionCallCounts", Map.of("workflow",1,"a2a",0,"managed",0));
        continuation.put("agentId", "agent-1");
        continuation.put("agentConfigVersionId", 7L);
        continuation.put("traceId", "trace-1");
        continuation.put("waitingToolName", "query_team");
        continuation.put("plannedWorkflowToolNames", List.of("query_team"));
        continuation.put("plannedWorkflowCursor", 0);
        continuation.put("completedWorkflowToolNames", List.of());
        continuation.put("recordedPlan", Map.of(
                "summary", "query",
                "steps", List.of("query"),
                "workflowToolNames", List.of("query_team"),
                "planNo", 1));
        RuntimeAgentWorkflowToolSnapshot queryTool = tool("wf-query", "query_team");
        stubWorkflow(queryTool);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.continueAfterWorkflowInteraction(
                continuation,
                Map.of("success", true, "status", "COMPLETED", "code", "OK", "answer", "完成"),
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(queryTool), Map.of(
                        "sessionId", "s-resume", "interactionId", "wfi_1",
                        "uiSubmit", Map.of("action", "confirm"))));

        assertTrue(result.success(), result.code() + " " + result.answer() + " " + result.metadata());
        verify(memoryService).recordTurn(any(), any(), any(),
                org.mockito.ArgumentMatchers.eq("trace-1"),
                org.mockito.ArgumentMatchers.eq("wfi_1"),
                org.mockito.ArgumentMatchers.eq("Interaction response: confirm"),
                org.mockito.ArgumentMatchers.eq("完成"),
                org.mockito.ArgumentMatchers.eq("SUPERVISOR_COMPLETED"),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq("lease-1"));
    }

    @Test
    void delegatesToManagedExecutorAsAnImmediateAsyncToolAndNeverInvokesGraphSpec() throws Exception {
        RuntimeAgentConfigSnapshot config = config(false).toBuilder()
                .status("PUBLISHED")
                .build();
        ManagedExecutorAgentDelegationService delegation = mock(ManagedExecutorAgentDelegationService.class);
        var managedPolicy = new ManagedExecutorAgentDelegationService.DelegationPolicy(
                true,
                false,
                ManagedExecutorAgentDelegationService.TOOL_NAMES,
                "ANALYZE_READONLY",
                "codex-reviewed",
                "PROJECT_DEFAULT",
                0,
                900,
                300,
                1);
        when(delegation.resolvePolicy(any(), any())).thenReturn(managedPolicy);
        when(delegation.availableTools(any(), any()))
                .thenReturn(ManagedExecutorAgentDelegationService.TOOL_NAMES);
        when(delegation.invoke(
                org.mockito.ArgumentMatchers.eq(ManagedExecutorAgentDelegationService.START_TOOL),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of(
                        "schema", "reachai.managed-executor.delegation-card.v1",
                        "kind", "MANAGED_EXECUTION",
                        "executionId", "mex_1",
                        "status", "QUEUED",
                        "async", true,
                        "productionMutationApplied", false));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("plan-managed", "record_supervisor_plan", Map.of(
                        "summary", "Use the selected managed sandbox",
                        "steps", List.of("Create the asynchronous coding execution"),
                        "workflowToolNames", List.of(ManagedExecutorAgentDelegationService.START_TOOL)))),
                calls(call("start-managed", ManagedExecutorAgentDelegationService.START_TOOL,
                        Map.of("objective", "Inspect the repository and run its governed checks"))),
                calls(call("final-managed", "begin_final_answer", Map.of())),
                text("托管执行 mex_1 已进入队列，当前状态为 QUEUED。"),
                text("托管执行 mex_1 已进入队列，当前状态为 QUEUED。"))));
        adapter.setManagedExecutorDelegationService(delegation);
        WorkflowExecutionIdentity identity = WorkflowExecutionIdentity.fromAgent(
                "tenant-a", 7L, "qmssmp", "user-a");

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(),
                        config,
                        List.of(),
                        Map.of(
                                "message", "请用平台托管执行检查这个复杂代码问题",
                                "sessionId", "s-managed",
                                "managedExecutorRequested", true),
                        null,
                        RuntimeAgentExecutionEventSink.NOOP,
                        RuntimeAgentExecutionCancellation.NOOP,
                        identity));

        assertTrue(result.success(), result.code() + " " + result.answer() + " " + result.metadata());
        assertEquals(1, result.metadata().get("managedExecutorCallCount"));
        assertEquals(0, result.metadata().get("workflowCallCount"));
        assertEquals("MANAGED_EXECUTOR", result.metadata().get("decisionMode"));
        assertEquals(true, result.metadata().get("managedExecutorSelected"));
        assertTrue(result.answer().contains("mex_1"));
        verify(delegation).invoke(
                org.mockito.ArgumentMatchers.eq(ManagedExecutorAgentDelegationService.START_TOOL),
                org.mockito.ArgumentMatchers.eq(managedPolicy),
                org.mockito.ArgumentMatchers.eq(config),
                org.mockito.ArgumentMatchers.argThat(value -> value != null
                        && value.projectTrusted() && value.userTrusted()
                        && "qmssmp".equals(value.projectCode()) && "user-a".equals(value.userId())),
                any(),
                org.mockito.ArgumentMatchers.eq(Map.of(
                        "objective", "Inspect the repository and run its governed checks")),
                org.mockito.ArgumentMatchers.eq("trace-1"));
        verifyNoInteractions(graphExecutor);
    }

    @Test
    void registersOnlyManagedToolsExposedByTheFailClosedPerRunPolicy() {
        RuntimeAgentConfigSnapshot config = config(false).toBuilder()
                .status("PUBLISHED")
                .build();
        ManagedExecutorAgentDelegationService delegation = mock(ManagedExecutorAgentDelegationService.class);
        var managedPolicy = new ManagedExecutorAgentDelegationService.DelegationPolicy(
                true,
                false,
                ManagedExecutorAgentDelegationService.TOOL_NAMES,
                "ANALYZE_READONLY",
                null,
                "PROJECT_DEFAULT",
                0,
                900,
                300,
                1);
        when(delegation.resolvePolicy(any(), any())).thenReturn(managedPolicy);
        when(delegation.availableTools(any(), any())).thenReturn(Set.of(
                ManagedExecutorAgentDelegationService.STATUS_TOOL,
                ManagedExecutorAgentDelegationService.READ_RESULT_TOOL));
        AtomicReference<Set<String>> registered = new AtomicReference<>(Set.of());
        RuntimeModelServiceClient modelClient = request -> {
            Set<String> names = new java.util.LinkedHashSet<>();
            if (request.getTools() != null) {
                request.getTools().forEach(tool -> names.add(tool.path("function").path("name").asText()));
            }
            registered.set(Set.copyOf(names));
            return new ModelChatResult(200, "success", text("请先显式选择平台托管执行模式。"));
        };
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(modelClient);
        adapter.setManagedExecutorDelegationService(delegation);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(),
                        config,
                        List.of(),
                        Map.of("message", "分析一下复杂问题", "sessionId", "s-no-auto-route"),
                        null,
                        RuntimeAgentExecutionEventSink.NOOP,
                        RuntimeAgentExecutionCancellation.NOOP,
                        WorkflowExecutionIdentity.fromAgent(
                                "tenant-a", 7L, "qmssmp", "user-a")));

        assertTrue(result.success());
        assertFalse(registered.get().contains(ManagedExecutorAgentDelegationService.START_TOOL));
        assertTrue(registered.get().contains(ManagedExecutorAgentDelegationService.STATUS_TOOL));
        assertTrue(registered.get().contains(ManagedExecutorAgentDelegationService.READ_RESULT_TOOL));
        assertEquals(0, result.metadata().get("managedExecutorCallCount"));
        verify(delegation, never()).invoke(any(), any(), any(), any(), any(), any(), any());
    }

    private AgentScopeSupervisorRuntimeAdapter adapter(RuntimeModelServiceClient modelClient) {
        return adapter(modelClient, null);
    }

    private AgentScopeSupervisorRuntimeAdapter adapter(RuntimeModelServiceClient modelClient,
                                                       RuntimeControlCatalogClient controlCatalogClient) {
        return adapter(modelClient, controlCatalogClient, null);
    }

    private AgentScopeSupervisorRuntimeAdapter adapter(RuntimeModelServiceClient modelClient,
                                                       RuntimeControlCatalogClient controlCatalogClient,
                                                       RuntimeSessionMemoryService memoryService) {
        SupervisorExecutionTraceService.TraceHandle handle =
                new SupervisorExecutionTraceService.TraceHandle("trace-1", "span-1", 1L, LocalDateTime.now());
        when(traceService.begin(any(), any(), any(), any())).thenReturn(handle);
        when(traceService.begin(any(), any(), any(), any(), any())).thenReturn(handle);
        when(traceService.beginOrResume(any(), any(), any(), any())).thenReturn(handle);
        when(traceService.beginOrResume(any(), any(), any(), any(), any())).thenReturn(handle);
        when(traceService.resume(any())).thenReturn(handle);
        SupervisorToolPolicyService policy = new SupervisorToolPolicyService(
                org.mockito.Mockito.mock(com.enterprise.ai.runtime.runops.RuntimeGuardDecisionWriter.class), approvalService, objectMapper);
        if (memoryService != null) {
            return new AgentScopeSupervisorRuntimeAdapter(
                    modelClient,
                    null,
                    controlCatalogClient,
                    new com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionReader(workflowMapper, versionMapper),
                    graphExecutor,
                    mock(com.enterprise.ai.runtime.execution.RuntimeWorkflowInteractionSessionService.class),
                    memoryService,
                    policy,
                    traceService,
                    objectMapper);
        }
        return new AgentScopeSupervisorRuntimeAdapter(
                modelClient,
                null,
                controlCatalogClient,
                new com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionReader(workflowMapper, versionMapper),
                graphExecutor,
                mock(com.enterprise.ai.runtime.execution.RuntimeWorkflowInteractionSessionService.class),
                new RuntimeChatMemoryStore(20),
                policy,
                traceService,
                objectMapper);
    }

    private AgentScopeSupervisorRuntimeAdapter adapterWithContextEngineering(
            RuntimeModelServiceClient modelClient,
            RuntimeSessionMemoryService memoryService,
            RuntimeContextEngineeringService contextEngineeringService) {
        SupervisorExecutionTraceService.TraceHandle handle =
                new SupervisorExecutionTraceService.TraceHandle(
                        "trace-1", "span-1", 1L, LocalDateTime.now());
        when(traceService.begin(any(), any(), any(), any())).thenReturn(handle);
        when(traceService.begin(any(), any(), any(), any(), any())).thenReturn(handle);
        when(traceService.beginOrResume(any(), any(), any(), any())).thenReturn(handle);
        when(traceService.beginOrResume(any(), any(), any(), any(), any())).thenReturn(handle);
        when(traceService.resume(any())).thenReturn(handle);
        SupervisorToolPolicyService policy = new SupervisorToolPolicyService(
                org.mockito.Mockito.mock(com.enterprise.ai.runtime.runops.RuntimeGuardDecisionWriter.class), mock(SupervisorApprovalInteractionService.class), objectMapper);
        return new AgentScopeSupervisorRuntimeAdapter(
                modelClient,
                null,
                null,
                new com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionReader(workflowMapper, versionMapper),
                graphExecutor,
                mock(com.enterprise.ai.runtime.execution.RuntimeWorkflowInteractionSessionService.class),
                memoryService,
                policy,
                traceService,
                objectMapper,
                contextEngineeringService);
    }

    private RuntimeModelServiceClient model(List<ModelChatData> responses) {
        AtomicInteger index = new AtomicInteger();
        return request -> {
            int responseIndex = index.getAndIncrement();
            if (responseIndex >= responses.size()) throw new IllegalStateException("Unexpected model call");
            return new ModelChatResult(200, "success", responses.get(responseIndex));
        };
    }

    private ModelChatData text(String content) {
        return new ModelChatData(content, "test", "test", new ModelUsage(1, 1, 2),
                null, null, "stop");
    }

    @SafeVarargs
    private final ModelChatData calls(Map<String, Object>... calls) {
        return new ModelChatData(null, "test", "test", new ModelUsage(1, 1, 2),
                null, objectMapper.valueToTree(List.of(calls)), "tool_calls");
    }

    private Map<String, Object> call(String id, String name, Map<String, Object> args) throws Exception {
        return Map.of(
                "id", id,
                "type", "function",
                "function", Map.of("name", name, "arguments", objectMapper.writeValueAsString(args)));
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
                .totalTimeoutMs(10_000)
                .workflowTimeoutMs(5_000)
                .pageBridgeTimeoutMs(2_000)
                .parallelReadOnly(parallelReadOnly)
                .policyProfile("DEV_ALLOW_ALL")
                .toolCatalogMode("ALLOW_LIST")
                .configJson("{}")
                .build();
        return config;
    }

    private RuntimeAgentView agent() {
        return new RuntimeAgentView(
                "agent-1", 7L, "qmssmp", "team-assistant", "班组助手", null,
                "PROJECT", null, true,
                7L, 7L, 1, "ACTIVE", "AGENTSCOPE", 2, null, null);
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
                .readOnly(true)
                .enabled(true)
                .build();
        return tool;
    }

    private RuntimeAgentWorkflowToolSnapshot pageActionTool(String workflowId, String toolName) {
        RuntimeAgentWorkflowToolSnapshot tool = tool(workflowId, toolName).toBuilder()
                .riskLevel("PAGE_ACTION")
                .permissionKey(toolName + ":page")
                .readOnly(false)
                .build();
        return tool;
    }

    private void stubWorkflow(RuntimeAgentWorkflowToolSnapshot tool) {
        stubWorkflow(tool, null, null);
    }

    private void stubWorkflow(RuntimeAgentWorkflowToolSnapshot tool,
                              String publishedModelInstanceId,
                              String currentDraftModelInstanceId) {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId(tool.getWorkflowId());
        workflow.setKeySlug(tool.getToolName());
        workflow.setName(tool.getToolName());
        workflow.setDescription(tool.getToolName());
        workflow.setInputSchemaJson("{\"type\":\"object\",\"additionalProperties\":true}");
        workflow.setStatus("ACTIVE");
        workflow.setDefaultModelInstanceId(currentDraftModelInstanceId);
        RuntimeWorkflowVersionEntity version = new RuntimeWorkflowVersionEntity();
        version.setId((long) Math.abs(tool.getWorkflowId().hashCode()));
        version.setWorkflowId(tool.getWorkflowId());
        version.setVersion("1.0.0");
        version.setStatus("ACTIVE");
        version.setGraphSpecSnapshotJson("{\"nodes\":[],\"edges\":[]}");
        if (publishedModelInstanceId != null) {
            version.setSnapshotJson("{\"defaultModelInstanceId\":\"" + publishedModelInstanceId + "\"}");
        } else {
            version.setSnapshotJson("{\"defaultModelInstanceId\":null}");
        }
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

    private void setPageActionGraph(RuntimeAgentWorkflowToolSnapshot tool,
                                    String pageKey,
                                    String actionKey,
                                    String... inputNames) {
        RuntimeWorkflowVersionEntity version = workflowVersions.get(tool.getWorkflowVersionId());
        Map<String, Object> inputMapping = new LinkedHashMap<>();
        for (String inputName : inputNames) {
            inputMapping.put(inputName, "params." + inputName);
        }
        try {
            version.setGraphSpecSnapshotJson(objectMapper.writeValueAsString(Map.of(
                    "nodes", List.of(Map.of(
                            "id", "page-action",
                            "type", "PAGE_ACTION",
                            "config", Map.of(
                                    "pageKey", pageKey,
                                    "actionKey", actionKey,
                                    "inputMapping", inputMapping))),
                    "edges", List.of())));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private RuntimeControlCatalogClient.PageActionCatalogEntry pageAction(
            String projectCode,
            String pageKey,
            String actionKey,
            String riskLevel,
            boolean confirmRequired,
            Object inputSchema,
            String status) {
        return new RuntimeControlCatalogClient.PageActionCatalogEntry(
                1L, projectCode, pageKey, actionKey, "Query", "Read page data",
                riskLevel, confirmRequired, null, inputSchema, Map.of(), Map.of(),
                List.of(), null, Map.of(), status);
    }

    private RuntimeGraphSpecExecutionResult success(String answer) {
        return new RuntimeGraphSpecExecutionResult(true, "OK", answer,
                null, null, new ArrayList<>(), Map.of());
    }

    private RuntimeGraphSpecExecutionResult successWithStructuredCount(int total) {
        return new RuntimeGraphSpecExecutionResult(true, "OK", "{message=执行成功, data={total=" + total + "}}",
                null, null, new ArrayList<>(), Map.of(), Map.of(
                "previousOutput", Map.of("message", "执行成功", "data", Map.of("total", total))));
    }

    private RuntimeGraphSpecExecutionResult successWithPageActionSummary(int total) {
        return new RuntimeGraphSpecExecutionResult(true, "OK", "页面动作已完成",
                null, null, new ArrayList<>(), Map.of(), Map.of(
                "pageActionResults", List.of(Map.of(
                        "actionKey", "query", "success", true, "status", "SUCCESS",
                        "total", total, "empty", total == 0))));
    }
}
