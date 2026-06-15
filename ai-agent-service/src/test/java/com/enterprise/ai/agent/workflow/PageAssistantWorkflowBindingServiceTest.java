package com.enterprise.ai.agent.workflow;

import com.enterprise.ai.agent.scan.ScanProjectEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PageAssistantWorkflowBindingServiceTest {

    @Test
    void ensurePageWorkflowBindingCreatesGlobalAgentWorkflowAndPageBinding() {
        AgentEntryService agentEntryService = mock(AgentEntryService.class);
        WorkflowDefinitionService workflowDefinitionService = mock(WorkflowDefinitionService.class);
        AgentWorkflowBindingService bindingService = mock(AgentWorkflowBindingService.class);
        PageAssistantWorkflowBindingService service = new PageAssistantWorkflowBindingService(
                agentEntryService,
                workflowDefinitionService,
                bindingService,
                new ObjectMapper());
        ScanProjectEntity project = project();
        when(agentEntryService.findByKeySlug("order-service-global-ai-assistant")).thenReturn(Optional.empty());
        when(agentEntryService.create(any(AgentEntryEntity.class))).thenAnswer(invocation -> {
            AgentEntryEntity agent = invocation.getArgument(0);
            agent.setId("agent-1");
            return agent;
        });
        when(workflowDefinitionService.findByKeySlug("order-service-orders_list-page-assistant"))
                .thenReturn(Optional.empty());
        when(workflowDefinitionService.create(any(WorkflowDefinitionEntity.class))).thenAnswer(invocation -> {
            WorkflowDefinitionEntity workflow = invocation.getArgument(0);
            workflow.setId("workflow-1");
            return workflow;
        });
        when(bindingService.list("agent-1")).thenReturn(List.of());
        when(bindingService.create(eq("agent-1"), any(AgentWorkflowBindingEntity.class))).thenAnswer(invocation -> {
            AgentWorkflowBindingEntity binding = invocation.getArgument(1);
            binding.setId(42L);
            return binding;
        });

        PageAssistantWorkflowBindingResult result = service.ensurePageWorkflowBinding(
                project,
                "orders.list",
                "/orders/*",
                List.of("orders.refresh", "orders.export"));

        assertEquals("agent-1", result.agentId());
        assertEquals("order-service-global-ai-assistant", result.agentKeySlug());
        assertEquals("workflow-1", result.workflowId());
        assertEquals("order-service-orders_list-page-assistant", result.workflowKeySlug());
        assertEquals(42L, result.bindingId());

        ArgumentCaptor<AgentEntryEntity> agentCaptor = ArgumentCaptor.forClass(AgentEntryEntity.class);
        verify(agentEntryService).create(agentCaptor.capture());
        assertEquals("GLOBAL_EMBED", agentCaptor.getValue().getAgentKind());
        assertEquals("order-service", agentCaptor.getValue().getProjectCode());

        ArgumentCaptor<WorkflowDefinitionEntity> workflowCaptor = ArgumentCaptor.forClass(WorkflowDefinitionEntity.class);
        verify(workflowDefinitionService).create(workflowCaptor.capture());
        WorkflowDefinitionEntity workflow = workflowCaptor.getValue();
        assertEquals("PAGE_ASSISTANT", workflow.getWorkflowType());
        assertEquals("PAGE_ASSISTANT", workflow.getManagedBy());
        assertTrue(workflow.getGraphSpecJson().contains("PAGE_ACTION"));
        assertTrue(workflow.getGraphSpecJson().contains("orders.refresh"));

        ArgumentCaptor<AgentWorkflowBindingEntity> bindingCaptor = ArgumentCaptor.forClass(AgentWorkflowBindingEntity.class);
        verify(bindingService).create(eq("agent-1"), bindingCaptor.capture());
        AgentWorkflowBindingEntity binding = bindingCaptor.getValue();
        assertEquals("PAGE", binding.getBindingType());
        assertEquals("workflow-1", binding.getWorkflowId());
        assertEquals("orders.list", binding.getPageKey());
        assertEquals("/orders/*", binding.getRoutePattern());
        assertNotNull(binding.getMetadataJson());
    }

    private ScanProjectEntity project() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("order-service");
        project.setName("Order Service");
        project.setVisibility("PROJECT");
        return project;
    }
}
