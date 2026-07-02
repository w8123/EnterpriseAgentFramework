package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.compat.RuntimePageAssistantWorkflowBindRequest;
import com.enterprise.ai.runtime.compat.RuntimePageAssistantWorkflowBinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimePageAssistantWorkflowBindingServiceTest {

    @Test
    void bindsExistingPageWorkflowToExplicitAgentAndNormalizesMetadata() throws Exception {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeAgentEntryMapper agentMapper = mock(RuntimeAgentEntryMapper.class);
        RuntimeAgentWorkflowBindingMapper bindingMapper = mock(RuntimeAgentWorkflowBindingMapper.class);
        RuntimePageAssistantWorkflowBindingService service = new RuntimePageAssistantWorkflowBindingService(
                capabilityClient, workflowService, agentMapper, bindingMapper, new ObjectMapper());
        when(capabilityClient.getProject("orders")).thenReturn(project("orders", 7L));
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(pageWorkflow()));
        when(agentMapper.selectById("agent-1")).thenReturn(agent("agent-1", "orders-page-copilot"));
        when(bindingMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            RuntimeAgentWorkflowBindingEntity entity = invocation.getArgument(0);
            entity.setId(11L);
            return 1;
        }).when(bindingMapper).insert(any(RuntimeAgentWorkflowBindingEntity.class));

        RuntimePageAssistantWorkflowBinding result = service.bindExistingPageWorkflow("wf-1",
                new RuntimePageAssistantWorkflowBindRequest(
                        null,
                        " orders ",
                        " agent-1 ",
                        " orders.list ",
                        " /orders ",
                        List.of("open", " ", "open", "refresh")));

        assertEquals("agent-1", result.agentId());
        assertEquals("orders-page-copilot", result.agentKeySlug());
        assertEquals("wf-1", result.workflowId());
        assertEquals("orders-page-assistant", result.workflowKeySlug());
        assertEquals(11L, result.bindingId());
        ArgumentCaptor<RuntimeAgentWorkflowBindingEntity> binding =
                ArgumentCaptor.forClass(RuntimeAgentWorkflowBindingEntity.class);
        verify(bindingMapper).insert(binding.capture());
        RuntimeAgentWorkflowBindingEntity saved = binding.getValue();
        assertEquals("agent-1", saved.getAgentId());
        assertEquals("wf-1", saved.getWorkflowId());
        assertEquals("orders", saved.getProjectCode());
        assertEquals("PAGE", saved.getBindingType());
        assertEquals("orders.list", saved.getPageKey());
        assertEquals("/orders", saved.getRoutePattern());
        assertEquals(100, saved.getPriority());
        assertEquals(true, saved.getEnabled());
        Map<?, ?> metadata = new ObjectMapper().readValue(saved.getMetadataJson(), Map.class);
        assertEquals("page-assistant-wizard", metadata.get("source"));
        assertEquals("orders-page-assistant", metadata.get("workflowKeySlug"));
        assertEquals(List.of("open", "refresh"), metadata.get("actionKeys"));
    }

    @Test
    void replacesExistingPageBindingForTheSameAgentAndPage() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeAgentEntryMapper agentMapper = mock(RuntimeAgentEntryMapper.class);
        RuntimeAgentWorkflowBindingMapper bindingMapper = mock(RuntimeAgentWorkflowBindingMapper.class);
        RuntimePageAssistantWorkflowBindingService service = new RuntimePageAssistantWorkflowBindingService(
                capabilityClient, workflowService, agentMapper, bindingMapper, new ObjectMapper());
        when(capabilityClient.getProjectById(7L)).thenReturn(project("orders", 7L));
        when(workflowService.findById("wf-2")).thenReturn(Optional.of(pageWorkflow("wf-2", "orders-page-assistant-v2")));
        when(agentMapper.selectById("agent-1")).thenReturn(agent("agent-1", "orders-page-copilot"));
        RuntimeAgentWorkflowBindingEntity existing = new RuntimeAgentWorkflowBindingEntity();
        existing.setId(9L);
        existing.setAgentId("agent-1");
        existing.setWorkflowId("wf-old");
        existing.setBindingType("PAGE");
        existing.setPageKey("orders.list");
        when(bindingMapper.selectList(any())).thenReturn(List.of(existing));
        when(bindingMapper.selectById(9L)).thenReturn(existing);

        RuntimePageAssistantWorkflowBinding result = service.bindExistingPageWorkflow("wf-2",
                new RuntimePageAssistantWorkflowBindRequest(
                        7L,
                        null,
                        "agent-1",
                        "orders.list",
                        null,
                        List.of()));

        assertEquals(9L, result.bindingId());
        assertEquals("wf-2", existing.getWorkflowId());
        assertEquals("orders-page-assistant-v2", result.workflowKeySlug());
        verify(bindingMapper).updateById(existing);
    }

    @Test
    void provisionsProjectPageCopilotWhenAgentIdIsMissing() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeAgentEntryMapper agentMapper = mock(RuntimeAgentEntryMapper.class);
        RuntimeAgentWorkflowBindingMapper bindingMapper = mock(RuntimeAgentWorkflowBindingMapper.class);
        RuntimePageAssistantWorkflowBindingService service = new RuntimePageAssistantWorkflowBindingService(
                capabilityClient, workflowService, agentMapper, bindingMapper, new ObjectMapper());
        when(capabilityClient.getProject("orders")).thenReturn(project("orders", 7L));
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(pageWorkflow()));
        when(agentMapper.selectOne(any())).thenReturn(null);
        when(bindingMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            RuntimeAgentEntryEntity entity = invocation.getArgument(0);
            entity.setId("generated-agent");
            return 1;
        }).when(agentMapper).insert(any(RuntimeAgentEntryEntity.class));
        doAnswer(invocation -> {
            RuntimeAgentWorkflowBindingEntity entity = invocation.getArgument(0);
            entity.setId(12L);
            return 1;
        }).when(bindingMapper).insert(any(RuntimeAgentWorkflowBindingEntity.class));

        RuntimePageAssistantWorkflowBinding result = service.bindExistingPageWorkflow("wf-1",
                new RuntimePageAssistantWorkflowBindRequest(
                        null,
                        "orders",
                        null,
                        "orders.list",
                        null,
                        List.of()));

        assertEquals("generated-agent", result.agentId());
        assertEquals("orders-page-copilot", result.agentKeySlug());
        ArgumentCaptor<RuntimeAgentEntryEntity> agent = ArgumentCaptor.forClass(RuntimeAgentEntryEntity.class);
        verify(agentMapper).insert(agent.capture());
        assertEquals(7L, agent.getValue().getProjectId());
        assertEquals("orders", agent.getValue().getProjectCode());
        assertEquals("orders-page-copilot", agent.getValue().getKeySlug());
        assertEquals("PAGE_COPILOT", agent.getValue().getAgentKind());
    }

    @Test
    void rejectsNonPageAssistantWorkflow() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimePageAssistantWorkflowBindingService service = new RuntimePageAssistantWorkflowBindingService(
                capabilityClient,
                workflowService,
                mock(RuntimeAgentEntryMapper.class),
                mock(RuntimeAgentWorkflowBindingMapper.class),
                new ObjectMapper());
        RuntimeWorkflowDefinitionEntity workflow = pageWorkflow();
        workflow.setWorkflowType("CHAT");
        when(capabilityClient.getProject("orders")).thenReturn(project("orders", 7L));
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));

        assertThrows(IllegalArgumentException.class, () -> service.bindExistingPageWorkflow("wf-1",
                new RuntimePageAssistantWorkflowBindRequest(
                        null,
                        "orders",
                        "agent-1",
                        "orders.list",
                        null,
                        List.of())));
    }

    private Map<String, Object> project(String projectCode, Long projectId) {
        return Map.of(
                "projectId", projectId,
                "projectCode", projectCode,
                "name", "Orders",
                "visibility", "PROJECT");
    }

    private RuntimeWorkflowDefinitionEntity pageWorkflow() {
        return pageWorkflow("wf-1", "orders-page-assistant");
    }

    private RuntimeWorkflowDefinitionEntity pageWorkflow(String id, String keySlug) {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId(id);
        workflow.setProjectId(7L);
        workflow.setProjectCode("orders");
        workflow.setKeySlug(keySlug);
        workflow.setWorkflowType("PAGE_ASSISTANT");
        return workflow;
    }

    private RuntimeAgentEntryEntity agent(String id, String keySlug) {
        RuntimeAgentEntryEntity agent = new RuntimeAgentEntryEntity();
        agent.setId(id);
        agent.setProjectId(7L);
        agent.setProjectCode("orders");
        agent.setKeySlug(keySlug);
        return agent;
    }
}
