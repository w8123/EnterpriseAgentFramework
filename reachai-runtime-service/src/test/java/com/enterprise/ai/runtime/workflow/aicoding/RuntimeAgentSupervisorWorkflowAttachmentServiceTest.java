package com.enterprise.ai.runtime.workflow.aicoding;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowAgentAttachmentPort;

import com.enterprise.ai.runtime.internal.RuntimeAgentSupervisorWorkflowAttachmentService;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigVersionView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowAttachmentService;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowResourceBindingService.BindingView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Workflow validation and public mapping; Agent mutations use real persistence tests. */
class RuntimeAgentSupervisorWorkflowAttachmentServiceTest {
    private final RuntimeCapabilityCatalogClient projects = mock(RuntimeCapabilityCatalogClient.class);
    private final RuntimeWorkflowDefinitionService workflows = mock(RuntimeWorkflowDefinitionService.class);
    private final RuntimeAgentWorkflowAttachmentService agents = mock(RuntimeAgentWorkflowAttachmentService.class);
    private final RuntimeAgentSupervisorWorkflowAttachmentService service =
            new RuntimeAgentSupervisorWorkflowAttachmentService(projects, new com.enterprise.ai.runtime.workflow.RuntimeWorkflowManagementService(workflows, null, null), agents, new ObjectMapper());

    @BeforeEach
    void setUp() {
        when(projects.getProjectById(7L)).thenReturn(Map.of("projectId", 7L, "projectCode", "orders"));
        when(workflows.findById("wf-chat")).thenReturn(Optional.of(workflow("wf-chat", "GENERAL")));
        when(agents.attach(any(), any())).thenReturn(new RuntimeAgentWorkflowAttachmentService.Result(
                "agent-1", "orders-page-copilot", config(), false));
    }

