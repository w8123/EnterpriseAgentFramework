package com.enterprise.ai.runtime.workflow.aicoding;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsQueryService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDebugService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeWorkflowAiCodingServiceTest {

    private RuntimeWorkflowDefinitionService workflowService;
    private RuntimeWorkflowReleaseValidationService validationService;
    private RuntimeWorkflowDebugService debugService;
    private RuntimeWorkflowVersionService versionService;
    private RuntimeRunOpsQueryService runOpsQueryService;
    private RuntimeWorkflowAiCodingService service;

    @BeforeEach
    void setUp() {
        workflowService = mock(RuntimeWorkflowDefinitionService.class);
        validationService = mock(RuntimeWorkflowReleaseValidationService.class);
        debugService = mock(RuntimeWorkflowDebugService.class);
        versionService = mock(RuntimeWorkflowVersionService.class);
        runOpsQueryService = mock(RuntimeRunOpsQueryService.class);
        service = new RuntimeWorkflowAiCodingService(
                workflowService,
                validationService,
                debugService,
                versionService,
                runOpsQueryService,
                new ObjectMapper());
        when(validationService.validate(any(RuntimeWorkflowDefinitionEntity.class)))
                .thenReturn(RuntimeWorkflowReleaseValidationResult.builder().build());
        when(validationService.validateProposed(any(RuntimeWorkflowDefinitionEntity.class), any(GraphSpec.class)))
                .thenReturn(RuntimeWorkflowReleaseValidationResult.builder().build());
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
        verify(workflowService).create(any(RuntimeWorkflowDefinitionEntity.class));
    }

    @Test
    void patchAppliesGraphOperationsAndSavesWhenNotDryRun() {
        RuntimeWorkflowDefinitionEntity workflow = workflow("wf-ai-1");
        AtomicReference<RuntimeWorkflowDefinitionEntity> updateRef = new AtomicReference<>();
        when(workflowService.findById("wf-ai-1")).thenReturn(Optional.of(workflow));
        when(workflowService.update(eq("wf-ai-1"), any(RuntimeWorkflowDefinitionEntity.class))).thenAnswer(inv -> {
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
                        null,
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
        assertNotNull(updateRef.get().getGraphSpecJson());
        verify(workflowService).update(eq("wf-ai-1"), any(RuntimeWorkflowDefinitionEntity.class));
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
        when(versionService.publish("wf-ai-1", "v1.0.0", 100, "first", "codex"))
                .thenReturn(published);

        RuntimeWorkflowAiCodingService.PublishView view = service.publishWorkflow(
                "wf-ai-1",
                new RuntimeWorkflowAiCodingService.PublishRequest(
                        "v1.0.0",
                        100,
                        "first",
                        "codex"));

        assertEquals(7L, view.versionId());
        assertEquals("v1.0.0", view.version());
        assertEquals("ACTIVE", view.status());
        verify(versionService).publish("wf-ai-1", "v1.0.0", 100, "first", "codex");
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
}
