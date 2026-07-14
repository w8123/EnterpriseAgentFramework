package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigService;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigDraftRequest;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigVersionView;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.WorkflowToolView;
import com.enterprise.ai.runtime.agent.RuntimeAgentEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
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

class RuntimePageAssistantWorkflowAttachmentServiceTest {

    @Test
    void attachesPublishedWorkflowAsToolAndPublishesSupervisorConfig() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        RuntimePageAssistantWorkflowAttachmentService service = new RuntimePageAssistantWorkflowAttachmentService(
                capabilityClient, workflowService, agentMapper, configService, new ObjectMapper());
        when(capabilityClient.getProject("orders")).thenReturn(project("orders", 7L));
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(pageWorkflow()));
        when(agentMapper.selectById("agent-1")).thenReturn(agent("agent-1", "orders-page-copilot"));
        AgentConfigVersionView draft = config(21L, 2, "DRAFT", List.of(tool(21L)));
        AgentConfigVersionView active = config(21L, 2, "ACTIVE", List.of(tool(21L)));
        when(configService.ensureWorkflowToolInDraft("agent-1", "wf-1", false)).thenReturn(draft);
        when(configService.publish("agent-1", 21L, "wizard")).thenReturn(active);

        RuntimePageAssistantWorkflowAttachment result = service.attachPublishedPageWorkflow("wf-1",
                new RuntimePageAssistantWorkflowAttachRequest(
                        null, " orders ", " agent-1 ", "model-1", "wizard"));

        assertEquals("agent-1", result.agentId());
        assertEquals("orders-page-copilot", result.agentKeySlug());
        assertEquals("wf-1", result.workflowId());
        assertEquals("orders_page_assistant", result.toolName());
        assertEquals(21L, result.configVersionId());
        assertEquals(2, result.configVersionNo());
        assertEquals("ACTIVE", result.configStatus());
        assertEquals(true, result.published());
        ArgumentCaptor<AgentConfigDraftRequest> config = ArgumentCaptor.forClass(AgentConfigDraftRequest.class);
        verify(configService).saveDraft(org.mockito.ArgumentMatchers.eq("agent-1"), config.capture());
        assertEquals("AGENTSCOPE", config.getValue().runtimeType());
        assertEquals("model-1", config.getValue().modelInstanceId());
        verify(configService).ensureWorkflowToolInDraft("agent-1", "wf-1", false);
        verify(configService).publish("agent-1", 21L, "wizard");
    }

    @Test
    void provisionsProjectPageCopilotBeforePublishingItsSupervisorConfig() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        RuntimePageAssistantWorkflowAttachmentService service = new RuntimePageAssistantWorkflowAttachmentService(
                capabilityClient, workflowService, agentMapper, configService, new ObjectMapper());
        when(capabilityClient.getProject("orders")).thenReturn(project("orders", 7L));
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(pageWorkflow()));
        when(agentMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            RuntimeAgentEntity entity = invocation.getArgument(0);
            entity.setId("generated-agent");
            return 1;
        }).when(agentMapper).insert(any(RuntimeAgentEntity.class));
        when(configService.ensureWorkflowToolInDraft("generated-agent", "wf-1", false))
                .thenReturn(config(1L, 1, "DRAFT", List.of(tool(1L))));
        when(configService.publish("generated-agent", 1L, "wizard"))
                .thenReturn(config(1L, 1, "ACTIVE", List.of(tool(1L))));

        RuntimePageAssistantWorkflowAttachment result = service.attachPublishedPageWorkflow("wf-1",
                new RuntimePageAssistantWorkflowAttachRequest(
                        null, "orders", null, "model-1", "wizard"));

        assertEquals("generated-agent", result.agentId());
        assertEquals("orders-page-copilot", result.agentKeySlug());
        ArgumentCaptor<RuntimeAgentEntity> agent = ArgumentCaptor.forClass(RuntimeAgentEntity.class);
        verify(agentMapper).insert(agent.capture());
        assertEquals(7L, agent.getValue().getProjectId());
        assertEquals("orders-page-copilot", agent.getValue().getKeySlug());
        verify(configService).createInitialDraft(
                org.mockito.ArgumentMatchers.eq("generated-agent"),
                org.mockito.ArgumentMatchers.contains("select the permitted Workflows"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.contains("supervisor-workflow-tools"));
        verify(configService).publish("generated-agent", 1L, "wizard");
    }

    @Test
    void rejectsMissingSupervisorModelBeforePublishing() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimePageAssistantWorkflowAttachmentService service = new RuntimePageAssistantWorkflowAttachmentService(
                capabilityClient, workflowService, agentMapper, mock(RuntimeAgentConfigService.class), new ObjectMapper());
        when(capabilityClient.getProject("orders")).thenReturn(project("orders", 7L));
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(pageWorkflow()));
        when(agentMapper.selectById("agent-1")).thenReturn(agent("agent-1", "orders-page-copilot"));

        assertThrows(IllegalArgumentException.class, () -> service.attachPublishedPageWorkflow("wf-1",
                new RuntimePageAssistantWorkflowAttachRequest(
                        null, "orders", "agent-1", null, "wizard")));
    }

    @Test
    void rejectsNonPageAssistantWorkflow() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimePageAssistantWorkflowAttachmentService service = new RuntimePageAssistantWorkflowAttachmentService(
                capabilityClient, workflowService, mock(RuntimeAgentMapper.class),
                mock(RuntimeAgentConfigService.class), new ObjectMapper());
        RuntimeWorkflowDefinitionEntity workflow = pageWorkflow();
        workflow.setWorkflowType("CHAT");
        when(capabilityClient.getProject("orders")).thenReturn(project("orders", 7L));
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));

        assertThrows(IllegalArgumentException.class, () -> service.attachPublishedPageWorkflow("wf-1",
                new RuntimePageAssistantWorkflowAttachRequest(
                        null, "orders", "agent-1", "model-1", "wizard")));
    }

    private AgentConfigVersionView config(Long id,
                                          int versionNo,
                                          String status,
                                          List<WorkflowToolView> tools) {
        LocalDateTime now = LocalDateTime.now();
        return new AgentConfigVersionView(
                id, "agent-1", versionNo, status, "AGENTSCOPE", "prompt", "model-1",
                6, 4, 2, 300_000, 180_000, 30_000, true,
                "DEV_ALLOW_ALL", "ALLOW_LIST", null, "wizard",
                now, now, now, tools);
    }

    private WorkflowToolView tool(Long configVersionId) {
        LocalDateTime now = LocalDateTime.now();
        return new WorkflowToolView(
                31L, "agent-1", configVersionId, "wf-1", "orders-page-assistant",
                "Orders Page Assistant", "v1.0.0", 41L, "orders_page_assistant",
                null, "Query and operate the orders page", null, null, null, null, "PAGE_ACTION",
                "workflow:orders-page-assistant", false, true, 0, now, now);
    }

    private Map<String, Object> project(String projectCode, Long projectId) {
        return Map.of(
                "projectId", projectId,
                "projectCode", projectCode,
                "name", "Orders",
                "visibility", "PROJECT");
    }

    private RuntimeWorkflowDefinitionEntity pageWorkflow() {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId("wf-1");
        workflow.setProjectId(7L);
        workflow.setProjectCode("orders");
        workflow.setKeySlug("orders-page-assistant");
        workflow.setWorkflowType("PAGE_ASSISTANT");
        workflow.setStatus("ACTIVE");
        return workflow;
    }

    private RuntimeAgentEntity agent(String id, String keySlug) {
        RuntimeAgentEntity agent = new RuntimeAgentEntity();
        agent.setId(id);
        agent.setProjectId(7L);
        agent.setProjectCode("orders");
        agent.setKeySlug(keySlug);
        return agent;
    }
}
