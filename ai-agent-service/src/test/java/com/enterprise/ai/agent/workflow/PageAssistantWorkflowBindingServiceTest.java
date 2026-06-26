package com.enterprise.ai.agent.workflow;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class PageAssistantWorkflowBindingServiceTest {

    private ObjectMapper objectMapper;
    private AgentProvisioningService agentProvisioningService;
    private AgentEntryService agentEntryService;
    private WorkflowDefinitionService workflowDefinitionService;
    private AgentWorkflowBindingService bindingService;
    private PageAssistantWorkflowBindingService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        agentProvisioningService = mock(AgentProvisioningService.class);
        agentEntryService = mock(AgentEntryService.class);
        workflowDefinitionService = mock(WorkflowDefinitionService.class);
        bindingService = mock(AgentWorkflowBindingService.class);
        service = new PageAssistantWorkflowBindingService(
                agentProvisioningService,
                agentEntryService,
                workflowDefinitionService,
                bindingService,
                objectMapper);
    }

    @Test
    void ensurePageWorkflowBindingCreatesGlobalAgentWorkflowAndPageBinding() throws Exception {
        ScanProjectEntity project = project();
        AgentEntryEntity agent = new AgentEntryEntity();
        agent.setId("agent-1");
        agent.setProjectCode("order-service");
        agent.setKeySlug("order-service-page-copilot");
        agent.setAgentKind("PAGE_COPILOT");
        when(agentProvisioningService.provisionPageCopilot(project, "page-assistant", true))
                .thenReturn(new AgentProvisioningService.AgentProvisioningResult(
                        agent,
                        null,
                        null,
                        false,
                        false,
                        false));
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
        assertEquals("order-service-page-copilot", result.agentKeySlug());
        assertEquals("workflow-1", result.workflowId());
        assertEquals("order-service-orders_list-page-assistant", result.workflowKeySlug());
        assertEquals(42L, result.bindingId());

        verify(agentProvisioningService).provisionPageCopilot(project, "page-assistant", true);

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
        assertTrue(binding.getMetadataJson().contains("page-assistant"));
    }

    @Test
    void bindExistingPageWorkflowCreatesPageCopilotBindingForExistingWorkflow() {
        ScanProjectEntity project = project();
        AgentEntryEntity agent = pageCopilotAgent();
        WorkflowDefinitionEntity workflow = pageAssistantWorkflow();

        when(workflowDefinitionService.findById("workflow-1")).thenReturn(Optional.of(workflow));
        when(agentProvisioningService.provisionPageCopilot(project, "page-assistant-wizard", false))
                .thenReturn(new AgentProvisioningService.AgentProvisioningResult(
                        agent,
                        null,
                        null,
                        false,
                        false,
                        false));
        when(bindingService.list("agent-1")).thenReturn(List.of());
        when(bindingService.create(eq("agent-1"), any(AgentWorkflowBindingEntity.class))).thenAnswer(invocation -> {
            AgentWorkflowBindingEntity binding = invocation.getArgument(1);
            binding.setId(88L);
            return binding;
        });

        PageAssistantWorkflowBindingResult result = service.bindExistingPageWorkflow(
                project,
                "workflow-1",
                null,
                "teamArchive.list",
                "/team/archive",
                List.of("getPageState", "search", "readTable"));

        assertEquals("agent-1", result.agentId());
        assertEquals("order-service-page-copilot", result.agentKeySlug());
        assertEquals("workflow-1", result.workflowId());
        assertEquals("order-service-team_archive-page-assistant", result.workflowKeySlug());
        assertEquals(88L, result.bindingId());

        verify(workflowDefinitionService).findById("workflow-1");
        verify(agentProvisioningService).provisionPageCopilot(project, "page-assistant-wizard", false);
        verify(bindingService).create(eq("agent-1"), any(AgentWorkflowBindingEntity.class));
        verify(workflowDefinitionService, times(0)).create(any());
    }

    @Test
    void bindExistingPageWorkflowIsIdempotentForSameAgentWorkflowAndPageKey() {
        ScanProjectEntity project = project();
        AgentEntryEntity agent = pageCopilotAgent();
        WorkflowDefinitionEntity workflow = pageAssistantWorkflow();
        AgentWorkflowBindingEntity existing = new AgentWorkflowBindingEntity();
        existing.setId(99L);
        existing.setAgentId("agent-1");
        existing.setWorkflowId("workflow-1");
        existing.setBindingType("PAGE");
        existing.setPageKey("teamArchive.list");

        when(workflowDefinitionService.findById("workflow-1")).thenReturn(Optional.of(workflow));
        when(agentProvisioningService.provisionPageCopilot(project, "page-assistant-wizard", false))
                .thenReturn(new AgentProvisioningService.AgentProvisioningResult(
                        agent,
                        null,
                        null,
                        false,
                        false,
                        false));
        when(bindingService.list("agent-1")).thenReturn(List.of(existing));
        when(bindingService.update(eq(99L), any(AgentWorkflowBindingEntity.class))).thenReturn(existing);

        PageAssistantWorkflowBindingResult first = service.bindExistingPageWorkflow(
                project,
                "workflow-1",
                null,
                "teamArchive.list",
                "/team/archive",
                List.of("search"));
        PageAssistantWorkflowBindingResult second = service.bindExistingPageWorkflow(
                project,
                "workflow-1",
                null,
                "teamArchive.list",
                "/team/archive",
                List.of("search"));

        assertEquals(99L, first.bindingId());
        assertEquals(99L, second.bindingId());
        verify(bindingService, times(2)).update(eq(99L), any(AgentWorkflowBindingEntity.class));
        verify(bindingService, times(0)).create(any(), any());
    }

    @Test
    void bindExistingPageWorkflowUpdatesExistingPageBindingForSameAgentAndPageKey() {
        ScanProjectEntity project = project();
        AgentEntryEntity agent = pageCopilotAgent();
        WorkflowDefinitionEntity workflow = pageAssistantWorkflow();
        AgentWorkflowBindingEntity existing = new AgentWorkflowBindingEntity();
        existing.setId(100L);
        existing.setAgentId("agent-1");
        existing.setWorkflowId("old-workflow");
        existing.setBindingType("PAGE");
        existing.setPageKey("teamArchive.list");
        existing.setRoutePattern("/team/archive");
        existing.setPriority(100);
        existing.setEnabled(true);

        when(workflowDefinitionService.findById("workflow-1")).thenReturn(Optional.of(workflow));
        when(agentProvisioningService.provisionPageCopilot(project, "page-assistant-wizard", false))
                .thenReturn(new AgentProvisioningService.AgentProvisioningResult(
                        agent,
                        null,
                        null,
                        false,
                        false,
                        false));
        when(bindingService.list("agent-1")).thenReturn(List.of(existing));
        when(bindingService.update(eq(100L), any(AgentWorkflowBindingEntity.class))).thenAnswer(invocation -> {
            AgentWorkflowBindingEntity update = invocation.getArgument(1);
            existing.setWorkflowId(update.getWorkflowId());
            existing.setRoutePattern(update.getRoutePattern());
            existing.setMetadataJson(update.getMetadataJson());
            return existing;
        });

        PageAssistantWorkflowBindingResult result = service.bindExistingPageWorkflow(
                project,
                "workflow-1",
                null,
                "teamArchive.list",
                "/team/archive",
                List.of("search"));

        assertEquals(100L, result.bindingId());
        assertEquals("workflow-1", result.workflowId());
        verify(bindingService).update(eq(100L), any(AgentWorkflowBindingEntity.class));
        verify(bindingService, never()).create(any(), any());
    }

    @Test
    void bindExistingPageWorkflowFailsWhenWorkflowMissing() {
        when(workflowDefinitionService.findById("missing")).thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.bindExistingPageWorkflow(
                        project(),
                        "missing",
                        null,
                        "teamArchive.list",
                        "/team/archive",
                        List.of("search")));

        assertTrue(error.getMessage().contains("workflow not found"));
    }

    @Test
    void bindExistingPageWorkflowFailsWhenWorkflowTypeIsNotPageAssistant() {
        WorkflowDefinitionEntity workflow = pageAssistantWorkflow();
        workflow.setWorkflowType("CHAT");
        when(workflowDefinitionService.findById("workflow-1")).thenReturn(Optional.of(workflow));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.bindExistingPageWorkflow(
                        project(),
                        "workflow-1",
                        null,
                        "teamArchive.list",
                        "/team/archive",
                        List.of("search")));

        assertTrue(error.getMessage().contains("PAGE_ASSISTANT"));
    }

    @Test
    void bindExistingPageWorkflowValidatesAgentProjectWhenAgentIdProvided() {
        ScanProjectEntity project = project();
        WorkflowDefinitionEntity workflow = pageAssistantWorkflow();
        AgentEntryEntity agent = pageCopilotAgent();
        agent.setProjectId(999L);
        agent.setProjectCode("other-service");

        when(workflowDefinitionService.findById("workflow-1")).thenReturn(Optional.of(workflow));
        when(agentEntryService.findById("agent-1")).thenReturn(Optional.of(agent));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.bindExistingPageWorkflow(
                        project,
                        "workflow-1",
                        "agent-1",
                        "teamArchive.list",
                        "/team/archive",
                        List.of("search")));

        assertTrue(error.getMessage().contains("agent project mismatch"));
    }

    private ScanProjectEntity project() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("order-service");
        project.setName("Order Service");
        project.setVisibility("PROJECT");
        return project;
    }

    private AgentEntryEntity pageCopilotAgent() {
        AgentEntryEntity agent = new AgentEntryEntity();
        agent.setId("agent-1");
        agent.setProjectId(7L);
        agent.setProjectCode("order-service");
        agent.setKeySlug("order-service-page-copilot");
        agent.setAgentKind("PAGE_COPILOT");
        return agent;
    }

    private WorkflowDefinitionEntity pageAssistantWorkflow() {
        WorkflowDefinitionEntity workflow = new WorkflowDefinitionEntity();
        workflow.setId("workflow-1");
        workflow.setProjectId(7L);
        workflow.setProjectCode("order-service");
        workflow.setKeySlug("order-service-team_archive-page-assistant");
        workflow.setWorkflowType("PAGE_ASSISTANT");
        return workflow;
    }
}
