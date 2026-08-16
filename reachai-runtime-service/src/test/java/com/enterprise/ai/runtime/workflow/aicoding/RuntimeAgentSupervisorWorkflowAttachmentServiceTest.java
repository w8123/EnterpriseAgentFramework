package com.enterprise.ai.runtime.workflow.aicoding;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigService;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigDraftRequest;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigVersionView;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.WorkflowToolView;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.WorkflowToolRequest;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelCatalogClient;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowResourceBindingService.BindingView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeAgentSupervisorWorkflowAttachmentServiceTest {

    @Test
    void attachesGeneralWorkflowViaAgentKeySlug() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeModelCatalogClient modelCatalogClient = mock(RuntimeModelCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        RuntimeAgentSupervisorWorkflowAttachmentService service =
                new RuntimeAgentSupervisorWorkflowAttachmentService(
                        capabilityClient, modelCatalogClient, workflowService, mock(RuntimeWorkflowVersionService.class),
                        agentMapper, configService,
                        new ObjectMapper());

        when(capabilityClient.getProjectById(7L)).thenReturn(project());
        when(workflowService.findById("wf-chat")).thenReturn(Optional.of(workflow("wf-chat", "GENERAL")));
        when(agentMapper.selectOne(any())).thenReturn(agent("agent-1", "orders-page-copilot"));
        when(modelCatalogClient.isActiveLlm("model-1")).thenReturn(true);
        when(configService.resolveActive("agent-1")).thenReturn(Optional.empty());
        AgentConfigVersionView draft = config(21L, 2, "DRAFT");
        AgentConfigVersionView active = config(21L, 2, "ACTIVE");
        when(configService.upsertWorkflowToolInDraft(eq("agent-1"), any())).thenReturn(draft);
        when(configService.publish("agent-1", 21L, "Cursor")).thenReturn(active);

        RuntimeAgentSupervisorWorkflowAttachmentService.AttachmentResult result = service.attach(
                7L,
                new RuntimeAgentSupervisorWorkflowAttachmentService.AttachRequest(
                        "wf-chat", null, "orders-page-copilot", "model-1", "Cursor",
                        "disable_team", "停用班组", Map.of("type", "object"), null,
                        "WRITE", "workflow:disable-team", false, 5));

        assertEquals("workflow-tool-attachment.v1", result.schema());
        assertEquals("GENERAL", result.workflow().workflowKind());
        assertEquals("chat_flow", result.toolName());
        assertTrue(result.created());
        assertFalse(result.reused());
        ArgumentCaptor<WorkflowToolRequest> toolCaptor = ArgumentCaptor.forClass(WorkflowToolRequest.class);
        verify(configService).upsertWorkflowToolInDraft(eq("agent-1"), toolCaptor.capture());
        assertEquals("disable_team", toolCaptor.getValue().toolName());
        assertEquals("WRITE", toolCaptor.getValue().riskLevel());
        assertFalse(toolCaptor.getValue().readOnly());
        assertTrue(toolCaptor.getValue().inputSchemaOverrideJson().contains("\"type\":\"object\""));
        verify(configService).publish("agent-1", 21L, "Cursor");
    }

    @Test
    void normalizesWorkflowKeySlugWhenDefaultingToolName() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeModelCatalogClient modelCatalogClient = mock(RuntimeModelCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        RuntimeAgentSupervisorWorkflowAttachmentService service =
                new RuntimeAgentSupervisorWorkflowAttachmentService(
                        capabilityClient, modelCatalogClient, workflowService, mock(RuntimeWorkflowVersionService.class),
                        agentMapper, configService,
                        new ObjectMapper());

        when(capabilityClient.getProjectById(7L)).thenReturn(project());
        when(workflowService.findById("wf-chat")).thenReturn(Optional.of(workflow("wf-chat", "GENERAL")));
        when(agentMapper.selectOne(any())).thenReturn(agent("agent-1", "orders-page-copilot"));
        when(modelCatalogClient.isActiveLlm("model-1")).thenReturn(true);
        when(configService.resolveActive("agent-1")).thenReturn(Optional.empty());
        AgentConfigVersionView draft = config(21L, 2, "DRAFT");
        AgentConfigVersionView active = config(21L, 2, "ACTIVE");
        when(configService.upsertWorkflowToolInDraft(eq("agent-1"), any())).thenReturn(draft);
        when(configService.publish("agent-1", 21L, "Cursor")).thenReturn(active);

        service.attach(
                7L,
                new RuntimeAgentSupervisorWorkflowAttachmentService.AttachRequest(
                        "wf-chat", null, "orders-page-copilot", "model-1", "Cursor"));

        ArgumentCaptor<WorkflowToolRequest> toolCaptor = ArgumentCaptor.forClass(WorkflowToolRequest.class);
        verify(configService).upsertWorkflowToolInDraft(eq("agent-1"), toolCaptor.capture());
        assertEquals("chat_flow", toolCaptor.getValue().toolName());
    }

    @Test
    void explicitlyReplacesAnAttachedWorkflowAndReportsTheReplacedId() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeModelCatalogClient modelCatalogClient = mock(RuntimeModelCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        RuntimeAgentSupervisorWorkflowAttachmentService service =
                new RuntimeAgentSupervisorWorkflowAttachmentService(
                        capabilityClient, modelCatalogClient, workflowService,
                        mock(RuntimeWorkflowVersionService.class), agentMapper,
                        configService, new ObjectMapper());

        when(capabilityClient.getProjectById(7L)).thenReturn(project());
        when(workflowService.findById("wf-chat"))
                .thenReturn(Optional.of(workflow("wf-chat", "PAGE_ASSISTANT")));
        when(workflowService.findById("wf-old"))
                .thenReturn(Optional.of(workflow("wf-old", "PAGE_ASSISTANT")));
        when(workflowService.listResourceBindings("wf-chat"))
                .thenReturn(List.of(targetPage("wf-chat", "orders.detail")));
        when(workflowService.listResourceBindings("wf-old"))
                .thenReturn(List.of(targetPage("wf-old", "orders.detail")));
        when(agentMapper.selectOne(any()))
                .thenReturn(agent("agent-1", "orders-page-copilot"));
        when(modelCatalogClient.isActiveLlm("model-1")).thenReturn(true);
        when(configService.resolveActive("agent-1")).thenReturn(Optional.empty());
        when(configService.replaceWorkflowToolInDraft(
                eq("agent-1"), eq("wf-old"), any()))
                .thenReturn(config(21L, 2, "DRAFT"));
        when(configService.publish("agent-1", 21L, "Cursor"))
                .thenReturn(config(21L, 2, "ACTIVE"));

        RuntimeAgentSupervisorWorkflowAttachmentService.AttachmentResult result =
                service.attach(7L,
                        new RuntimeAgentSupervisorWorkflowAttachmentService.AttachRequest(
                                "wf-chat", null, "orders-page-copilot",
                                "model-1", "Cursor", null, null, null,
                                null, null, null, null, null, "wf-old"));

        assertEquals("wf-old", result.replacedWorkflowId());
        verify(configService).replaceWorkflowToolInDraft(
                eq("agent-1"), eq("wf-old"), any(WorkflowToolRequest.class));
        verify(configService, never()).upsertWorkflowToolInDraft(any(), any());
    }

    @Test
    void rejectsCrossPageReplacementBeforeCreatingOrPublishingAgentConfig() {
        RuntimeCapabilityCatalogClient capabilityClient =
                mock(RuntimeCapabilityCatalogClient.class);
        RuntimeModelCatalogClient modelCatalogClient =
                mock(RuntimeModelCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService =
                mock(RuntimeWorkflowDefinitionService.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigService configService =
                mock(RuntimeAgentConfigService.class);
        RuntimeAgentSupervisorWorkflowAttachmentService service =
                new RuntimeAgentSupervisorWorkflowAttachmentService(
                        capabilityClient,
                        modelCatalogClient,
                        workflowService,
                        mock(RuntimeWorkflowVersionService.class),
                        agentMapper,
                        configService,
                        new ObjectMapper());

        when(capabilityClient.getProjectById(7L)).thenReturn(project());
        when(workflowService.findById("wf-chat"))
                .thenReturn(Optional.of(workflow("wf-chat", "PAGE_ASSISTANT")));
        when(workflowService.findById("wf-old"))
                .thenReturn(Optional.of(workflow("wf-old", "PAGE_ASSISTANT")));
        when(workflowService.listResourceBindings("wf-chat"))
                .thenReturn(List.of(targetPage("wf-chat", "orders.detail")));
        when(workflowService.listResourceBindings("wf-old"))
                .thenReturn(List.of(targetPage("wf-old", "orders.list")));

        AiCodingAttachmentException error = assertThrows(
                AiCodingAttachmentException.class,
                () -> service.attach(
                        7L,
                        new RuntimeAgentSupervisorWorkflowAttachmentService.AttachRequest(
                                "wf-chat", null, "orders-page-copilot",
                                "model-1", "Cursor", null, null, null,
                                null, null, null, null, null, "wf-old")));

        assertEquals("WORKFLOW_REPLACEMENT_INVALID", error.code());
        assertTrue(error.getMessage().contains("TARGET PAGE"));
        verify(agentMapper, never()).selectOne(any());
        verify(configService, never()).saveDraft(any(), any());
        verify(configService, never()).replaceWorkflowToolInDraft(
                any(), any(), any());
        verify(configService, never()).publish(any(), any(), any());
    }

    @Test
    void reusesActiveAttachmentIdempotently() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeModelCatalogClient modelCatalogClient = mock(RuntimeModelCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        RuntimeWorkflowVersionService workflowVersionService = mock(RuntimeWorkflowVersionService.class);
        RuntimeAgentSupervisorWorkflowAttachmentService service =
                new RuntimeAgentSupervisorWorkflowAttachmentService(
                        capabilityClient, modelCatalogClient, workflowService, workflowVersionService,
                        agentMapper, configService,
                        new ObjectMapper());

        when(capabilityClient.getProjectById(7L)).thenReturn(project());
        when(workflowService.findById("wf-chat")).thenReturn(Optional.of(workflow("wf-chat", "GENERAL")));
        when(agentMapper.selectOne(any())).thenReturn(agent("agent-1", "orders-page-copilot"));
        when(modelCatalogClient.isActiveLlm("model-1")).thenReturn(true);
        RuntimeAgentConfigVersionEntity activeEntity = new RuntimeAgentConfigVersionEntity();
        activeEntity.setId(21L);
        activeEntity.setModelInstanceId("model-1");
        when(configService.resolveActive("agent-1")).thenReturn(Optional.of(activeEntity));
        when(configService.listTools("agent-1", 21L)).thenReturn(List.of(tool(21L, "wf-chat")));
        when(configService.list("agent-1")).thenReturn(List.of(config(21L, 2, "ACTIVE")));
        when(workflowVersionService.resolveActive("wf-chat")).thenReturn(workflowVersion(41L));

        RuntimeAgentSupervisorWorkflowAttachmentService.AttachmentResult result = service.attach(
                7L,
                new RuntimeAgentSupervisorWorkflowAttachmentService.AttachRequest(
                        "wf-chat", null, "orders-page-copilot", "model-1", "Cursor"));

        assertTrue(result.reused());
        assertFalse(result.created());
        verify(configService, never()).publish(any(), any(), any());
    }

    @Test
    void republishesAgentConfigWhenAttachedWorkflowHasANewerActiveVersion() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeModelCatalogClient modelCatalogClient = mock(RuntimeModelCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeWorkflowVersionService workflowVersionService = mock(RuntimeWorkflowVersionService.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        RuntimeAgentSupervisorWorkflowAttachmentService service =
                new RuntimeAgentSupervisorWorkflowAttachmentService(
                        capabilityClient, modelCatalogClient, workflowService, workflowVersionService,
                        agentMapper, configService, new ObjectMapper());

        when(capabilityClient.getProjectById(7L)).thenReturn(project());
        when(workflowService.findById("wf-chat")).thenReturn(Optional.of(workflow("wf-chat", "GENERAL")));
        when(agentMapper.selectOne(any())).thenReturn(agent("agent-1", "orders-page-copilot"));
        when(modelCatalogClient.isActiveLlm("model-1")).thenReturn(true);
        RuntimeAgentConfigVersionEntity activeEntity = new RuntimeAgentConfigVersionEntity();
        activeEntity.setId(21L);
        activeEntity.setModelInstanceId("model-1");
        when(configService.resolveActive("agent-1")).thenReturn(Optional.of(activeEntity));
        when(configService.listTools("agent-1", 21L)).thenReturn(List.of(tool(21L, "wf-chat")));
        when(workflowVersionService.resolveActive("wf-chat")).thenReturn(workflowVersion(42L));
        when(configService.saveDraft(eq("agent-1"), any())).thenReturn(config(22L, 3, "DRAFT"));
        when(configService.publish("agent-1", 22L, "Cursor")).thenReturn(config(22L, 3, "ACTIVE"));

        RuntimeAgentSupervisorWorkflowAttachmentService.AttachmentResult result = service.attach(
                7L,
                new RuntimeAgentSupervisorWorkflowAttachmentService.AttachRequest(
                        "wf-chat", null, "orders-page-copilot", "model-1", "Cursor"));

        assertTrue(result.created());
        assertFalse(result.reused());
        verify(configService).saveDraft(eq("agent-1"), any(AgentConfigDraftRequest.class));
        verify(configService).publish("agent-1", 22L, "Cursor");
        verify(configService, never()).upsertWorkflowToolInDraft(any(), any());
    }

    @Test
    void rejectsGeneralOnPageAssistantOnlyPath() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeAgentSupervisorWorkflowAttachmentService service =
                new RuntimeAgentSupervisorWorkflowAttachmentService(
                        capabilityClient, mock(RuntimeModelCatalogClient.class), workflowService,
                        mock(RuntimeWorkflowVersionService.class), mock(RuntimeAgentMapper.class),
                        mock(RuntimeAgentConfigService.class), new ObjectMapper());
        when(capabilityClient.getProject("orders")).thenReturn(project());
        when(workflowService.findById("wf-chat")).thenReturn(Optional.of(workflow("wf-chat", "GENERAL")));

        AiCodingAttachmentException ex = assertThrows(AiCodingAttachmentException.class,
                () -> service.attachPageAssistantOnly("wf-chat",
                        new RuntimeAgentSupervisorWorkflowAttachmentService.PageAssistantAttachRequest(
                                null, "orders", null, "model-1", "wizard")));
        assertEquals("WORKFLOW_KIND_NOT_SUPPORTED", ex.code());
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
                        capabilityClient, modelCatalogClient, workflowService, mock(RuntimeWorkflowVersionService.class),
                        agentMapper, configService,
                        new ObjectMapper());
        when(capabilityClient.getProjectById(7L)).thenReturn(project());
        when(workflowService.findById("wf-chat")).thenReturn(Optional.of(workflow("wf-chat", "GENERAL")));
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
                        capabilityClient, modelCatalogClient, workflowService, mock(RuntimeWorkflowVersionService.class),
                        agentMapper, configService,
                        new ObjectMapper());
        when(capabilityClient.getProjectById(7L)).thenReturn(project());
        when(workflowService.findById("wf-chat")).thenReturn(Optional.of(workflow("wf-chat", "GENERAL")));
        when(agentMapper.selectOne(any())).thenReturn(agent("agent-1", "orders-page-copilot"));
        when(configService.resolveActive("agent-1")).thenReturn(Optional.empty());
        when(modelCatalogClient.firstActiveLlmId()).thenThrow(new RuntimeException("model service down"));

        AiCodingAttachmentException ex = assertThrows(AiCodingAttachmentException.class,
                () -> service.attach(7L, new RuntimeAgentSupervisorWorkflowAttachmentService.AttachRequest(
                        "wf-chat", null, "orders-page-copilot", null, "Cursor")));
        assertEquals("RUNTIME_DEPENDENCY_UNAVAILABLE", ex.code());
    }

    @Test
    void createsManagedPageCopilotWithChineseReadableDefaults() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeModelCatalogClient modelCatalogClient = mock(RuntimeModelCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        RuntimeAgentSupervisorWorkflowAttachmentService service =
                new RuntimeAgentSupervisorWorkflowAttachmentService(
                        capabilityClient, modelCatalogClient, workflowService, mock(RuntimeWorkflowVersionService.class),
                        agentMapper, configService,
                        new ObjectMapper());

        when(capabilityClient.getProjectById(7L)).thenReturn(project());
        when(workflowService.findById("wf-chat")).thenReturn(Optional.of(workflow("wf-chat", "GENERAL")));
        when(agentMapper.selectOne(any())).thenReturn(null);
        when(modelCatalogClient.isActiveLlm("model-1")).thenReturn(true);
        when(configService.resolveActive(any())).thenReturn(Optional.empty());
        when(configService.upsertWorkflowToolInDraft(any(), any())).thenReturn(config(21L, 1, "DRAFT"));
        when(configService.publish(any(), eq(21L), eq("Codex"))).thenReturn(config(21L, 1, "ACTIVE"));

        RuntimeAgentSupervisorWorkflowAttachmentService.AttachmentResult result = service.attach(
                7L,
                new RuntimeAgentSupervisorWorkflowAttachmentService.AttachRequest(
                        "wf-chat", null, null, "model-1", "Codex"));

        ArgumentCaptor<RuntimeAgentEntity> agentCaptor = ArgumentCaptor.forClass(RuntimeAgentEntity.class);
        verify(agentMapper).insert(agentCaptor.capture());
        RuntimeAgentEntity created = agentCaptor.getValue();
        assertEquals("orders 页面副驾驶 Agent", created.getName());
        assertEquals(
                "项目页面副驾驶 Agent，用于嵌入式对话、页面理解和 Workflow 路由。",
                created.getDescription());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(configService).createInitialDraft(
                eq(created.getId()),
                promptCaptor.capture(),
                isNull(),
                org.mockito.ArgumentMatchers.anyString());
        assertTrue(promptCaptor.getValue().startsWith("你是当前项目的页面副驾驶 Supervisor"));
        assertTrue(promptCaptor.getValue().contains("名称、说明和回复默认使用简体中文"));
        assertEquals(created.getId(), result.agent().id());
    }

    @Test
    void upgradesLegacyManagedPageCopilotContentToChinese() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeModelCatalogClient modelCatalogClient = mock(RuntimeModelCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        RuntimeAgentSupervisorWorkflowAttachmentService service =
                new RuntimeAgentSupervisorWorkflowAttachmentService(
                        capabilityClient, modelCatalogClient, workflowService, mock(RuntimeWorkflowVersionService.class),
                        agentMapper, configService,
                        new ObjectMapper());
        RuntimeAgentEntity legacyAgent = agent("agent-1", "orders-page-copilot");
        legacyAgent.setName("orders Page Copilot");
        legacyAgent.setDescription(
                "Project page copilot Agent for embedded chat, page understanding, and Workflow routing.");
        RuntimeAgentConfigVersionEntity activeEntity = new RuntimeAgentConfigVersionEntity();
        activeEntity.setId(20L);
        activeEntity.setModelInstanceId("model-1");
        activeEntity.setSystemPrompt(
                "You are the project's page copilot. Understand the user's intent and select the permitted Workflows as tools.");

        when(capabilityClient.getProjectById(7L)).thenReturn(project());
        when(workflowService.findById("wf-chat")).thenReturn(Optional.of(workflow("wf-chat", "GENERAL")));
        when(agentMapper.selectOne(any())).thenReturn(legacyAgent);
        when(modelCatalogClient.isActiveLlm("model-1")).thenReturn(true);
        when(configService.resolveActive("agent-1")).thenReturn(Optional.of(activeEntity));
        when(configService.listTools("agent-1", 20L)).thenReturn(List.of(tool(20L, "wf-chat")));
        when(configService.upsertWorkflowToolInDraft(eq("agent-1"), any())).thenReturn(config(21L, 3, "DRAFT"));
        when(configService.publish("agent-1", 21L, "Codex")).thenReturn(config(21L, 3, "ACTIVE"));

        service.attach(
                7L,
                new RuntimeAgentSupervisorWorkflowAttachmentService.AttachRequest(
                        "wf-chat", null, null, "model-1", "Codex"));

        ArgumentCaptor<RuntimeAgentEntity> agentCaptor = ArgumentCaptor.forClass(RuntimeAgentEntity.class);
        verify(agentMapper).updateById(agentCaptor.capture());
        assertEquals("orders 页面副驾驶 Agent", agentCaptor.getValue().getName());
        assertEquals(
                "项目页面副驾驶 Agent，用于嵌入式对话、页面理解和 Workflow 路由。",
                agentCaptor.getValue().getDescription());
        ArgumentCaptor<AgentConfigDraftRequest> configCaptor =
                ArgumentCaptor.forClass(AgentConfigDraftRequest.class);
        verify(configService).saveDraft(eq("agent-1"), configCaptor.capture());
        assertTrue(configCaptor.getValue().systemPrompt().startsWith(
                "你是当前项目的页面副驾驶 Supervisor"));
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

    private RuntimeWorkflowVersionEntity workflowVersion(Long id) {
        RuntimeWorkflowVersionEntity version = new RuntimeWorkflowVersionEntity();
        version.setId(id);
        version.setWorkflowId("wf-chat");
        version.setVersion("v1.0.0");
        return version;
    }

    private Map<String, Object> project() {
        return Map.of("projectId", 7L, "projectCode", "orders", "visibility", "PROJECT");
    }

    private RuntimeWorkflowDefinitionEntity workflow(String id, String workflowKind) {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId(id);
        workflow.setProjectId(7L);
        workflow.setProjectCode("orders");
        workflow.setKeySlug("chat-flow");
        workflow.setWorkflowKind(workflowKind);
        workflow.setStatus("ACTIVE");
        return workflow;
    }

    private BindingView targetPage(String workflowId, String pageKey) {
        return new BindingView(
                1L,
                workflowId,
                7L,
                "orders",
                "PAGE",
                pageKey,
                "TARGET",
                "ACTIVE",
                LocalDateTime.of(2026, 7, 26, 10, 0));
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
