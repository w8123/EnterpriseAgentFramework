package com.enterprise.ai.runtime.workflow.aicoding;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigService;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigVersionView;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.WorkflowToolView;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelCatalogClient;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeAgentSupervisorWorkflowAttachmentServiceTest {

    @Test
    void attachesChatWorkflowViaAgentKeySlug() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeModelCatalogClient modelCatalogClient = mock(RuntimeModelCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        RuntimeAgentSupervisorWorkflowAttachmentService service =
                new RuntimeAgentSupervisorWorkflowAttachmentService(
                        capabilityClient, modelCatalogClient, workflowService, agentMapper, configService,
                        new ObjectMapper());

        when(capabilityClient.getProjectById(7L)).thenReturn(project());
        when(workflowService.findById("wf-chat")).thenReturn(Optional.of(workflow("wf-chat", "CHAT")));
        when(agentMapper.selectOne(any())).thenReturn(agent("agent-1", "orders-page-copilot"));
        when(modelCatalogClient.isActiveLlm("model-1")).thenReturn(true);
        when(configService.resolveActive("agent-1")).thenReturn(Optional.empty());
        AgentConfigVersionView draft = config(21L, 2, "DRAFT");
        AgentConfigVersionView active = config(21L, 2, "ACTIVE");
        when(configService.ensureWorkflowToolInDraft("agent-1", "wf-chat", true)).thenReturn(draft);
        when(configService.publish("agent-1", 21L, "Cursor")).thenReturn(active);

        RuntimeAgentSupervisorWorkflowAttachmentService.AttachmentResult result = service.attach(
                7L,
                new RuntimeAgentSupervisorWorkflowAttachmentService.AttachRequest(
                        "wf-chat", null, "orders-page-copilot", "model-1", "Cursor"));

        assertEquals("workflow-tool-attachment.v1", result.schema());
        assertEquals("CHAT", result.workflow().workflowType());
        assertEquals("chat_flow", result.toolName());
        assertTrue(result.created());
        assertFalse(result.reused());
        verify(configService).publish("agent-1", 21L, "Cursor");
    }

    @Test
    void reusesActiveAttachmentIdempotently() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeModelCatalogClient modelCatalogClient = mock(RuntimeModelCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        RuntimeAgentSupervisorWorkflowAttachmentService service =
                new RuntimeAgentSupervisorWorkflowAttachmentService(
                        capabilityClient, modelCatalogClient, workflowService, agentMapper, configService,
                        new ObjectMapper());

        when(capabilityClient.getProjectById(7L)).thenReturn(project());
        when(workflowService.findById("wf-chat")).thenReturn(Optional.of(workflow("wf-chat", "CHAT")));
        when(agentMapper.selectOne(any())).thenReturn(agent("agent-1", "orders-page-copilot"));
        when(modelCatalogClient.isActiveLlm("model-1")).thenReturn(true);
        RuntimeAgentConfigVersionEntity activeEntity = new RuntimeAgentConfigVersionEntity();
        activeEntity.setId(21L);
        activeEntity.setModelInstanceId("model-1");
        when(configService.resolveActive("agent-1")).thenReturn(Optional.of(activeEntity));
        when(configService.listTools("agent-1", 21L)).thenReturn(List.of(tool(21L, "wf-chat")));
        when(configService.list("agent-1")).thenReturn(List.of(config(21L, 2, "ACTIVE")));

        RuntimeAgentSupervisorWorkflowAttachmentService.AttachmentResult result = service.attach(
                7L,
                new RuntimeAgentSupervisorWorkflowAttachmentService.AttachRequest(
                        "wf-chat", null, "orders-page-copilot", "model-1", "Cursor"));

        assertTrue(result.reused());
        assertFalse(result.created());
        verify(configService, never()).publish(any(), any(), any());
    }

    @Test
    void rejectsChatOnPageAssistantOnlyPath() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeAgentSupervisorWorkflowAttachmentService service =
                new RuntimeAgentSupervisorWorkflowAttachmentService(
                        capabilityClient, mock(RuntimeModelCatalogClient.class), workflowService,
                        mock(RuntimeAgentMapper.class), mock(RuntimeAgentConfigService.class), new ObjectMapper());
        when(capabilityClient.getProject("orders")).thenReturn(project());
        when(workflowService.findById("wf-chat")).thenReturn(Optional.of(workflow("wf-chat", "CHAT")));

        AiCodingAttachmentException ex = assertThrows(AiCodingAttachmentException.class,
                () -> service.attachPageAssistantOnly("wf-chat",
                        new RuntimeAgentSupervisorWorkflowAttachmentService.PageAssistantAttachRequest(
                                null, "orders", null, "model-1", "wizard")));
        assertEquals("WORKFLOW_TYPE_NOT_SUPPORTED", ex.code());
    }

    @Test
    void returnsNoActiveLlmWhenCatalogEmpty() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeModelCatalogClient modelCatalogClient = mock(RuntimeModelCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        RuntimeAgentSupervisorWorkflowAttachmentService service =
                new RuntimeAgentSupervisorWorkflowAttachmentService(
                        capabilityClient, modelCatalogClient, workflowService, agentMapper, configService,
                        new ObjectMapper());
        when(capabilityClient.getProjectById(7L)).thenReturn(project());
        when(workflowService.findById("wf-chat")).thenReturn(Optional.of(workflow("wf-chat", "CHAT")));
        when(agentMapper.selectOne(any())).thenReturn(agent("agent-1", "orders-page-copilot"));
        when(configService.resolveActive("agent-1")).thenReturn(Optional.empty());
        when(modelCatalogClient.firstActiveLlmId()).thenReturn(null);

        AiCodingAttachmentException ex = assertThrows(AiCodingAttachmentException.class,
                () -> service.attach(7L, new RuntimeAgentSupervisorWorkflowAttachmentService.AttachRequest(
                        "wf-chat", null, "orders-page-copilot", null, "Cursor")));
        assertEquals("NO_ACTIVE_LLM", ex.code());
    }

    @Test
    void mapsModelCatalogFailureToRuntimeDependencyUnavailable() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeModelCatalogClient modelCatalogClient = mock(RuntimeModelCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        RuntimeAgentSupervisorWorkflowAttachmentService service =
                new RuntimeAgentSupervisorWorkflowAttachmentService(
                        capabilityClient, modelCatalogClient, workflowService, agentMapper, configService,
                        new ObjectMapper());
        when(capabilityClient.getProjectById(7L)).thenReturn(project());
        when(workflowService.findById("wf-chat")).thenReturn(Optional.of(workflow("wf-chat", "CHAT")));
        when(agentMapper.selectOne(any())).thenReturn(agent("agent-1", "orders-page-copilot"));
        when(configService.resolveActive("agent-1")).thenReturn(Optional.empty());
        when(modelCatalogClient.firstActiveLlmId()).thenThrow(new RuntimeException("model service down"));

        AiCodingAttachmentException ex = assertThrows(AiCodingAttachmentException.class,
                () -> service.attach(7L, new RuntimeAgentSupervisorWorkflowAttachmentService.AttachRequest(
                        "wf-chat", null, "orders-page-copilot", null, "Cursor")));
        assertEquals("RUNTIME_DEPENDENCY_UNAVAILABLE", ex.code());
    }

    private AgentConfigVersionView config(Long id, int versionNo, String status) {
        LocalDateTime now = LocalDateTime.now();
        return new AgentConfigVersionView(
                id, "agent-1", versionNo, status, "AGENTSCOPE", "prompt", "model-1",
                6, 4, 2, 300_000, 180_000, 30_000, true,
                "DEV_ALLOW_ALL", "ALLOW_LIST", null, "Cursor",
                now, now, now, List.of(tool(id, "wf-chat")));
    }

    private WorkflowToolView tool(Long configVersionId, String workflowId) {
        LocalDateTime now = LocalDateTime.now();
        return new WorkflowToolView(
                31L, "agent-1", configVersionId, workflowId, "chat-flow",
                "Chat", "v1.0.0", 41L, "chat_flow",
                null, "Chat workflow", null, null, null, null, "READ",
                "workflow:chat-flow", true, true, 0, now, now);
    }

    private Map<String, Object> project() {
        return Map.of("projectId", 7L, "projectCode", "orders", "visibility", "PROJECT");
    }

    private RuntimeWorkflowDefinitionEntity workflow(String id, String type) {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId(id);
        workflow.setProjectId(7L);
        workflow.setProjectCode("orders");
        workflow.setKeySlug("chat-flow");
        workflow.setWorkflowType(type);
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
