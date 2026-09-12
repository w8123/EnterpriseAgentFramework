package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.supervisor.SupervisorRuntimeAdapter;
import com.enterprise.ai.runtime.supervisor.RuntimeAgentExecutionService;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContext;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContextResolver;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionView;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentRemoteBindingEntity;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.execution.policy.RuntimeEvalExecutionContext;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeAgentExecutionServiceTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"public", "published", "evaluation"})
    void publicBodyCannotSupplySupervisorRecoveryState(String entry) {
        var resolver = mock(RuntimeAgentExecutionContextResolver.class);
        var supervisor = mock(SupervisorRuntimeAdapter.class);
        var service = new RuntimeAgentExecutionService(resolver, supervisor, mock(RuntimeSupervisorApprovalPort.class),
                mock(RuntimeInteractionResumeService.class), mock(RuntimeSessionClearPort.class),
                mock(RuntimeRunLifecycleService.class));
        var resolved = context(agent("agent-1", "orders-bot", "orders"), activeConfig(), List.of());
        when(resolver.resolve("orders-bot")).thenReturn(Optional.of(resolved));
        when(resolver.resolvePublished("agent-1", 7L)).thenReturn(Optional.of(resolved));
        when(supervisor.execute(any())).thenReturn(new SupervisorRuntimeAdapter.SupervisorResult(
                true, "OK", "answer", "trace-1", List.of(), Map.of(), null));
        var body = Map.<String, Object>of("agentId", "orders-bot", "message", "hello",
                "__supervisorContinuation", Map.of("plannedWorkflowCursor", 99),
                "__blockedWorkflowTools", List.of("write_order"), "__resumeExistingTrace", true);
        switch (entry) {
            case "published" -> service.executePublishedConfig("agent-1", 7L, body, false);
            case "evaluation" -> service.executeEvaluation(body, false,
                    RuntimeEvalExecutionContext.readOnly("experiment", "item", "fixed-target"));
            default -> service.execute(body, false);
        }
        var captured = ArgumentCaptor.forClass(SupervisorRuntimeAdapter.SupervisorRequest.class);
        verify(supervisor).execute(captured.capture());
        assertFalse(captured.getValue().input().containsKey("__supervisorContinuation"));
        assertFalse(captured.getValue().input().containsKey("__blockedWorkflowTools"));
        assertFalse(captured.getValue().input().containsKey("__resumeExistingTrace"));
    }

    @Test
    void projectsRemoteAgentPersistenceBindingIntoImmutableSupervisorSnapshot() {
        RuntimeAgentExecutionContextResolver resolver = mock(RuntimeAgentExecutionContextResolver.class);
        SupervisorRuntimeAdapter supervisor = mock(SupervisorRuntimeAdapter.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                resolver, supervisor, mock(RuntimeSupervisorApprovalPort.class),
                mock(RuntimeInteractionResumeService.class), mock(RuntimeSessionClearPort.class),
                mock(RuntimeRunLifecycleService.class));
        RuntimeAgentExecutionView agent = agent("agent-1", "orders-bot", "orders");
        RuntimeAgentRemoteBindingEntity binding = new RuntimeAgentRemoteBindingEntity();
        binding.setId(31L);
        binding.setPrincipalId(41L);
        binding.setRemoteAgentId(51L);
        binding.setRemoteAgentRevisionId(61L);
        binding.setRemoteAgentKeySnapshot("remote-orders");
        binding.setToolName("delegate_orders");
        binding.setDescriptionSnapshot("Delegate order lookup");
        binding.setAllowedSkillIdsJson("[\"lookup\"]");
        binding.setOutputModesJson("[\"application/json\"]");
        binding.setRiskLevel("READ");
        binding.setPermissionKey("a2a.orders.read");
        binding.setEnabled(true);
        RuntimeAgentExecutionContext resolved = new RuntimeAgentExecutionContext(
                agent, activeConfig(), List.of(), List.of(), List.of(com.enterprise.ai.runtime.agent.RuntimeAgentRemoteBindingView.fromEntity(binding)), List.of(),
                new RuntimeAgentExecutionContext.ResolveTimings(1L, 2L, 3L, 4L, 5L, 6L, 21L));
        when(resolver.resolve("orders-bot")).thenReturn(Optional.of(resolved));
        when(supervisor.execute(any())).thenReturn(new SupervisorRuntimeAdapter.SupervisorResult(
                true, "OK", "answer", "trace-remote", List.of(), Map.of(), null));

        service.execute(Map.of("agentId", "orders-bot", "message", "delegate"), false);

        ArgumentCaptor<SupervisorRuntimeAdapter.SupervisorRequest> captor =
                ArgumentCaptor.forClass(SupervisorRuntimeAdapter.SupervisorRequest.class);
        verify(supervisor).execute(captor.capture());
        SupervisorRuntimeAdapter.RemoteAgentBinding snapshot =
                captor.getValue().remoteAgents().get(0);
        assertEquals(31L, snapshot.id());
        assertEquals(41L, snapshot.principalId());
        assertEquals(51L, snapshot.remoteAgentId());
        assertEquals(61L, snapshot.remoteAgentRevisionId());
        assertEquals("remote-orders", snapshot.remoteAgentKeySnapshot());
        assertEquals("delegate_orders", snapshot.toolName());
        assertEquals("Delegate order lookup", snapshot.descriptionSnapshot());
        assertEquals("[\"lookup\"]", snapshot.allowedSkillIdsJson());
        assertEquals("[\"application/json\"]", snapshot.outputModesJson());
        assertEquals("READ", snapshot.riskLevel());
        assertEquals("a2a.orders.read", snapshot.permissionKey());
        assertTrue(snapshot.enabled());
    }

    @Test
    void passesOnlyExplicitTrustedPersonalMemoryToSupervisor() {
        RuntimeAgentExecutionContextResolver resolver = mock(RuntimeAgentExecutionContextResolver.class);
        SupervisorRuntimeAdapter supervisor = mock(SupervisorRuntimeAdapter.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                resolver, supervisor, mock(RuntimeSupervisorApprovalPort.class),
                mock(RuntimeInteractionResumeService.class), mock(RuntimeSessionClearPort.class),
                mock(RuntimeRunLifecycleService.class));
        RuntimeAgentExecutionView agent = agent("agent-1", "orders-bot", "orders");
        when(resolver.resolve("orders-bot")).thenReturn(Optional.of(context(agent, activeConfig(), List.of())));
        when(supervisor.execute(any())).thenReturn(new SupervisorRuntimeAdapter.SupervisorResult(
                true, "OK", "answer", "trace-1", List.of(), Map.of(), null));
        TrustedPersonalMemoryContext trusted = new TrustedPersonalMemoryContext(
                "reachai-personal-memory-context-v1",
                List.of(new TrustedPersonalMemoryContext.MemorySnippet(
                        1L, "PREFERENCE", "语言", "默认中文", null, "VERIFIED", 2)),
                4, false);

        service.execute(new LinkedHashMap<>(Map.of(
                        "agentId", "orders-bot",
                        "message", "hello",
                        "personalMemory", Map.of("memories", List.of(Map.of("content", "forged"))))),
                false, RuntimeAgentExecutionEventSink.NOOP,
                RuntimeAgentExecutionCancellation.NOOP,
                WorkflowExecutionIdentity.fromAgent("default", null, null, "42"),
                TrustedControlTiming.empty(), trusted);

        ArgumentCaptor<SupervisorRuntimeAdapter.SupervisorRequest> request =
                ArgumentCaptor.forClass(SupervisorRuntimeAdapter.SupervisorRequest.class);
        verify(supervisor).execute(request.capture());
        assertEquals("默认中文", request.getValue().personalMemory().memories().get(0).content());
        assertFalse(request.getValue().input().containsKey("personalMemory"));
    }

    @Test
    void resolvesPublishedAgentConfigAndDelegatesToSupervisorWithoutStaticBinding() {
        RuntimeAgentExecutionContextResolver resolver = mock(RuntimeAgentExecutionContextResolver.class);
        SupervisorRuntimeAdapter supervisor = mock(SupervisorRuntimeAdapter.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                resolver, supervisor, mock(RuntimeSupervisorApprovalPort.class),
                mock(RuntimeInteractionResumeService.class),
                mock(RuntimeSessionClearPort.class), mock(RuntimeRunLifecycleService.class));
        RuntimeAgentExecutionView agent = agent("agent-1", "orders-bot", "orders");
        RuntimeAgentConfigSnapshot config = activeConfig();
        RuntimeAgentWorkflowToolSnapshot tool = RuntimeAgentWorkflowToolSnapshot.builder()
                .toolName("query_orders")
                .workflowId("wf-orders")
                .build();
        Map<String, Object> request = Map.of(
                "agentId", "orders-bot",
                "message", "hello",
                "sessionId", "s1");
        when(resolver.resolve("orders-bot")).thenReturn(Optional.of(context(agent, config, List.of(tool))));
        when(supervisor.execute(any())).thenReturn(new SupervisorRuntimeAdapter.SupervisorResult(
                true,
                "SUPERVISOR_COMPLETED",
                "订单查询完成",
                "trace-1",
                List.of(Map.of("name", "plan", "detail", "query orders")),
                Map.of("workflowCallCount", 1),
                null));

        Map<String, Object> response = service.execute(request, true);

        assertEquals(true, response.get("success"));
        assertEquals("订单查询完成", response.get("answer"));
        assertEquals("s1", response.get("sessionId"));
        Map<?, ?> metadata = (Map<?, ?>) response.get("metadata");
        assertEquals("SUPERVISOR_COMPLETED", metadata.get("code"));
        assertEquals(7L, metadata.get("agentConfigVersionId"));
        assertEquals("trace-1", metadata.get("traceId"));
        verify(resolver).resolve("orders-bot");
        verify(supervisor).execute(any());

    }

    @Test
    void executesCapturedDraftTargetAndIgnoresCallerEvalAndAgentOverrides() {
        RuntimeAgentExecutionContextResolver resolver = mock(RuntimeAgentExecutionContextResolver.class);
        SupervisorRuntimeAdapter supervisor = mock(SupervisorRuntimeAdapter.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                resolver, supervisor, mock(RuntimeSupervisorApprovalPort.class),
                mock(RuntimeInteractionResumeService.class), mock(RuntimeSessionClearPort.class),
                mock(RuntimeRunLifecycleService.class));
        RuntimeAgentConfigSnapshot draft = activeConfig().toBuilder()
                .id(12L)
                .status("DRAFT")
                .build();
        RuntimeAgentExecutionContext captured = context(
                agent("agent-draft", "orders-draft", "orders"), draft, List.of());
        when(supervisor.execute(any())).thenReturn(new SupervisorRuntimeAdapter.SupervisorResult(
                true, "SUPERVISOR_COMPLETED", "ok", "trace-eval", List.of(), Map.of(), null));
        RuntimeEvalExecutionContext evaluation =
                RuntimeEvalExecutionContext.readOnly("exp-1", "item-1", "fingerprint-1");

        Map<String, Object> response = service.executeEvaluation(captured, new LinkedHashMap<>(Map.of(
                "agentId", "forged-active-agent",
                "message", "query",
                "evalMode", false,
                "evaluationPolicy", Map.of("mode", "NONE"))), true, evaluation);

        ArgumentCaptor<SupervisorRuntimeAdapter.SupervisorRequest> requestCaptor =
                ArgumentCaptor.forClass(SupervisorRuntimeAdapter.SupervisorRequest.class);
        verify(supervisor).execute(requestCaptor.capture());
        assertEquals("agent-draft", requestCaptor.getValue().input().get("agentId"));
        assertEquals("EVAL", requestCaptor.getValue().input().get("entryType"));
        assertFalse(requestCaptor.getValue().input().containsKey("evalMode"));
        assertFalse(requestCaptor.getValue().input().containsKey("evaluationPolicy"));
        assertEquals(evaluation, requestCaptor.getValue().evalContext());
        assertEquals(12L, requestCaptor.getValue().config().getId());
        assertEquals("DRAFT", requestCaptor.getValue().config().getStatus());
        assertEquals("fingerprint-1", ((Map<?, ?>) response.get("metadata"))
                .get("evalTargetFingerprint"));
        verify(resolver, never()).resolve(any());
    }

    @Test
    void returnsRuntimeOwnedErrorWhenAgentCannotBeResolved() {
        RuntimeAgentExecutionContextResolver resolver = mock(RuntimeAgentExecutionContextResolver.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                resolver, mock(SupervisorRuntimeAdapter.class),
                mock(RuntimeSupervisorApprovalPort.class), mock(RuntimeInteractionResumeService.class),
                mock(RuntimeSessionClearPort.class), mock(RuntimeRunLifecycleService.class));
        when(resolver.resolve("missing")).thenReturn(Optional.empty());

        Map<String, Object> response = service.execute(Map.of("agentId", "missing"), false);

        assertEquals(false, response.get("success"));
        assertEquals("Agent not found: missing", response.get("answer"));
        assertFalse(response.containsKey("steps"));
        assertEquals("RUNTIME_AGENT_NOT_FOUND", ((Map<?, ?>) response.get("metadata")).get("code"));
    }

    @Test
    void refusesDraftOnlyAgentInsteadOfFallingBackToBinding() {
        RuntimeAgentExecutionContextResolver resolver = mock(RuntimeAgentExecutionContextResolver.class);
        SupervisorRuntimeAdapter supervisor = mock(SupervisorRuntimeAdapter.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                resolver, supervisor, mock(RuntimeSupervisorApprovalPort.class),
                mock(RuntimeInteractionResumeService.class),
                mock(RuntimeSessionClearPort.class), mock(RuntimeRunLifecycleService.class));
        when(resolver.resolve("orders-bot")).thenReturn(Optional.of(context(
                agent("agent-1", "orders-bot", "orders"), null, List.of())));

        Map<String, Object> response = service.execute(Map.of("agentId", "orders-bot"), true);

        assertEquals(false, response.get("success"));
        assertEquals("RUNTIME_AGENT_CONFIG_NOT_PUBLISHED", ((Map<?, ?>) response.get("metadata")).get("code"));
        assertTrue(response.get("answer").toString().contains("no published Supervisor configuration"));
    }

    @Test
    void resumesSupervisorApprovalWithServerIssuedOneTimeGrant() {
        RuntimeAgentExecutionContextResolver resolver = mock(RuntimeAgentExecutionContextResolver.class);
        SupervisorRuntimeAdapter supervisor = mock(SupervisorRuntimeAdapter.class);
        RuntimeSupervisorApprovalPort approvals = mock(RuntimeSupervisorApprovalPort.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                resolver, supervisor, approvals, mock(RuntimeInteractionResumeService.class),
                mock(RuntimeSessionClearPort.class), mock(RuntimeRunLifecycleService.class));
        RuntimeAgentExecutionView agent = agent("agent-1", "orders-bot", "orders");
        RuntimeAgentConfigSnapshot config = activeConfig();
        RuntimeSupervisorApprovalPort.PolicyApprovalGrant grant = new RuntimeSupervisorApprovalPort.PolicyApprovalGrant(
                "spv_1", "orders:write", "update_order", Map.of("orderId", "O-1"), "u-1");
        when(approvals.prepareResume(any(), any(), any()))
                .thenReturn(new RuntimeSupervisorApprovalService.ResumeDecision(
                        true, false, "agent-1", 7L,
                        Map.of("message", "更新订单", "sessionId", "s1", "agentId", "agent-1",
                                "traceId", "trace-original"),
                        grant, "审批已通过", "attempt-1", Map.of("action", "confirm"), null));
        when(approvals.completeResume(any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(3));
        when(resolver.resolvePublished("agent-1", 7L)).thenReturn(Optional.of(context(agent, config, List.of())));
        when(supervisor.execute(any())).thenReturn(new SupervisorRuntimeAdapter.SupervisorResult(
                true, "SUPERVISOR_COMPLETED", "订单已更新", "trace-original", List.of(), Map.of(), null));
        WorkflowExecutionIdentity trustedIdentity =
                WorkflowExecutionIdentity.fromAgent("default", 7L, "orders", "u-1");

        Map<String, Object> response = service.execute(Map.of(
                "interactionId", "spv_1",
                "traceId", "trace-submit-must-not-win",
                "sessionId", "s1",
                "userId", "forged-body-user",
                "uiSubmit", Map.of("action", "confirm")), true,
                RuntimeAgentExecutionEventSink.NOOP,
                RuntimeAgentExecutionCancellation.NOOP,
                trustedIdentity);

        assertEquals(true, response.get("success"));
        assertEquals("订单已更新", response.get("answer"));
        verify(approvals).prepareResume(any(), any(), same(trustedIdentity));
        ArgumentCaptor<SupervisorRuntimeAdapter.SupervisorRequest> captor =
                ArgumentCaptor.forClass(SupervisorRuntimeAdapter.SupervisorRequest.class);
        verify(supervisor).execute(captor.capture());
        assertEquals(grant, captor.getValue().approvalGrant());
        assertEquals("agent-1", captor.getValue().input().get("agentId"));
        assertEquals("trace-original", captor.getValue().input().get("traceId"));
        assertEquals(true, captor.getValue().input().get("__resumeExistingTrace"));
        assertEquals("trace-original", ((Map<?, ?>) response.get("metadata")).get("traceId"));
    }

    @Test
    void returnsCompletedSupervisorApprovalReplayWithoutExecutingAgain() {
        RuntimeAgentExecutionContextResolver resolver = mock(RuntimeAgentExecutionContextResolver.class);
        SupervisorRuntimeAdapter supervisor = mock(SupervisorRuntimeAdapter.class);
        RuntimeSupervisorApprovalPort approvals = mock(RuntimeSupervisorApprovalPort.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                resolver, supervisor, approvals, mock(RuntimeInteractionResumeService.class),
                mock(RuntimeSessionClearPort.class), mock(RuntimeRunLifecycleService.class));
        Map<String, Object> replay = Map.of(
                "success", true,
                "answer", "订单已更新",
                "metadata", Map.of(
                        "code", "SUPERVISOR_COMPLETED",
                        "interactionId", "spv_1",
                        "idempotentReplay", true));
        when(approvals.prepareResume(any(), any(), any()))
                .thenReturn(new RuntimeSupervisorApprovalService.ResumeDecision(
                        false, false, "agent-1", 7L, Map.of(), null, "",
                        "attempt-1", Map.of("action", "confirm"), replay));

        Map<String, Object> response = service.execute(Map.of(
                "interactionId", "spv_1",
                "idempotencyKey", "attempt-1",
                "uiSubmit", Map.of("action", "confirm")), true);

        assertEquals(replay, response);
        verify(supervisor, never()).execute(any());
        verify(resolver, never()).resolve(any());
        verify(approvals, never()).completeResume(any(), any(), any(), any());
    }

    @Test
    void returnsSafeResultWhenUserRejectsSupervisorApproval() {
        RuntimeSupervisorApprovalPort approvals = mock(RuntimeSupervisorApprovalPort.class);
        RuntimeRunLifecycleService runLifecycle = mock(RuntimeRunLifecycleService.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                mock(RuntimeAgentExecutionContextResolver.class), mock(SupervisorRuntimeAdapter.class), approvals,
                mock(RuntimeInteractionResumeService.class),
                mock(RuntimeSessionClearPort.class), runLifecycle);
        when(approvals.prepareResume(any(), any(), any())).thenReturn(
                new RuntimeSupervisorApprovalService.ResumeDecision(
                        false, true, "agent-1", 7L, Map.of("traceId", "trace-original"), null,
                        "用户已拒绝执行该 Workflow Tool", "attempt-1", Map.of("action", "reject"), null));
        when(approvals.completeResume(any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(3));

        Map<String, Object> response = service.execute(Map.of(
                "interactionId", "spv_1",
                "traceId", "trace-submit-must-not-win",
                "uiSubmit", Map.of("action", "reject")), false);

        assertEquals(true, response.get("success"));
        assertEquals("SUPERVISOR_ACTION_REJECTED", ((Map<?, ?>) response.get("metadata")).get("code"));
        assertEquals("trace-original", ((Map<?, ?>) response.get("metadata")).get("traceId"));
        verify(runLifecycle).resumeAgent("trace-original");
        verify(runLifecycle).finishAgent(
                org.mockito.ArgumentMatchers.eq("trace-original"),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq("SUPERVISOR_ACTION_REJECTED"),
                any(), any(), any(), any());
    }

    @Test
    void resumesWorkflowInteractionUsingSessionTraceIdNotRequestTraceId() {
        RuntimeInteractionResumeService resumeService = mock(RuntimeInteractionResumeService.class);
        RuntimeRunLifecycleService runLifecycle = mock(RuntimeRunLifecycleService.class);
        SupervisorRuntimeAdapter supervisor = mock(SupervisorRuntimeAdapter.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                mock(RuntimeAgentExecutionContextResolver.class), supervisor,
                mock(RuntimeSupervisorApprovalPort.class), resumeService,
                mock(RuntimeSessionClearPort.class), runLifecycle);
        when(resumeService.resume(any(), any(), any())).thenReturn(Map.of(
                "success", true,
                "code", "RUNTIME_GRAPH_EXECUTED",
                "answer", "done",
                "status", "COMPLETED",
                "waiting", false,
                "traceId", "session-trace-original",
                "runId", "session-run-original",
                "interactionId", "wfi_abc"));

        Map<String, Object> response = service.execute(Map.of(
                "interactionId", "wfi_abc",
                "traceId", "brand-new-submit-trace",
                "sessionId", "chat-1",
                "appId", "app-1",
                "userId", "user-1",
                "values", Map.of("q", "1")), false);

        assertEquals(true, response.get("success"));
        assertEquals("session-trace-original", response.get("traceId"));
        assertEquals("session-run-original", response.get("runId"));
        assertEquals("session-trace-original", ((Map<?, ?>) response.get("metadata")).get("traceId"));
        ArgumentCaptor<String> finishTrace = ArgumentCaptor.forClass(String.class);
        verify(runLifecycle).finishAgent(finishTrace.capture(), any(Boolean.class), any(), any(), any(), any(), any());
        assertEquals("session-trace-original", finishTrace.getValue());
        verify(supervisor, never()).execute(any());
    }

    @Test
    void consumesSupervisorContinuationAfterWorkflowInteraction() {
        RuntimeInteractionResumeService resumeService = mock(RuntimeInteractionResumeService.class);
        RuntimeAgentExecutionContextResolver resolver = mock(RuntimeAgentExecutionContextResolver.class);
        SupervisorRuntimeAdapter supervisor = mock(SupervisorRuntimeAdapter.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                resolver, supervisor, mock(RuntimeSupervisorApprovalPort.class),
                resumeService, mock(RuntimeSessionClearPort.class), mock(RuntimeRunLifecycleService.class));
        RuntimeAgentExecutionView agent = agent("agent-1", "orders-bot", "orders");
        RuntimeAgentConfigSnapshot config = activeConfig();
        when(resumeService.resume(any(), any(), any())).thenReturn(Map.of(
                "success", true,
                "code", "RUNTIME_GRAPH_EXECUTED",
                "answer", "workflow-done",
                "status", "COMPLETED",
                "waiting", false,
                "traceId", "trace-cont",
                "runId", "run-cont",
                "interactionId", "wfi_cont",
                "continuation", Map.of(
                        "agentId", "agent-1",
                        "agentConfigVersionId", 7L,
                        "traceId", "trace-cont",
                        "waitingToolName", "query_orders",
                        "originalInput", Map.of("message", "查订单", "sessionId", "s1"))));
        when(resolver.resolvePublished("agent-1", 7L)).thenReturn(Optional.of(context(agent, config, List.of())));
        when(supervisor.continueAfterWorkflowInteraction(any(), any(), any())).thenReturn(
                new SupervisorRuntimeAdapter.SupervisorResult(
                        true, "SUPERVISOR_COMPLETED", "final-answer", "trace-cont",
                        List.of(), Map.of("continuationConsumed", true, "continuationMode", "LAST_WORKFLOW_STEP"),
                        null));

        Map<String, Object> response = service.execute(Map.of(
                "interactionId", "wfi_cont",
                "traceId", "should-not-win",
                "sessionId", "s1",
                "appId", "orders",
                "userId", "u1"), false);

        assertEquals(true, response.get("success"));
        assertEquals("final-answer", response.get("answer"));
        assertEquals("trace-cont", response.get("traceId"));
        assertEquals(true, ((Map<?, ?>) response.get("metadata")).get("continuationConsumed"));
        ArgumentCaptor<SupervisorRuntimeAdapter.SupervisorRequest> continuationRequest =
                ArgumentCaptor.forClass(SupervisorRuntimeAdapter.SupervisorRequest.class);
        verify(supervisor).continueAfterWorkflowInteraction(any(), any(), continuationRequest.capture());
        assertTrue(String.valueOf(continuationRequest.getValue().input().get("__memoryTurnId"))
                .matches("[0-9a-f-]{36}"));
        verify(supervisor, never()).execute(any());
    }

    @Test
    void executesSpecifiedArchivedConfigWithoutResolvingCurrentActiveConfig() {
        RuntimeAgentExecutionContextResolver resolver = mock(RuntimeAgentExecutionContextResolver.class);
        SupervisorRuntimeAdapter supervisor = mock(SupervisorRuntimeAdapter.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                resolver, supervisor, mock(RuntimeSupervisorApprovalPort.class),
                mock(RuntimeInteractionResumeService.class),
                mock(RuntimeSessionClearPort.class), mock(RuntimeRunLifecycleService.class));
        RuntimeAgentExecutionView agent = agent("agent-1", "orders-bot", "orders");
        RuntimeAgentConfigSnapshot archived = RuntimeAgentConfigSnapshot.builder()
                .id(91L)
                .agentId("agent-1")
                .versionNo(4)
                .status("ARCHIVED")
                .runtimeType("AGENTSCOPE")
                .build();
        RuntimeAgentWorkflowToolSnapshot pinnedTool = RuntimeAgentWorkflowToolSnapshot.builder()
                .agentId("agent-1")
                .agentConfigVersionId(91L)
                .workflowId("wf-orders")
                .workflowVersionId(42L)
                .toolName("query_orders_v4")
                .build();
        when(resolver.resolvePublished("agent-1", 91L))
                .thenReturn(Optional.of(context(agent, archived, List.of(pinnedTool))));
        when(supervisor.execute(any())).thenReturn(new SupervisorRuntimeAdapter.SupervisorResult(
                true, "SUPERVISOR_COMPLETED", "历史配置执行完成", "trace-replay",
                List.of(), Map.of("workflowCallCount", 1), null));

        Map<String, Object> response = service.executePublishedConfig(
                "agent-1",
                91L,
                Map.of("message", "重放这次运行", "traceId", "trace-replay"),
                true);

        assertEquals(true, response.get("success"));
        assertEquals("历史配置执行完成", response.get("answer"));
        assertEquals(91L, ((Map<?, ?>) response.get("metadata")).get("agentConfigVersionId"));
        verify(resolver).resolvePublished("agent-1", 91L);
        ArgumentCaptor<SupervisorRuntimeAdapter.SupervisorRequest> captor =
                ArgumentCaptor.forClass(SupervisorRuntimeAdapter.SupervisorRequest.class);
        verify(supervisor).execute(captor.capture());
        assertEquals(archived, captor.getValue().config());
        assertEquals(List.of(pinnedTool), captor.getValue().workflowTools());
        assertEquals("REPLAY", captor.getValue().input().get("entryType"));
    }

    @Test
    void publicExecuteIgnoresNestedControlTimingAndOuterControlKeys() {
        RuntimeAgentExecutionContextResolver resolver = mock(RuntimeAgentExecutionContextResolver.class);
        SupervisorRuntimeAdapter supervisor = mock(SupervisorRuntimeAdapter.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                resolver, supervisor, mock(RuntimeSupervisorApprovalPort.class),
                mock(RuntimeInteractionResumeService.class),
                mock(RuntimeSessionClearPort.class), mock(RuntimeRunLifecycleService.class));
        RuntimeAgentExecutionView agent = agent("agent-1", "orders-bot", "orders");
        RuntimeAgentConfigSnapshot config = activeConfig();
        when(resolver.resolve("orders-bot")).thenReturn(Optional.of(context(agent, config, List.of())));
        when(supervisor.execute(any())).thenReturn(new SupervisorRuntimeAdapter.SupervisorResult(
                true, "SUPERVISOR_COMPLETED", "你好", "trace-timing",
                List.of(), Map.of("decisionMode", "DIRECT", "modelRoundCount", 1), null));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("agentId", "orders-bot");
        request.put("message", "你好");
        request.put("entryType", "EMBED");
        request.put("intentHint", "EMBED_CHAT");
        request.put("control.sessionLookupMs", 987654L);
        request.put("metadata", Map.of("control.userMessageAuditMs", 876543L));
        request.put("controlTiming", Map.of(
                "control.sessionLookupMs", 987654L,
                "control.userMessageAuditMs", 876543L,
                "control.preRuntimeMs", 765432L));

        Map<String, Object> response = service.execute(request, false);
        Map<?, ?> metadata = (Map<?, ?>) response.get("metadata");
        assertNull(metadata.get("control.sessionLookupMs"));
        assertNull(metadata.get("control.userMessageAuditMs"));
        assertNull(metadata.get("control.preRuntimeMs"));
        assertFalse(metadata.containsKey("controlTiming"));

        ArgumentCaptor<SupervisorRuntimeAdapter.SupervisorRequest> captor =
                ArgumentCaptor.forClass(SupervisorRuntimeAdapter.SupervisorRequest.class);
        verify(supervisor).execute(captor.capture());
        assertFalse(captor.getValue().input().containsKey("controlTiming"));
        assertFalse(captor.getValue().input().containsKey("control.sessionLookupMs"));
    }

    @Test
    void trustedControlTimingParameterMergesAllowlistedFieldsOnly() {
        RuntimeAgentExecutionContextResolver resolver = mock(RuntimeAgentExecutionContextResolver.class);
        SupervisorRuntimeAdapter supervisor = mock(SupervisorRuntimeAdapter.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                resolver, supervisor, mock(RuntimeSupervisorApprovalPort.class),
                mock(RuntimeInteractionResumeService.class),
                mock(RuntimeSessionClearPort.class), mock(RuntimeRunLifecycleService.class));
        RuntimeAgentExecutionView agent = agent("agent-1", "orders-bot", "orders");
        RuntimeAgentConfigSnapshot config = activeConfig();
        when(resolver.resolve("orders-bot")).thenReturn(Optional.of(context(agent, config, List.of())));
        when(supervisor.execute(any())).thenReturn(new SupervisorRuntimeAdapter.SupervisorResult(
                true, "SUPERVISOR_COMPLETED", "你好", "trace-trusted-timing",
                List.of(), Map.of("decisionMode", "DIRECT", "modelRoundCount", 1), null));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("agentId", "orders-bot");
        request.put("message", "你好");
        // Body forgery must still be ignored when trusted timing is supplied separately.
        request.put("controlTiming", Map.of("control.sessionLookupMs", 9999L));

        TrustedControlTiming timing = new TrustedControlTiming(12L, 3L, 18L);
        Map<String, Object> response = service.execute(
                request, false,
                RuntimeAgentExecutionEventSink.NOOP,
                RuntimeAgentExecutionCancellation.NOOP,
                WorkflowExecutionIdentity.fromEmbedSession(null, null, "user-1"),
                timing);
        Map<?, ?> metadata = (Map<?, ?>) response.get("metadata");
        assertEquals(12L, ((Number) metadata.get("control.sessionLookupMs")).longValue());
        assertEquals(3L, ((Number) metadata.get("control.userMessageAuditMs")).longValue());
        assertEquals(18L, ((Number) metadata.get("control.preRuntimeMs")).longValue());
        assertNull(metadata.get("control.assistantAuditMs"));
    }

    private RuntimeAgentConfigSnapshot activeConfig() {
        RuntimeAgentConfigSnapshot config = RuntimeAgentConfigSnapshot.builder()
                .id(7L)
                .agentId("agent-1")
                .versionNo(2)
                .status("ACTIVE")
                .runtimeType("AGENTSCOPE")
                .build();
        return config;
    }

    private RuntimeAgentExecutionContext context(RuntimeAgentExecutionView agent,
                                                 RuntimeAgentConfigSnapshot config,
                                                 List<RuntimeAgentWorkflowToolSnapshot> tools) {
        return new RuntimeAgentExecutionContext(agent, config, tools, List.of(),
                new RuntimeAgentExecutionContext.ResolveTimings(1L, 2L, 3L, 4L, 10L));
    }

    private RuntimeAgentExecutionView agent(String id, String keySlug, String projectCode) {
        return new RuntimeAgentExecutionView(
                id, 7L, projectCode, keySlug, "Orders Bot", null, "PROJECT",
                null, true, 7L, null, null);
    }
}
