package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.agent.RuntimeAgentEntryService;
import com.enterprise.ai.runtime.agent.RuntimeAgentEntryView;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.workflow.RuntimeAgentWorkflowBindingResolveRequest;
import com.enterprise.ai.runtime.workflow.RuntimeAgentWorkflowBindingService;
import com.enterprise.ai.runtime.workflow.RuntimeAgentWorkflowBindingView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeAgentExecutionServiceTest {

    @Test
    void resolvesAgentBindingWorkflowAndExecutesAnswerGraph() {
        RuntimeAgentEntryService agentService = mock(RuntimeAgentEntryService.class);
        RuntimeAgentWorkflowBindingService bindingService = mock(RuntimeAgentWorkflowBindingService.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeAgentExecutionService service =
                new RuntimeAgentExecutionService(agentService, bindingService, workflowService,
                        new RuntimeGraphSpecExecutor(new ObjectMapper(),
                                mock(RuntimeModelServiceClient.class),
                                mock(RuntimeCapabilityCatalogClient.class)));
        RuntimeAgentEntryView agent = agent("agent-1", "orders-bot", "orders");
        RuntimeAgentWorkflowBindingView binding = binding("agent-1", "wf-orders");
        RuntimeWorkflowDefinitionEntity workflow = workflow("wf-orders", "orders-flow");
        Map<String, Object> request = Map.of(
                "agentDefinitionId", "orders-bot",
                "message", "hello",
                "sessionId", "s1",
                "userId", "u1",
                "intentHint", "ORDER_QA");
        when(agentService.findByIdOrKeySlug("orders-bot")).thenReturn(Optional.of(agent));
        when(bindingService.resolvePreview(new RuntimeAgentWorkflowBindingResolveRequest(
                "agent-1", "orders", null, null, null, "ORDER_QA"))).thenReturn(Optional.of(binding));
        when(workflowService.findById("wf-orders")).thenReturn(Optional.of(workflow));

        Map<String, Object> response = service.execute(request, true);

        assertEquals(true, response.get("success"));
        assertEquals("订单助手收到：hello", response.get("answer"));
        assertEquals("s1", response.get("sessionId"));
        assertEquals(List.of(
                Map.of("name", "resolve-agent", "detail", "agent-1"),
                Map.of("name", "resolve-binding", "detail", "wf-orders"),
                Map.of("name", "resolve-workflow", "detail", "orders-flow"),
                Map.of("name", "execute-node", "detail", "answer")
        ), response.get("steps"));
        Map<?, ?> metadata = (Map<?, ?>) response.get("metadata");
        assertEquals("RUNTIME_GRAPH_EXECUTED", metadata.get("code"));
        assertEquals("agent-1", metadata.get("agentId"));
        assertEquals("orders-bot", metadata.get("agentKey"));
        assertEquals("wf-orders", metadata.get("workflowId"));
        assertEquals("orders-flow", metadata.get("workflowKey"));
        verify(agentService).findByIdOrKeySlug("orders-bot");
        verify(bindingService).resolvePreview(new RuntimeAgentWorkflowBindingResolveRequest(
                "agent-1", "orders", null, null, null, "ORDER_QA"));
        verify(workflowService).findById("wf-orders");
    }

    @Test
    void returnsRuntimeOwnedErrorWhenAgentCannotBeResolved() {
        RuntimeAgentEntryService agentService = mock(RuntimeAgentEntryService.class);
        RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(
                agentService,
                mock(RuntimeAgentWorkflowBindingService.class),
                mock(RuntimeWorkflowDefinitionService.class),
                new RuntimeGraphSpecExecutor(new ObjectMapper(),
                        mock(RuntimeModelServiceClient.class),
                        mock(RuntimeCapabilityCatalogClient.class)));
        when(agentService.findByIdOrKeySlug("missing")).thenReturn(Optional.empty());

        Map<String, Object> response = service.execute(Map.of("agentDefinitionId", "missing"), false);

        assertEquals(false, response.get("success"));
        assertEquals("Agent not found: missing", response.get("answer"));
        assertFalse(response.containsKey("steps"));
        assertEquals("RUNTIME_AGENT_NOT_FOUND", ((Map<?, ?>) response.get("metadata")).get("code"));
    }

    private RuntimeAgentEntryView agent(String id, String keySlug, String projectCode) {
        return new RuntimeAgentEntryView(
                id,
                7L,
                projectCode,
                keySlug,
                "Orders Bot",
                null,
                "PROJECT_ENTRY",
                "PROJECT",
                null,
                "model-1",
                null,
                null,
                true,
                null,
                null);
    }

    private RuntimeAgentWorkflowBindingView binding(String agentId, String workflowId) {
        return new RuntimeAgentWorkflowBindingView(
                9L,
                agentId,
                workflowId,
                "orders",
                "DEFAULT",
                null,
                null,
                null,
                null,
                0,
                true,
                null,
                null,
                null,
                null);
    }

    private RuntimeWorkflowDefinitionEntity workflow(String id, String keySlug) {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId(id);
        workflow.setKeySlug(keySlug);
        workflow.setName("Orders Flow");
        workflow.setStatus("PUBLISHED");
        workflow.setGraphSpecJson("""
                {"entry":"answer","nodes":[{"id":"answer","type":"ANSWER","config":{"template":"订单助手收到：{{ input }}"}}]}
                """);
        return workflow;
    }
}
