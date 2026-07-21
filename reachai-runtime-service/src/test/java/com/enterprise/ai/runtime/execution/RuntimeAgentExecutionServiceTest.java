package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContext;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContextResolver;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionView;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.chat.RuntimeChatMemoryStore;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.supervisor.SupervisorApprovalInteractionService;
import com.enterprise.ai.runtime.supervisor.SupervisorRuntimeAdapter;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeAgentExecutionServiceTest {

    @Test
    void resolvesPublishedAgentConfigAndDelegatesToSupervisorWithoutStaticBinding() {
        RuntimeAgentExecutionContextResolver resolver = mock(RuntimeAgentExecutionContextResolver.class);
        SupervisorRuntimeAdapter supervisor = mock(SupervisorRuntimeAdapter.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                resolver, supervisor, mock(SupervisorApprovalInteractionService.class),
                mock(RuntimeInteractionResumeService.class),
                mock(RuntimeChatMemoryStore.class), mock(RuntimeRunLifecycleService.class));
        RuntimeAgentExecutionView agent = agent("agent-1", "orders-bot", "orders");
        RuntimeAgentConfigVersionEntity config = activeConfig();
        RuntimeAgentWorkflowToolEntity tool = new RuntimeAgentWorkflowToolEntity();
        tool.setToolName("query_orders");
        tool.setWorkflowId("wf-orders");
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
    void returnsRuntimeOwnedErrorWhenAgentCannotBeResolved() {
        RuntimeAgentExecutionContextResolver resolver = mock(RuntimeAgentExecutionContextResolver.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                resolver, mock(SupervisorRuntimeAdapter.class),
                mock(SupervisorApprovalInteractionService.class), mock(RuntimeInteractionResumeService.class),
                mock(RuntimeChatMemoryStore.class), mock(RuntimeRunLifecycleService.class));
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
                resolver, supervisor, mock(SupervisorApprovalInteractionService.class),
                mock(RuntimeInteractionResumeService.class),
                mock(RuntimeChatMemoryStore.class), mock(RuntimeRunLifecycleService.class));
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
        SupervisorApprovalInteractionService approvals = mock(SupervisorApprovalInteractionService.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                resolver, supervisor, approvals, mock(RuntimeInteractionResumeService.class),
                mock(RuntimeChatMemoryStore.class), mock(RuntimeRunLifecycleService.class));
        RuntimeAgentExecutionView agent = agent("agent-1", "orders-bot", "orders");
        RuntimeAgentConfigVersionEntity config = activeConfig();
        SupervisorRuntimeAdapter.PolicyApprovalGrant grant = new SupervisorRuntimeAdapter.PolicyApprovalGrant(
                "spv_1", "orders:write", "update_order", Map.of("orderId", "O-1"), "u-1");
        when(approvals.prepareResume(any(), any()))
                .thenReturn(new SupervisorApprovalInteractionService.ResumeDecision(
                        true, false, "agent-1",
                        Map.of("message", "更新订单", "sessionId", "s1", "agentId", "agent-1",
                                "traceId", "trace-original"),
                        grant, "审批已通过"));
        when(resolver.resolve("agent-1")).thenReturn(Optional.of(context(agent, config, List.of())));
        when(supervisor.execute(any())).thenReturn(new SupervisorRuntimeAdapter.SupervisorResult(
                true, "SUPERVISOR_COMPLETED", "订单已更新", "trace-original", List.of(), Map.of(), null));

        Map<String, Object> response = service.execute(Map.of(
                "interactionId", "spv_1",
                "traceId", "trace-submit-must-not-win",
                "sessionId", "s1",
                "uiSubmit", Map.of("action", "confirm")), true);

        assertEquals(true, response.get("success"));
        assertEquals("订单已更新", response.get("answer"));
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
    void returnsSafeResultWhenUserRejectsSupervisorApproval() {
        SupervisorApprovalInteractionService approvals = mock(SupervisorApprovalInteractionService.class);
        RuntimeRunLifecycleService runLifecycle = mock(RuntimeRunLifecycleService.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                mock(RuntimeAgentExecutionContextResolver.class), mock(SupervisorRuntimeAdapter.class), approvals,
                mock(RuntimeInteractionResumeService.class),
                mock(RuntimeChatMemoryStore.class), runLifecycle);
        when(approvals.prepareResume(any(), any())).thenReturn(
                new SupervisorApprovalInteractionService.ResumeDecision(
                        false, true, "agent-1", Map.of("traceId", "trace-original"), null,
                        "用户已拒绝执行该 Workflow Tool"));

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
                mock(SupervisorApprovalInteractionService.class), resumeService,
                mock(RuntimeChatMemoryStore.class), runLifecycle);
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
                resolver, supervisor, mock(SupervisorApprovalInteractionService.class),
                resumeService, mock(RuntimeChatMemoryStore.class), mock(RuntimeRunLifecycleService.class));
        RuntimeAgentExecutionView agent = agent("agent-1", "orders-bot", "orders");
        RuntimeAgentConfigVersionEntity config = activeConfig();
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
        verify(supervisor).continueAfterWorkflowInteraction(any(), any(), any());
        verify(supervisor, never()).execute(any());
    }

    @Test
    void executesSpecifiedArchivedConfigWithoutResolvingCurrentActiveConfig() {
        RuntimeAgentExecutionContextResolver resolver = mock(RuntimeAgentExecutionContextResolver.class);
        SupervisorRuntimeAdapter supervisor = mock(SupervisorRuntimeAdapter.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                resolver, supervisor, mock(SupervisorApprovalInteractionService.class),
                mock(RuntimeInteractionResumeService.class),
                mock(RuntimeChatMemoryStore.class), mock(RuntimeRunLifecycleService.class));
        RuntimeAgentExecutionView agent = agent("agent-1", "orders-bot", "orders");
        RuntimeAgentConfigVersionEntity archived = new RuntimeAgentConfigVersionEntity();
        archived.setId(91L);
        archived.setAgentId("agent-1");
        archived.setVersionNo(4);
        archived.setStatus("ARCHIVED");
        archived.setRuntimeType("AGENTSCOPE");
        RuntimeAgentWorkflowToolEntity pinnedTool = new RuntimeAgentWorkflowToolEntity();
        pinnedTool.setAgentId("agent-1");
        pinnedTool.setAgentConfigVersionId(91L);
        pinnedTool.setWorkflowId("wf-orders");
        pinnedTool.setWorkflowVersionId(42L);
        pinnedTool.setToolName("query_orders_v4");
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
                resolver, supervisor, mock(SupervisorApprovalInteractionService.class),
                mock(RuntimeInteractionResumeService.class),
                mock(RuntimeChatMemoryStore.class), mock(RuntimeRunLifecycleService.class));
        RuntimeAgentExecutionView agent = agent("agent-1", "orders-bot", "orders");
        RuntimeAgentConfigVersionEntity config = activeConfig();
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
                resolver, supervisor, mock(SupervisorApprovalInteractionService.class),
                mock(RuntimeInteractionResumeService.class),
                mock(RuntimeChatMemoryStore.class), mock(RuntimeRunLifecycleService.class));
        RuntimeAgentExecutionView agent = agent("agent-1", "orders-bot", "orders");
        RuntimeAgentConfigVersionEntity config = activeConfig();
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
                SupervisorRuntimeAdapter.SupervisorEventSink.NOOP,
                RuntimeAgentExecutionCancellation.NOOP,
                WorkflowExecutionIdentity.fromEmbedSession(null, null, "user-1"),
                timing);
        Map<?, ?> metadata = (Map<?, ?>) response.get("metadata");
        assertEquals(12L, ((Number) metadata.get("control.sessionLookupMs")).longValue());
        assertEquals(3L, ((Number) metadata.get("control.userMessageAuditMs")).longValue());
        assertEquals(18L, ((Number) metadata.get("control.preRuntimeMs")).longValue());
        assertNull(metadata.get("control.assistantAuditMs"));
    }

    private RuntimeAgentConfigVersionEntity activeConfig() {
        RuntimeAgentConfigVersionEntity config = new RuntimeAgentConfigVersionEntity();
        config.setId(7L);
        config.setAgentId("agent-1");
        config.setVersionNo(2);
        config.setStatus("ACTIVE");
        config.setRuntimeType("AGENTSCOPE");
        return config;
    }

    private RuntimeAgentExecutionContext context(RuntimeAgentExecutionView agent,
                                                 RuntimeAgentConfigVersionEntity config,
                                                 List<RuntimeAgentWorkflowToolEntity> tools) {
        return new RuntimeAgentExecutionContext(agent, config, tools, List.of(),
                new RuntimeAgentExecutionContext.ResolveTimings(1L, 2L, 3L, 4L, 10L));
    }

    private RuntimeAgentExecutionView agent(String id, String keySlug, String projectCode) {
        return new RuntimeAgentExecutionView(
                id, 7L, projectCode, keySlug, "Orders Bot", null, "PROJECT",
                null, true, 7L, null, null);
    }
}
