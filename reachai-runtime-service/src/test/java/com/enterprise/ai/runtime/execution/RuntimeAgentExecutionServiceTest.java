package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigService;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentService;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.chat.RuntimeChatMemoryStore;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.supervisor.SupervisorApprovalInteractionService;
import com.enterprise.ai.runtime.supervisor.SupervisorRuntimeAdapter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeAgentExecutionServiceTest {

    @Test
    void resolvesPublishedAgentConfigAndDelegatesToSupervisorWithoutStaticBinding() {
        RuntimeAgentService agentService = mock(RuntimeAgentService.class);
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        SupervisorRuntimeAdapter supervisor = mock(SupervisorRuntimeAdapter.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                agentService, configService, supervisor, mock(SupervisorApprovalInteractionService.class),
                mock(RuntimeChatMemoryStore.class), mock(RuntimeRunLifecycleService.class));
        RuntimeAgentView agent = agent("agent-1", "orders-bot", "orders");
        RuntimeAgentConfigVersionEntity config = activeConfig();
        RuntimeAgentWorkflowToolEntity tool = new RuntimeAgentWorkflowToolEntity();
        tool.setToolName("query_orders");
        tool.setWorkflowId("wf-orders");
        Map<String, Object> request = Map.of(
                "agentId", "orders-bot",
                "message", "hello",
                "sessionId", "s1");
        when(agentService.findByIdOrKeySlug("orders-bot")).thenReturn(Optional.of(agent));
        when(configService.resolveActive("agent-1")).thenReturn(Optional.of(config));
        when(configService.resolveTools("agent-1", config)).thenReturn(List.of(tool));
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
        verify(configService).resolveTools("agent-1", config);
        verify(supervisor).execute(any());

    }

    @Test
    void returnsRuntimeOwnedErrorWhenAgentCannotBeResolved() {
        RuntimeAgentService agentService = mock(RuntimeAgentService.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                agentService, mock(RuntimeAgentConfigService.class), mock(SupervisorRuntimeAdapter.class),
                mock(SupervisorApprovalInteractionService.class), mock(RuntimeChatMemoryStore.class),
                mock(RuntimeRunLifecycleService.class));
        when(agentService.findByIdOrKeySlug("missing")).thenReturn(Optional.empty());

        Map<String, Object> response = service.execute(Map.of("agentId", "missing"), false);

        assertEquals(false, response.get("success"));
        assertEquals("Agent not found: missing", response.get("answer"));
        assertFalse(response.containsKey("steps"));
        assertEquals("RUNTIME_AGENT_NOT_FOUND", ((Map<?, ?>) response.get("metadata")).get("code"));
    }

    @Test
    void refusesDraftOnlyAgentInsteadOfFallingBackToBinding() {
        RuntimeAgentService agentService = mock(RuntimeAgentService.class);
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        SupervisorRuntimeAdapter supervisor = mock(SupervisorRuntimeAdapter.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                agentService, configService, supervisor, mock(SupervisorApprovalInteractionService.class),
                mock(RuntimeChatMemoryStore.class), mock(RuntimeRunLifecycleService.class));
        when(agentService.findByIdOrKeySlug("orders-bot"))
                .thenReturn(Optional.of(agent("agent-1", "orders-bot", "orders")));
        when(configService.resolveActive("agent-1")).thenReturn(Optional.empty());

        Map<String, Object> response = service.execute(Map.of("agentId", "orders-bot"), true);

        assertEquals(false, response.get("success"));
        assertEquals("RUNTIME_AGENT_CONFIG_NOT_PUBLISHED", ((Map<?, ?>) response.get("metadata")).get("code"));
        assertTrue(response.get("answer").toString().contains("no published Supervisor configuration"));
    }

    @Test
    void resumesSupervisorApprovalWithServerIssuedOneTimeGrant() {
        RuntimeAgentService agentService = mock(RuntimeAgentService.class);
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        SupervisorRuntimeAdapter supervisor = mock(SupervisorRuntimeAdapter.class);
        SupervisorApprovalInteractionService approvals = mock(SupervisorApprovalInteractionService.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                agentService, configService, supervisor, approvals, mock(RuntimeChatMemoryStore.class),
                mock(RuntimeRunLifecycleService.class));
        RuntimeAgentView agent = agent("agent-1", "orders-bot", "orders");
        RuntimeAgentConfigVersionEntity config = activeConfig();
        SupervisorRuntimeAdapter.PolicyApprovalGrant grant = new SupervisorRuntimeAdapter.PolicyApprovalGrant(
                "spv_1", "orders:write", "update_order", Map.of("orderId", "O-1"), "u-1");
        when(approvals.prepareResume(any(), any()))
                .thenReturn(new SupervisorApprovalInteractionService.ResumeDecision(
                        true, false, "agent-1",
                        Map.of("message", "更新订单", "sessionId", "s1", "agentId", "agent-1"),
                        grant, "审批已通过"));
        when(agentService.findByIdOrKeySlug("agent-1")).thenReturn(Optional.of(agent));
        when(configService.resolveActive("agent-1")).thenReturn(Optional.of(config));
        when(configService.resolveTools("agent-1", config)).thenReturn(List.of());
        when(supervisor.execute(any())).thenReturn(new SupervisorRuntimeAdapter.SupervisorResult(
                true, "SUPERVISOR_COMPLETED", "订单已更新", "trace-2", List.of(), Map.of(), null));

        Map<String, Object> response = service.execute(Map.of(
                "interactionId", "spv_1",
                "sessionId", "s1",
                "uiSubmit", Map.of("action", "confirm")), true);

        assertEquals(true, response.get("success"));
        assertEquals("订单已更新", response.get("answer"));
        ArgumentCaptor<SupervisorRuntimeAdapter.SupervisorRequest> captor =
                ArgumentCaptor.forClass(SupervisorRuntimeAdapter.SupervisorRequest.class);
        verify(supervisor).execute(captor.capture());
        assertEquals(grant, captor.getValue().approvalGrant());
        assertEquals("agent-1", captor.getValue().input().get("agentId"));
    }

    @Test
    void returnsSafeResultWhenUserRejectsSupervisorApproval() {
        SupervisorApprovalInteractionService approvals = mock(SupervisorApprovalInteractionService.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                mock(RuntimeAgentService.class), mock(RuntimeAgentConfigService.class),
                mock(SupervisorRuntimeAdapter.class), approvals, mock(RuntimeChatMemoryStore.class),
                mock(RuntimeRunLifecycleService.class));
        when(approvals.prepareResume(any(), any())).thenReturn(
                new SupervisorApprovalInteractionService.ResumeDecision(
                        false, true, "agent-1", Map.of(), null, "用户已拒绝执行该 Workflow Tool"));

        Map<String, Object> response = service.execute(Map.of(
                "interactionId", "spv_1",
                "uiSubmit", Map.of("action", "reject")), false);

        assertEquals(true, response.get("success"));
        assertEquals("SUPERVISOR_ACTION_REJECTED", ((Map<?, ?>) response.get("metadata")).get("code"));
    }

    @Test
    void executesSpecifiedArchivedConfigWithoutResolvingCurrentActiveConfig() {
        RuntimeAgentService agentService = mock(RuntimeAgentService.class);
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        SupervisorRuntimeAdapter supervisor = mock(SupervisorRuntimeAdapter.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                agentService, configService, supervisor, mock(SupervisorApprovalInteractionService.class),
                mock(RuntimeChatMemoryStore.class), mock(RuntimeRunLifecycleService.class));
        RuntimeAgentView agent = agent("agent-1", "orders-bot", "orders");
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
        when(agentService.findByIdOrKeySlug("agent-1")).thenReturn(Optional.of(agent));
        when(configService.find(91L)).thenReturn(Optional.of(archived));
        when(configService.resolveTools("agent-1", archived)).thenReturn(List.of(pinnedTool));
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
        verify(configService).find(91L);
        verify(configService).resolveTools("agent-1", archived);
        verify(configService, never()).resolveActive(any());
        ArgumentCaptor<SupervisorRuntimeAdapter.SupervisorRequest> captor =
                ArgumentCaptor.forClass(SupervisorRuntimeAdapter.SupervisorRequest.class);
        verify(supervisor).execute(captor.capture());
        assertEquals(archived, captor.getValue().config());
        assertEquals(List.of(pinnedTool), captor.getValue().workflowTools());
        assertEquals("REPLAY", captor.getValue().input().get("entryType"));
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

    private RuntimeAgentView agent(String id, String keySlug, String projectCode) {
        return new RuntimeAgentView(
                id, 7L, projectCode, keySlug, "Orders Bot", null, "PROJECT",
                null, true, 7L, 7L, 2, "ACTIVE", "AGENTSCOPE", 1, null, null);
    }
}
