package com.enterprise.ai.runtime.workflow.aicoding;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsQueryService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDebugService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowRevisionConflictException;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionService;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelCatalogClient;
import com.enterprise.ai.runtime.workflow.layout.RuntimeWorkflowCanvasLayoutService;
import com.enterprise.ai.runtime.workflow.mutation.RuntimeWorkflowGraphMutationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeWorkflowAiCodingServiceTest {

    private RuntimeWorkflowDefinitionService workflowService;
    private RuntimeWorkflowReleaseValidationService validationService;
    private RuntimeWorkflowDebugService debugService;
    private RuntimeWorkflowVersionService versionService;
    private RuntimeRunOpsQueryService runOpsQueryService;
    private RuntimeModelCatalogClient modelCatalogClient;
    private RuntimeCapabilityCatalogClient capabilityCatalogClient;
    private RuntimeWorkflowAiCodingService service;

    @BeforeEach
    void setUp() {
        workflowService = mock(RuntimeWorkflowDefinitionService.class);
        validationService = mock(RuntimeWorkflowReleaseValidationService.class);
        debugService = mock(RuntimeWorkflowDebugService.class);
        versionService = mock(RuntimeWorkflowVersionService.class);
        runOpsQueryService = mock(RuntimeRunOpsQueryService.class);
        modelCatalogClient = mock(RuntimeModelCatalogClient.class);
        capabilityCatalogClient = mock(RuntimeCapabilityCatalogClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        service = new RuntimeWorkflowAiCodingService(
                workflowService,
                validationService,
                debugService,
                versionService,
                runOpsQueryService,
                objectMapper,
                new RuntimeWorkflowCanvasLayoutService(objectMapper),
                new RuntimeWorkflowGraphMutationService(objectMapper),
                modelCatalogClient,
                capabilityCatalogClient);
        when(validationService.validate(any(RuntimeWorkflowDefinitionEntity.class)))
                .thenReturn(RuntimeWorkflowReleaseValidationResult.builder().build());
        when(validationService.validateProposed(any(RuntimeWorkflowDefinitionEntity.class), any(GraphSpec.class)))
                .thenReturn(RuntimeWorkflowReleaseValidationResult.builder().build());
        when(modelCatalogClient.listActiveLlms()).thenReturn(List.of(
                Map.of("id", "model-active", "displayName", "Active LLM", "modelType", "LLM", "status", "ACTIVE",
                        "provider", "openai", "modelName", "gpt")));
        when(capabilityCatalogClient.listProjectTools(12L)).thenReturn(List.of(
                Map.of("toolId", 11L, "keySlug", "orders_query", "displayName", "orders_query", "status", "ACTIVE")));
    }

    @Test
    void createPersistsWorkflowAndReturnsAiCodingContext() {
        when(workflowService.create(any(RuntimeWorkflowDefinitionEntity.class))).thenAnswer(inv -> {
            RuntimeWorkflowDefinitionEntity entity = inv.getArgument(0);
            entity.setId("wf-ai-1");
            entity.setUpdatedAt(LocalDateTime.of(2026, 7, 1, 9, 0));
            return entity;
        });

        RuntimeWorkflowAiCodingService.ContextView context = service.createWorkflow(
                new RuntimeWorkflowAiCodingService.CreateRequest(
                        "Order Assistant",
                        "order-assistant",
                        12L,
                        "orders",
                        "Draft from AI Coding",
                        "PAGE_ASSISTANT",
                        "LANGGRAPH4J",
                        "model-1",
                        graph("answer"),
                        Map.of("nodes", List.of()),
                        Map.of("source", "ai-coding"),
                        "initial draft"));

        assertEquals("wf-ai-1", context.workflow().id());
        assertEquals("order-assistant", context.workflow().keySlug());
        assertEquals("PAGE_ASSISTANT", context.workflow().workflowType());
        assertEquals("LANGGRAPH4J", context.workflow().runtimeType());
        assertEquals("answer", context.graphSpec().getEntry());
        assertEquals(true, context.validation().valid());
        assertEquals(2, context.canvas().get("layoutVersion"));
        assertEquals(Set.of("start", "answer", "end"), canvasNodeIds(context.canvas()));
        assertEquals(1, context.availableModels().size());
        assertEquals("model-active", ((Map<?, ?>) context.availableModels().get(0)).get("id"));
        assertEquals(1, context.availableTools().size());
        verify(workflowService).create(any(RuntimeWorkflowDefinitionEntity.class));
    }

    @Test
    void createDefaultsWorkflowTypeToChat() {
        when(workflowService.create(any(RuntimeWorkflowDefinitionEntity.class))).thenAnswer(inv -> {
            RuntimeWorkflowDefinitionEntity entity = inv.getArgument(0);
            entity.setId("wf-chat-1");
            entity.setUpdatedAt(LocalDateTime.of(2026, 7, 1, 9, 0));
            return entity;
        });

        RuntimeWorkflowAiCodingService.ContextView context = service.createWorkflow(
                new RuntimeWorkflowAiCodingService.CreateRequest(
                        "Chat Flow",
                        "chat-flow",
                        12L,
                        "orders",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));

        assertEquals("CHAT", context.workflow().workflowType());
    }

    @Test
    void contextAddsWarningWhenModelCatalogUnavailable() {
        RuntimeWorkflowDefinitionEntity workflow = workflow("wf-ai-1");
        when(workflowService.findById("wf-ai-1")).thenReturn(Optional.of(workflow));
        when(modelCatalogClient.listActiveLlms()).thenThrow(new RuntimeException("model down"));

        RuntimeWorkflowAiCodingService.ContextView context = service.context("wf-ai-1");

        assertTrue(context.availableModels().isEmpty());
        assertTrue(context.warnings().stream().anyMatch(item ->
                item instanceof RuntimeWorkflowAiCodingService.WarningView view
                        && "MODEL_CATALOG_UNAVAILABLE".equals(view.code())));
    }

    @Test
    void contextAddsWarningWhenCapabilityCatalogUnavailable() {
        RuntimeWorkflowDefinitionEntity workflow = workflow("wf-ai-1");
        when(workflowService.findById("wf-ai-1")).thenReturn(Optional.of(workflow));
        when(capabilityCatalogClient.listProjectTools(12L)).thenThrow(new RuntimeException("capability down"));

        RuntimeWorkflowAiCodingService.ContextView context = service.context("wf-ai-1");

        assertTrue(context.availableTools().isEmpty());
        assertTrue(context.warnings().stream().anyMatch(item ->
                item instanceof RuntimeWorkflowAiCodingService.WarningView view
                        && "CAPABILITY_CATALOG_UNAVAILABLE".equals(view.code())));
    }

    @Test
    void patchAppliesGraphOperationsAndSavesWhenNotDryRun() {
        RuntimeWorkflowDefinitionEntity workflow = workflow("wf-ai-1");
        AtomicReference<RuntimeWorkflowDefinitionEntity> updateRef = new AtomicReference<>();
        when(workflowService.findById("wf-ai-1")).thenReturn(Optional.of(workflow));
        when(workflowService.update(
                eq("wf-ai-1"),
                any(RuntimeWorkflowDefinitionEntity.class),
                eq("2026-07-01T09:00"))).thenAnswer(inv -> {
            RuntimeWorkflowDefinitionEntity update = inv.getArgument(1);
            updateRef.set(update);
            workflow.setGraphSpecJson(update.getGraphSpecJson());
            workflow.setCanvasJson(update.getCanvasJson());
            workflow.setUpdatedAt(LocalDateTime.of(2026, 7, 1, 10, 0));
            return workflow;
        });

        RuntimeWorkflowAiCodingService.PatchView view = service.patchWorkflow(
                "wf-ai-1",
                new RuntimeWorkflowAiCodingService.PatchRequest(
                        "2026-07-01T09:00",
                        false,
                        List.of(new RuntimeWorkflowAiCodingService.GraphPatchOperation(
                                RuntimeWorkflowAiCodingService.GraphPatchOperation.Op.ADD_NODE,
                                graphNode("tool", "TOOL", "Tool Step"),
                                null,
                                null,
                                null,
                                null,
                                null)),
                        null,
                        "add tool"));

        assertEquals(true, view.saved());
        assertEquals(List.of("tool"), view.changedNodes());
        assertEquals(true, view.validation().valid());
        assertEquals(2, view.proposedCanvas().get("layoutVersion"));
        assertTrue(canvasNodeIds(view.proposedCanvas()).contains("tool"));
        assertNotNull(updateRef.get().getGraphSpecJson());
        verify(workflowService).assertRevision(workflow, "2026-07-01T09:00");
        verify(workflowService).update(
                eq("wf-ai-1"),
                any(RuntimeWorkflowDefinitionEntity.class),
                eq("2026-07-01T09:00"));
    }

    @Test
    void patchRejectsStaleBaseRevisionBeforeApplyingOperations() {
        RuntimeWorkflowDefinitionEntity workflow = workflow("wf-ai-1");
        when(workflowService.findById("wf-ai-1")).thenReturn(Optional.of(workflow));
        doThrow(new com.enterprise.ai.runtime.workflow.RuntimeWorkflowRevisionConflictException(
                "wf-ai-1", "2026-07-01T08:59", "2026-07-01T09:00"))
                .when(workflowService).assertRevision(workflow, "2026-07-01T08:59");

        assertThrows(
                com.enterprise.ai.runtime.workflow.RuntimeWorkflowRevisionConflictException.class,
                () -> service.patchWorkflow(
                        "wf-ai-1",
                        new RuntimeWorkflowAiCodingService.PatchRequest(
                                "2026-07-01T08:59",
                                false,
                                List.of(),
                                null,
                                "stale patch")));

        verify(workflowService, never()).update(
                eq("wf-ai-1"),
                any(RuntimeWorkflowDefinitionEntity.class),
                eq("2026-07-01T08:59"));
    }

    @Test
    void patchDoesNotPersistInvalidProposal() {
        RuntimeWorkflowDefinitionEntity workflow = workflow("wf-ai-1");
        when(workflowService.findById("wf-ai-1")).thenReturn(Optional.of(workflow));
        when(validationService.validateProposed(eq(workflow), any(GraphSpec.class)))
                .thenReturn(RuntimeWorkflowReleaseValidationResult.builder()
                        .error("ENTRY_MISSING", null, "entry is required")
                        .build());

        RuntimeWorkflowAiCodingService.PatchView view = service.patchWorkflow(
                "wf-ai-1",
                new RuntimeWorkflowAiCodingService.PatchRequest(
                        "2026-07-01T09:00",
                        false,
                        List.of(new RuntimeWorkflowAiCodingService.GraphPatchOperation(
                                RuntimeWorkflowAiCodingService.GraphPatchOperation.Op.DELETE_NODE,
                                null,
                                "answer",
                                null,
                                null,
                                null,
                                null)),
                        null,
                        "remove the only node"));

        assertEquals(false, view.saved());
        assertEquals(false, view.validation().valid());
        assertEquals(List.of("entry is required"), view.errors());
        verify(workflowService, never()).update(
                eq("wf-ai-1"),
                any(RuntimeWorkflowDefinitionEntity.class),
                eq("2026-07-01T09:00"));
    }

    @Test
    void proposedValidationWithoutGraphSpecReturnsMissingInsteadOfValidatingCurrentDraft() {
        RuntimeWorkflowDefinitionEntity workflow = workflow("wf-ai-1");
        when(workflowService.findById("wf-ai-1")).thenReturn(Optional.of(workflow));
        when(validationService.validateProposed(eq(workflow), isNull()))
                .thenReturn(RuntimeWorkflowReleaseValidationResult.builder()
                        .error("GRAPH_SPEC_MISSING", null, "GraphSpec is required")
                        .build());

        RuntimeWorkflowAiCodingService.ValidationView view = service.validateWorkflow(
                "wf-ai-1",
                new RuntimeWorkflowAiCodingService.ValidateRequest(
                        RuntimeWorkflowAiCodingService.ValidateRequest.Mode.PROPOSED,
                        null));

        assertEquals("PROPOSED", view.mode());
        assertEquals(false, view.valid());
        assertEquals("GRAPH_SPEC_MISSING", view.errors().get(0).code());
        verify(validationService).validateProposed(workflow, null);
        verify(validationService, never()).validate(workflow);
    }

    @Test
    void deletingFinishNodeRemovesFinishReferenceAndDanglingCanvasEdge() {
        RuntimeWorkflowDefinitionEntity workflow = workflow("wf-ai-1");
        workflow.setGraphSpecJson("""
                {"nodes":[{"id":"answer","type":"ANSWER"},{"id":"tool","type":"TOOL"}],
                 "edges":[{"id":"answer-tool","from":"answer","to":"tool","condition":"always"}],
                 "entry":"answer","finish":["tool"]}
                """);
        when(workflowService.findById("wf-ai-1")).thenReturn(Optional.of(workflow));

        RuntimeWorkflowAiCodingService.PatchView view = service.patchWorkflow(
                "wf-ai-1",
                new RuntimeWorkflowAiCodingService.PatchRequest(
                        "2026-07-01T09:00",
                        true,
                        List.of(new RuntimeWorkflowAiCodingService.GraphPatchOperation(
                                RuntimeWorkflowAiCodingService.GraphPatchOperation.Op.DELETE_NODE,
                                null,
                                "tool",
                                null,
                                null,
                                null,
                                null)),
                        null,
                        "remove finish node"));

        assertEquals(List.of(), view.proposedGraphSpec().getFinish());
        assertEquals(Set.of("start", "answer", "end"), canvasNodeIds(view.proposedCanvas()));
        assertTrue(canvasEdges(view.proposedCanvas()).stream()
                .noneMatch(edge -> "tool".equals(edge.get("source")) || "tool".equals(edge.get("target"))));
    }

    @Test
    void runDelegatesToRuntimeWorkflowDebugService() {
        RuntimeWorkflowDefinitionEntity workflow = workflow("wf-ai-1");
        when(workflowService.findById("wf-ai-1")).thenReturn(Optional.of(workflow));
        when(debugService.debugRun(any(RuntimeWorkflowDebugService.DebugRunRequest.class)))
                .thenReturn(new RuntimeWorkflowDebugService.DebugRunResult(
                        "run-1",
                        "trace-1",
                        null,
                        "WORKFLOW",
                        true,
                        "SUCCESS",
                        "ok",
                        "answer",
                        List.of(),
                        null,
                        List.of(),
                        Map.of("lastOutput", "ok"),
                        null,
                        null));

        RuntimeWorkflowAiCodingService.RunView view = service.runWorkflow(
                "wf-ai-1",
                new RuntimeWorkflowAiCodingService.RunRequest(
                        Map.of("input", "hello"),
                        "hello",
                        Map.of("source", "ai-coding"),
                        true));

        assertEquals("SUCCESS", view.status());
        assertEquals("ok", view.answer());
        assertEquals("trace-1", view.traceId());
        verify(debugService).debugRun(any(RuntimeWorkflowDebugService.DebugRunRequest.class));
    }

    @Test
    void runReturnsEmptyErrorsWhenDebugFailureHasNoMessage() {
        RuntimeWorkflowDefinitionEntity workflow = workflow("wf-ai-1");
        when(workflowService.findById("wf-ai-1")).thenReturn(Optional.of(workflow));
        when(debugService.debugRun(any(RuntimeWorkflowDebugService.DebugRunRequest.class)))
                .thenReturn(new RuntimeWorkflowDebugService.DebugRunResult(
                        "run-1",
                        "trace-1",
                        null,
                        "WORKFLOW",
                        false,
                        "ERROR",
                        null,
                        null,
                        List.of(),
                        null,
                        List.of(),
                        Map.of(),
                        null,
                        null));

        RuntimeWorkflowAiCodingService.RunView view = service.runWorkflow(
                "wf-ai-1",
                new RuntimeWorkflowAiCodingService.RunRequest(
                        Map.of("input", "hello"),
                        "hello",
                        Map.of(),
                        true));

        assertEquals("ERROR", view.status());
        assertEquals(List.of(), view.errors());
    }

    @Test
    void publishDelegatesToRuntimeWorkflowVersionService() {
        RuntimeWorkflowVersionEntity published = new RuntimeWorkflowVersionEntity();
        published.setId(7L);
        published.setWorkflowId("wf-ai-1");
        published.setVersion("v1.0.0");
        published.setStatus("ACTIVE");
        published.setRolloutPercent(100);
        published.setPublishedBy("codex");
        published.setPublishedAt(LocalDateTime.of(2026, 7, 1, 11, 0));
        when(versionService.publish(
                "wf-ai-1", "v1.0.0", 100, "first", "codex", "2026-07-01T09:00"))
                .thenReturn(published);

        RuntimeWorkflowAiCodingService.PublishView view = service.publishWorkflow(
                "wf-ai-1",
                new RuntimeWorkflowAiCodingService.PublishRequest(
                        "2026-07-01T09:00",
                        "v1.0.0",
                        100,
                        "first",
                        "codex"));

        assertEquals(7L, view.versionId());
        assertEquals("v1.0.0", view.version());
        assertEquals("ACTIVE", view.status());
        verify(versionService).publish(
                "wf-ai-1", "v1.0.0", 100, "first", "codex", "2026-07-01T09:00");
    }

    @Test
    void publishPropagatesRevisionConflictWithoutFallingBackToUnconditionalPublish() {
        RuntimeWorkflowRevisionConflictException conflict = new RuntimeWorkflowRevisionConflictException(
                "wf-ai-1", "2026-07-01T09:00", "2026-07-01T09:01");
        when(versionService.publish(
                "wf-ai-1", "v1.0.0", 100, "first", "codex", "2026-07-01T09:00"))
                .thenThrow(conflict);

        RuntimeWorkflowRevisionConflictException thrown = assertThrows(
                RuntimeWorkflowRevisionConflictException.class,
                () -> service.publishWorkflow(
                        "wf-ai-1",
                        new RuntimeWorkflowAiCodingService.PublishRequest(
                                "2026-07-01T09:00", "v1.0.0", 100, "first", "codex")));

        assertEquals(conflict, thrown);
        assertEquals(
                org.springframework.http.HttpStatus.CONFLICT,
                RuntimeWorkflowRevisionConflictException.class
                        .getAnnotation(org.springframework.web.bind.annotation.ResponseStatus.class)
                        .value());
        verify(versionService).publish(
                "wf-ai-1", "v1.0.0", 100, "first", "codex", "2026-07-01T09:00");
        verify(versionService, never()).publish("wf-ai-1", "v1.0.0", 100, "first", "codex");
    }

    private RuntimeWorkflowDefinitionEntity workflow(String id) {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId(id);
        workflow.setProjectId(12L);
        workflow.setProjectCode("orders");
        workflow.setKeySlug("order-assistant");
        workflow.setName("Order Assistant");
        workflow.setDescription("Draft from AI Coding");
        workflow.setWorkflowType("PAGE_ASSISTANT");
        workflow.setRuntimeType("LANGGRAPH4J");
        workflow.setDefaultModelInstanceId("model-1");
        workflow.setStatus("DRAFT");
        workflow.setGraphSpecJson("{\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}],\"edges\":[],\"entry\":\"answer\"}");
        workflow.setCanvasJson("{\"nodes\":[]}");
        workflow.setUpdatedAt(LocalDateTime.of(2026, 7, 1, 9, 0));
        return workflow;
    }

    private GraphSpec graph(String entry) {
        GraphSpec graph = new GraphSpec();
        graph.setNodes(List.of(graphNode(entry, "ANSWER", "Answer")));
        graph.setEdges(List.of());
        graph.setEntry(entry);
        return graph;
    }

    private GraphSpec.Node graphNode(String id, String type, String name) {
        GraphSpec.Node node = new GraphSpec.Node();
        node.setId(id);
        node.setType(type);
        node.setName(name);
        return node;
    }

    @SuppressWarnings("unchecked")
    private Set<String> canvasNodeIds(Map<String, Object> canvas) {
        return ((List<Map<String, Object>>) canvas.get("nodes")).stream()
                .map(item -> String.valueOf(item.get("id")))
                .collect(java.util.stream.Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> canvasEdges(Map<String, Object> canvas) {
        return (List<Map<String, Object>>) canvas.get("edges");
    }
}