    @Test
    void springBindsWorkflowAttachmentPortToTheActualCoordinator() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(RuntimeCapabilityCatalogClient.class, () -> projects);
            context.registerBean(com.enterprise.ai.runtime.workflow.RuntimeWorkflowManagementService.class,
                    () -> new com.enterprise.ai.runtime.workflow.RuntimeWorkflowManagementService(workflows, null, null));
            context.registerBean(RuntimeAgentWorkflowAttachmentService.class, () -> agents);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(RuntimeAgentSupervisorWorkflowAttachmentService.class);
            context.refresh();
            var port = context.getBean(RuntimeWorkflowAgentAttachmentPort.class);
            var result = port.attach(7L, new RuntimeWorkflowAgentAttachmentPort.AttachRequest(
                    "wf-chat", null, "orders-page-copilot", "model-1", "tester"));
            assertEquals("agent-1", result.agent().id());
            assertEquals("wf-chat", result.workflow().id());
            assertEquals("ACTIVE", result.activeConfig().status());
            verify(agents).attach(any(), any());
        }
    }

    @Test
    void attachesGeneralWorkflowViaAgentKeySlugAndForwardsToolOverrides() {
        var result = service.attach(7L, new RuntimeWorkflowAgentAttachmentPort.AttachRequest(
                "wf-chat", null, "orders-page-copilot", "model-1", "Cursor", "disable_team", "停用班组",
                Map.of("type", "object"), null, "WRITE", "workflow:disable-team", false, 5));
        var command = command();
        assertEquals("orders-page-copilot", command.agentKeySlug());
        assertEquals("disable_team", command.tool().toolName());
        assertEquals("WRITE", command.tool().riskLevel());
        assertFalse(command.tool().readOnly());
        assertTrue(command.tool().inputSchemaOverrideJson().contains("\"type\":\"object\""));
        assertEquals(5, command.tool().priority());
        assertFalse(command.preserveToolConfiguration());
        assertTrue(result.created());
        assertEquals("workflow-tool-attachment.v1", result.schema());
    }

    @Test
    void normalizesWorkflowKeySlugWhenDefaultingToolName() {
        service.attach(7L, request());
        var command = command();
        assertEquals("chat_flow", command.tool().toolName());
        assertTrue(command.preserveToolConfiguration());
        assertTrue(command.tool().readOnly());
    }

    @Test
    void explicitlyReplacesAnAttachedWorkflowAndReportsTheReplacedId() {
        when(workflows.findById("old-flow")).thenReturn(Optional.of(workflow("old-flow", "GENERAL")));
        var result = service.attach(7L, replacement());
        var command = command();
        assertEquals("old-flow", command.replaceWorkflowId());
        assertFalse(command.preserveToolConfiguration());
        assertEquals("old-flow", result.replacedWorkflowId());
    }

    @Test
    void rejectsCrossPageReplacementBeforeInvokingAgentPublication() {
        when(workflows.findById("wf-chat")).thenReturn(Optional.of(workflow("wf-chat", "PAGE_ASSISTANT")));
        when(workflows.findById("old-flow")).thenReturn(Optional.of(workflow("old-flow", "PAGE_ASSISTANT")));
        when(workflows.listResourceBindings("wf-chat")).thenReturn(List.of(binding("wf-chat", "page-a")));
        when(workflows.listResourceBindings("old-flow")).thenReturn(List.of(binding("old-flow", "page-b")));
        assertEquals("WORKFLOW_REPLACEMENT_INVALID", assertThrows(AiCodingAttachmentException.class,
                () -> service.attach(7L, replacement())).code());
        verifyNoInteractions(agents);
    }

    @Test
    void exposesTheOwnerReuseDecisionWithoutSavingADraft() {
        when(agents.attach(any(), any())).thenReturn(new RuntimeAgentWorkflowAttachmentService.Result(
                "agent-1", "orders-page-copilot", config(), true));
        var result = service.attach(7L, request());
        assertTrue(result.reused());
        assertFalse(result.created());
    }

    @Test
    void rejectsGeneralOnPageAssistantOnlyPath() {
        var error = assertThrows(AiCodingAttachmentException.class, () -> service.attachPageAssistantOnly("wf-chat",
                new RuntimeWorkflowAgentAttachmentPort.PageAssistantAttachRequest(7L, null, "agent-1", "model-1", "Cursor")));
        assertEquals("WORKFLOW_KIND_NOT_SUPPORTED", error.code());
        verifyNoInteractions(agents);
    }

    @Test
    void preservesTheModelCatalogErrorCode() {
        when(agents.attach(any(), any())).thenThrow(new RuntimeAgentWorkflowAttachmentService.Failure("NO_ACTIVE_LLM", "missing model"));
        assertEquals("NO_ACTIVE_LLM", assertThrows(AiCodingAttachmentException.class,
                () -> service.attach(7L, request())).code());
    }

    @Test
    void preservesDependencyFailureStatus() {
        when(agents.attach(any(), any())).thenThrow(new RuntimeAgentWorkflowAttachmentService.Failure(
                "RUNTIME_DEPENDENCY_UNAVAILABLE", "model unavailable", HttpStatus.BAD_GATEWAY));
        var error = assertThrows(AiCodingAttachmentException.class, () -> service.attach(7L, request()));
        assertEquals("RUNTIME_DEPENDENCY_UNAVAILABLE", error.code());
        assertEquals(HttpStatus.BAD_GATEWAY, error.status());
    }

    @Test
    void rejectsCrossProjectWorkflowBeforeAgentPublication() {
        var foreign = workflow("wf-chat", "GENERAL"); foreign.setProjectId(99L);
        when(workflows.findById("wf-chat")).thenReturn(Optional.of(foreign));
        assertThrows(AiCodingAttachmentException.class, () -> service.attach(7L, request()));
        verifyNoInteractions(agents);
    }

    @Test
    void rejectsConflictingAgentIdentifiers() {
        assertThrows(AiCodingAttachmentException.class, () -> service.attach(7L,
                new RuntimeWorkflowAgentAttachmentPort.AttachRequest("wf-chat", "agent-1", "slug", "model-1", "Cursor")));
        verifyNoInteractions(agents);
    }

    private RuntimeAgentWorkflowAttachmentService.Command command() {
        var captor = ArgumentCaptor.forClass(RuntimeAgentWorkflowAttachmentService.Command.class);
        verify(agents).attach(any(), captor.capture());
        return captor.getValue();
    }

    private RuntimeWorkflowAgentAttachmentPort.AttachRequest request() {
        return new RuntimeWorkflowAgentAttachmentPort.AttachRequest("wf-chat", null, "orders-page-copilot", "model-1", "Cursor");
    }

    private RuntimeWorkflowAgentAttachmentPort.AttachRequest replacement() {
        return new RuntimeWorkflowAgentAttachmentPort.AttachRequest("wf-chat", "agent-1", null,
                "model-1", "Cursor", null, null, null, null, null, null, null, null, "old-flow");
    }

    private RuntimeWorkflowDefinitionEntity workflow(String id, String kind) {
        var workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId(id); workflow.setKeySlug("chat-flow"); workflow.setWorkflowKind(kind);
        workflow.setProjectId(7L); workflow.setProjectCode("orders"); workflow.setStatus("ACTIVE");
        return workflow;
    }

    private BindingView binding(String id, String page) {
        return new BindingView(1L, id, 7L, "orders", "PAGE", page, "TARGET", "ACTIVE", null);
    }

    private AgentConfigVersionView config() {
        LocalDateTime now = LocalDateTime.now();
        return new AgentConfigVersionView(21L, "agent-1", 2, "ACTIVE", "AGENTSCOPE", "prompt", "model-1",
                6, 4, 2, 300_000, 180_000, 30_000, true, "DEV_ALLOW_ALL", "ALLOW_LIST", null,
                "Cursor", now, now, now, List.of());
    }
}
