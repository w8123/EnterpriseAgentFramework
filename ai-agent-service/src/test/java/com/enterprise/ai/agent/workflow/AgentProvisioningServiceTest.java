package com.enterprise.ai.agent.workflow;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentProvisioningServiceTest {

    @Test
    void ensurePageCopilotCreatesAgentDefaultWorkflowAndBinding() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentEntryService agentEntryService = mock(AgentEntryService.class);
        WorkflowDefinitionService workflowDefinitionService = mock(WorkflowDefinitionService.class);
        AgentWorkflowBindingService bindingService = mock(AgentWorkflowBindingService.class);
        AgentProvisioningService service = new AgentProvisioningService(
                agentEntryService,
                workflowDefinitionService,
                bindingService,
                objectMapper);
        ScanProjectEntity project = project();
        when(agentEntryService.findByKeySlug("order-service-page-copilot")).thenReturn(Optional.empty());
        when(agentEntryService.create(any(AgentEntryEntity.class))).thenAnswer(invocation -> {
            AgentEntryEntity agent = invocation.getArgument(0);
            agent.setId("agent-1");
            return agent;
        });
        when(workflowDefinitionService.findByKeySlug("order-service-page-copilot-default"))
                .thenReturn(Optional.empty());
        when(workflowDefinitionService.create(any(WorkflowDefinitionEntity.class))).thenAnswer(invocation -> {
            WorkflowDefinitionEntity workflow = invocation.getArgument(0);
            workflow.setId("workflow-1");
            return workflow;
        });
        when(bindingService.list("agent-1")).thenReturn(List.of());
        when(bindingService.create(eq("agent-1"), any(AgentWorkflowBindingEntity.class))).thenAnswer(invocation -> {
            AgentWorkflowBindingEntity binding = invocation.getArgument(1);
            binding.setId(9L);
            return binding;
        });

        AgentProvisioningService.AgentProvisioningResult result =
                service.provisionPageCopilot(project, "sdk-onboarding", true);

        assertEquals("agent-1", result.agent().getId());
        assertEquals("order-service-page-copilot", result.agent().getKeySlug());
        assertEquals("workflow-1", result.defaultWorkflow().getId());
        assertEquals(9L, result.defaultBinding().getId());
        assertTrue(result.createdAgent());
        assertTrue(result.createdDefaultWorkflow());
        assertTrue(result.createdDefaultBinding());

        ArgumentCaptor<AgentEntryEntity> agentCaptor = ArgumentCaptor.forClass(AgentEntryEntity.class);
        verify(agentEntryService).create(agentCaptor.capture());
        assertEquals("PAGE_COPILOT", agentCaptor.getValue().getAgentKind());
        assertEquals("Order Service Page Copilot", agentCaptor.getValue().getName());
        assertTrue(agentCaptor.getValue().getEntryConfigJson().contains("sdk-onboarding"));

        ArgumentCaptor<WorkflowDefinitionEntity> workflowCaptor = ArgumentCaptor.forClass(WorkflowDefinitionEntity.class);
        verify(workflowDefinitionService).create(workflowCaptor.capture());
        assertEquals("PAGE_COPILOT_DEFAULT", workflowCaptor.getValue().getWorkflowType());
        assertEquals("AGENT_PROVISIONING", workflowCaptor.getValue().getManagedBy());
        assertTrue(workflowCaptor.getValue().getGraphSpecJson().contains("LLM"));
        GraphSpec graphSpec = objectMapper.readValue(workflowCaptor.getValue().getGraphSpecJson(), GraphSpec.class);
        assertEquals("page_copilot_answer", graphSpec.getEntry());
        Map<String, Object> llmConfig = graphSpec.getNodes().get(0).getConfig();
        assertTrue(String.valueOf(llmConfig.get("systemPrompt")).contains("业务系统页面助手"));
        assertTrue(String.valueOf(llmConfig.get("userPrompt")).contains("用户消息"));

        ArgumentCaptor<AgentWorkflowBindingEntity> bindingCaptor = ArgumentCaptor.forClass(AgentWorkflowBindingEntity.class);
        verify(bindingService).create(eq("agent-1"), bindingCaptor.capture());
        assertEquals("DEFAULT", bindingCaptor.getValue().getBindingType());
        assertEquals("workflow-1", bindingCaptor.getValue().getWorkflowId());
        assertTrue(bindingCaptor.getValue().getMetadataJson().contains("agent-provisioning"));
    }

    @Test
    void ensurePageCopilotReusesExistingAgentWorkflowAndBinding() {
        AgentEntryService agentEntryService = mock(AgentEntryService.class);
        WorkflowDefinitionService workflowDefinitionService = mock(WorkflowDefinitionService.class);
        AgentWorkflowBindingService bindingService = mock(AgentWorkflowBindingService.class);
        AgentProvisioningService service = new AgentProvisioningService(
                agentEntryService,
                workflowDefinitionService,
                bindingService,
                new ObjectMapper());
        ScanProjectEntity project = project();
        AgentEntryEntity agent = agent("agent-1", "order-service-page-copilot");
        WorkflowDefinitionEntity workflow = workflow("workflow-1", "order-service-page-copilot-default");
        AgentWorkflowBindingEntity binding = new AgentWorkflowBindingEntity();
        binding.setId(9L);
        binding.setAgentId("agent-1");
        binding.setWorkflowId("workflow-1");
        binding.setBindingType("DEFAULT");
        binding.setEnabled(true);
        when(agentEntryService.findByKeySlug("order-service-page-copilot")).thenReturn(Optional.of(agent));
        when(workflowDefinitionService.findByKeySlug("order-service-page-copilot-default"))
                .thenReturn(Optional.of(workflow));
        when(bindingService.list("agent-1")).thenReturn(List.of(binding));

        AgentProvisioningService.AgentProvisioningResult result =
                service.provisionPageCopilot(project, "sdk-onboarding", true);

        assertEquals("agent-1", result.agent().getId());
        assertEquals("workflow-1", result.defaultWorkflow().getId());
        assertEquals(9L, result.defaultBinding().getId());
        verify(agentEntryService, never()).create(any());
        verify(workflowDefinitionService, never()).create(any());
        verify(bindingService, never()).create(eq("agent-1"), any());
    }

    private ScanProjectEntity project() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("order-service");
        project.setName("Order Service");
        project.setVisibility("PROJECT");
        return project;
    }

    private AgentEntryEntity agent(String id, String keySlug) {
        AgentEntryEntity entity = new AgentEntryEntity();
        entity.setId(id);
        entity.setProjectId(7L);
        entity.setProjectCode("order-service");
        entity.setKeySlug(keySlug);
        entity.setName("Order Service Page Copilot");
        entity.setAgentKind("PAGE_COPILOT");
        entity.setEnabled(true);
        return entity;
    }

    private WorkflowDefinitionEntity workflow(String id, String keySlug) {
        WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity();
        entity.setId(id);
        entity.setProjectId(7L);
        entity.setProjectCode("order-service");
        entity.setKeySlug(keySlug);
        entity.setName("Order Service Page Copilot Default");
        entity.setWorkflowType("PAGE_COPILOT_DEFAULT");
        entity.setStatus("DRAFT");
        return entity;
    }
}
