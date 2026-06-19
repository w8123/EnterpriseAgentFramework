package com.enterprise.ai.agent.workflow.aicoding;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.client.ModelServiceClient;
import com.enterprise.ai.agent.governance.GuardDecisionLogService;
import com.enterprise.ai.agent.identity.PageActionRegistryMapper;
import com.enterprise.ai.agent.platform.auth.AiCodingKeyContext;
import com.enterprise.ai.agent.platform.auth.PlatformAuthContext;
import com.enterprise.ai.agent.runops.RunOpsService;
import com.enterprise.ai.agent.runtime.LangGraph4jRuntimeAdapter;
import com.enterprise.ai.agent.scan.ScanProjectEntity;
import com.enterprise.ai.agent.scan.ScanProjectService;
import com.enterprise.ai.agent.workflow.AgentWorkflowBindingEntity;
import com.enterprise.ai.agent.workflow.AgentWorkflowBindingService;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionEntity;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionService;
import com.enterprise.ai.agent.workflow.WorkflowReleaseValidationResult;
import com.enterprise.ai.agent.workflow.WorkflowReleaseValidationService;
import com.enterprise.ai.agent.workflow.WorkflowRuntimeGraphAdapter;
import com.enterprise.ai.agent.workflow.WorkflowVersionEntity;
import com.enterprise.ai.agent.workflow.WorkflowVersionService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowAiCodingServiceTest {

    private static final String TEST_AI_CODING_KEY = "rac_test";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearContexts() {
        PlatformAuthContext.clear();
        AiCodingKeyContext.clear();
    }

    @Test
    void contextReturnsWorkflowGraphSpecNodeTypesAndValidation() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        WorkflowReleaseValidationService validationService = mock(WorkflowReleaseValidationService.class);
        AgentWorkflowBindingService bindingService = mock(AgentWorkflowBindingService.class);
        WorkflowAiCodingService service = newService(workflowService, validationService, bindingService, null);

        WorkflowDefinitionEntity workflow = sampleWorkflow();
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(validationService.validate(workflow)).thenReturn(WorkflowReleaseValidationResult.ok());
        when(bindingService.listByWorkflowId("wf-1")).thenReturn(List.of());

        WorkflowAiCodingContextResponse context = service.getContext("wf-1");

        assertEquals("wf-1", context.getWorkflow().getId());
        assertEquals("orders-page", context.getWorkflow().getKeySlug());
        assertNotNull(context.getGraphSpec());
        assertEquals("start", context.getGraphSpec().getEntry());
        assertFalse(context.getNodeTypes().isEmpty());
        assertTrue(context.getValidation().valid());
        assertNotNull(context.getRuntimeHints());
        assertNotNull(context.getAvailableModels());
        assertNotNull(context.getAvailableTools());
    }

    @Test
    void contextIncludesAvailableModelsFromModelService() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        WorkflowReleaseValidationService validationService = mock(WorkflowReleaseValidationService.class);
        ModelServiceClient modelServiceClient = mock(ModelServiceClient.class);
        ToolDefinitionService toolDefinitionService = mock(ToolDefinitionService.class);
        WorkflowAiCodingService service = newService(
                workflowService,
                validationService,
                mock(AgentWorkflowBindingService.class),
                null,
                mock(WorkflowVersionService.class),
                mock(RunOpsService.class),
                modelServiceClient,
                toolDefinitionService);

        WorkflowDefinitionEntity workflow = sampleWorkflow();
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(validationService.validate(workflow)).thenReturn(WorkflowReleaseValidationResult.ok());
        when(toolDefinitionService.listByProjectId(7L)).thenReturn(List.of());
        when(modelServiceClient.listModelInstances("LLM", null, null)).thenReturn(
                new ModelServiceClient.ModelInstanceListResult(
                        200,
                        "ok",
                        List.of(new ModelServiceClient.ModelInstanceData(
                                "llm-main",
                                "Main LLM",
                                "openai",
                                "LLM",
                                "gpt-4o",
                                null,
                                null,
                                null,
                                null,
                                "ACTIVE",
                                null))));

        WorkflowAiCodingContextResponse context = service.getContext("wf-1");

        assertEquals(1, context.getAvailableModels().size());
        assertEquals("llm-main", context.getAvailableModels().get(0).getId());
    }

    @Test
    void contextToleratesModelServiceFailure() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        WorkflowReleaseValidationService validationService = mock(WorkflowReleaseValidationService.class);
        ModelServiceClient modelServiceClient = mock(ModelServiceClient.class);
        ToolDefinitionService toolDefinitionService = mock(ToolDefinitionService.class);
        WorkflowAiCodingService service = newService(
                workflowService,
                validationService,
                mock(AgentWorkflowBindingService.class),
                null,
                mock(WorkflowVersionService.class),
                mock(RunOpsService.class),
                modelServiceClient,
                toolDefinitionService);

        WorkflowDefinitionEntity workflow = sampleWorkflow();
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(validationService.validate(workflow)).thenReturn(WorkflowReleaseValidationResult.ok());
        when(toolDefinitionService.listByProjectId(7L)).thenReturn(List.of());
        when(modelServiceClient.listModelInstances("LLM", null, null))
                .thenThrow(new RuntimeException("model service down"));

        WorkflowAiCodingContextResponse context = service.getContext("wf-1");

        assertTrue(context.getAvailableModels().isEmpty());
        assertTrue(context.getWarnings().stream().anyMatch(w -> w.contains("availableModels")));
    }

    @Test
    void contextIncludesAvailableToolsForProject() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        WorkflowReleaseValidationService validationService = mock(WorkflowReleaseValidationService.class);
        ModelServiceClient modelServiceClient = mock(ModelServiceClient.class);
        ToolDefinitionService toolDefinitionService = mock(ToolDefinitionService.class);
        WorkflowAiCodingService service = newService(
                workflowService,
                validationService,
                mock(AgentWorkflowBindingService.class),
                null,
                mock(WorkflowVersionService.class),
                mock(RunOpsService.class),
                modelServiceClient,
                toolDefinitionService);

        WorkflowDefinitionEntity workflow = sampleWorkflow();
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(validationService.validate(workflow)).thenReturn(WorkflowReleaseValidationResult.ok());
        when(modelServiceClient.listModelInstances("LLM", null, null)).thenReturn(
                new ModelServiceClient.ModelInstanceListResult(200, "ok", List.of()));
        ToolDefinitionEntity tool = new ToolDefinitionEntity();
        tool.setName("queryOrders");
        tool.setKind("TOOL");
        tool.setDescription("Query orders");
        tool.setEnabled(true);
        tool.setQualifiedName("orders:queryOrders");
        when(toolDefinitionService.listByProjectId(7L)).thenReturn(List.of(tool));

        WorkflowAiCodingContextResponse context = service.getContext("wf-1");

        assertEquals(1, context.getAvailableTools().size());
        assertEquals("queryOrders", context.getAvailableTools().get(0).getName());
    }

    @Test
    void dryRunPatchDoesNotSaveDatabase() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        WorkflowReleaseValidationService validationService = mock(WorkflowReleaseValidationService.class);
        WorkflowAiCodingService service = newService(workflowService, validationService, mock(AgentWorkflowBindingService.class), null);

        WorkflowDefinitionEntity workflow = sampleWorkflow();
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(validationService.validateProposed(eq(workflow), any(GraphSpec.class)))
                .thenReturn(WorkflowReleaseValidationResult.ok());

        WorkflowAiCodingPatchResponse response = service.patch("wf-1", WorkflowAiCodingPatchRequest.builder()
                .dryRun(true)
                .operations(List.of(
                        WorkflowGraphPatchOperation.builder()
                                .op(WorkflowGraphPatchOperation.OperationOp.ADD_NODE)
                                .node(GraphSpec.Node.builder()
                                        .id("answer")
                                        .type("ANSWER")
                                        .name("Answer")
                                        .build())
                                .build()
                ))
                .build());

        assertTrue(response.isDryRun());
        assertFalse(response.isSaved());
        assertEquals(2, response.getProposedGraphSpec().getNodes().size());
        verify(workflowService, never()).update(any(), any());
    }

    @Test
    void patchWithLlmAndAnswerNodesReturnsValidation() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        WorkflowReleaseValidationService validationService = mock(WorkflowReleaseValidationService.class);
        WorkflowAiCodingService service = newService(workflowService, validationService, mock(AgentWorkflowBindingService.class), null);

        WorkflowDefinitionEntity workflow = sampleWorkflow();
        workflow.setDefaultModelInstanceId("llm-main");
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(validationService.validateProposed(eq(workflow), any(GraphSpec.class)))
                .thenAnswer(invocation -> {
                    GraphSpec graph = invocation.getArgument(1);
                    WorkflowReleaseValidationResult.Builder report = WorkflowReleaseValidationResult.builder();
                    if (graph.getNodes().stream().anyMatch(node -> "LLM".equals(node.getType()))) {
                        return report.build();
                    }
                    report.error("GRAPH_NODE_TYPE_UNSUPPORTED", "llm", "missing llm");
                    return report.build();
                });

        WorkflowAiCodingPatchResponse response = service.patch("wf-1", WorkflowAiCodingPatchRequest.builder()
                .dryRun(true)
                .operations(List.of(
                        WorkflowGraphPatchOperation.builder()
                                .op(WorkflowGraphPatchOperation.OperationOp.ADD_NODE)
                                .node(GraphSpec.Node.builder().id("llm").type("LLM").config(Map.of("prompt", "hi")).build())
                                .build(),
                        WorkflowGraphPatchOperation.builder()
                                .op(WorkflowGraphPatchOperation.OperationOp.ADD_NODE)
                                .node(GraphSpec.Node.builder().id("answer").type("ANSWER").build())
                                .build(),
                        WorkflowGraphPatchOperation.builder()
                                .op(WorkflowGraphPatchOperation.OperationOp.ADD_EDGE)
                                .edge(GraphSpec.Edge.builder().id("e1").from("start").to("llm").build())
                                .build(),
                        WorkflowGraphPatchOperation.builder()
                                .op(WorkflowGraphPatchOperation.OperationOp.ADD_EDGE)
                                .edge(GraphSpec.Edge.builder().id("e2").from("llm").to("answer").build())
                                .build(),
                        WorkflowGraphPatchOperation.builder()
                                .op(WorkflowGraphPatchOperation.OperationOp.SET_ENTRY)
                                .entry("start")
                                .build()
                ))
                .build());

        assertTrue(response.getValidation().valid());
        assertTrue(response.getProposedGraphSpec().getNodes().stream()
                .anyMatch(node -> "LLM".equals(node.getType())));
        assertTrue(response.getProposedGraphSpec().getNodes().stream()
                .anyMatch(node -> "ANSWER".equals(node.getType())));
    }

    @Test
    void patchDefaultsToDryRunWhenFieldMissing() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        WorkflowReleaseValidationService validationService = mock(WorkflowReleaseValidationService.class);
        WorkflowAiCodingService service = newService(workflowService, validationService, mock(AgentWorkflowBindingService.class), null);

        WorkflowDefinitionEntity workflow = sampleWorkflow();
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(validationService.validateProposed(eq(workflow), any(GraphSpec.class)))
                .thenReturn(WorkflowReleaseValidationResult.ok());

        WorkflowAiCodingPatchResponse response = service.patch("wf-1", WorkflowAiCodingPatchRequest.builder()
                .operations(List.of(
                        WorkflowGraphPatchOperation.builder()
                                .op(WorkflowGraphPatchOperation.OperationOp.ADD_NODE)
                                .node(GraphSpec.Node.builder().id("answer").type("ANSWER").build())
                                .build()
                ))
                .build());

        assertTrue(response.isDryRun());
        verify(workflowService, never()).update(any(), any());
    }

    @Test
    void patchSaveRejectedWhenValidationFails() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        WorkflowReleaseValidationService validationService = mock(WorkflowReleaseValidationService.class);
        WorkflowAiCodingService service = newService(workflowService, validationService, mock(AgentWorkflowBindingService.class), null);

        WorkflowDefinitionEntity workflow = sampleWorkflow();
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(validationService.validateProposed(eq(workflow), any(GraphSpec.class)))
                .thenReturn(WorkflowReleaseValidationResult.builder()
                        .error("GRAPH_ENTRY_MISSING", null, "entry required")
                        .build());

        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.patch("wf-1", WorkflowAiCodingPatchRequest.builder()
                        .dryRun(false)
                        .operations(List.of(
                                WorkflowGraphPatchOperation.builder()
                                        .op(WorkflowGraphPatchOperation.OperationOp.ADD_NODE)
                                        .node(GraphSpec.Node.builder().id("answer").type("ANSWER").build())
                                        .build()
                        ))
                        .build()));

        assertTrue(error.getMessage().contains("patch validation failed"));
        verify(workflowService, never()).update(any(), any());
    }

    @Test
    void runSkipsSideEffectNodesWithoutConfirmation() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        LangGraph4jRuntimeAdapter runtimeAdapter = mock(LangGraph4jRuntimeAdapter.class);
        WorkflowAiCodingService service = newService(
                workflowService,
                mock(WorkflowReleaseValidationService.class),
                mock(AgentWorkflowBindingService.class),
                runtimeAdapter);

        WorkflowDefinitionEntity workflow = sampleWorkflow();
        workflow.setGraphSpecJson(objectMapper.writeValueAsString(GraphSpec.builder()
                .entry("http")
                .node(GraphSpec.Node.builder().id("http").type("HTTP_REQUEST").config(Map.of("url", "https://example.com")).build())
                .build()));
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));

        WorkflowAiCodingRunResponse response = service.run("wf-1", WorkflowAiCodingRunRequest.builder()
                .message("test")
                .build());

        assertEquals("SKIPPED", response.getStatus());
        assertTrue(response.getWarnings().stream().anyMatch(item -> item.contains("confirmSideEffects")));
        verify(runtimeAdapter, never()).debugRun(any(), any(), any(), any(), any());
    }

    @Test
    void validateCurrentModeRejectsGraphSpecPayload() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        WorkflowAiCodingService service = newService(workflowService, mock(WorkflowReleaseValidationService.class), mock(AgentWorkflowBindingService.class), null);
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(sampleWorkflow()));

        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.validate("wf-1", WorkflowAiCodingValidateRequest.builder()
                        .mode(WorkflowAiCodingValidateRequest.ValidateMode.CURRENT)
                        .graphSpec(GraphSpec.builder().entry("start").build())
                        .build()));

        assertTrue(error.getMessage().contains("mode=PROPOSED"));
    }

    @Test
    void missingAiCodingKeyIsUnauthorized() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        WorkflowAiCodingService service = newService(
                workflowService,
                mock(WorkflowReleaseValidationService.class),
                mock(AgentWorkflowBindingService.class),
                null);
        AiCodingKeyContext.clear();
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(sampleWorkflow()));

        assertThrows(WorkflowAiCodingUnauthorizedException.class, () -> service.getContext("wf-1"));
    }

    @Test
    void invalidAiCodingKeyIsForbidden() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        ScanProjectService scanProjectService = mock(ScanProjectService.class);
        when(scanProjectService.matchesAiCodingAccessKey(7L, "wrong-key")).thenReturn(false);
        WorkflowAiCodingService service = newService(
                workflowService,
                mock(WorkflowReleaseValidationService.class),
                mock(AgentWorkflowBindingService.class),
                null,
                mock(WorkflowVersionService.class),
                mock(RunOpsService.class),
                mock(ModelServiceClient.class),
                mock(ToolDefinitionService.class),
                scanProjectService);
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(sampleWorkflow()));
        AiCodingKeyContext.set("wrong-key");

        assertThrows(WorkflowAccessDeniedException.class, () -> service.getContext("wf-1"));
    }

    @Test
    void validAiCodingKeyAllowsWorkflowAccess() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        WorkflowReleaseValidationService validationService = mock(WorkflowReleaseValidationService.class);
        WorkflowAiCodingService service = newService(workflowService, validationService, mock(AgentWorkflowBindingService.class), null);

        WorkflowDefinitionEntity workflow = sampleWorkflow();
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(validationService.validate(workflow)).thenReturn(WorkflowReleaseValidationResult.ok());
        AiCodingKeyContext.set(TEST_AI_CODING_KEY);

        WorkflowAiCodingContextResponse context = service.getContext("wf-1");

        assertEquals("wf-1", context.getWorkflow().getId());
    }

    @Test
    void patchSaveUpdatesGraphSpecJsonWhenDryRunFalse() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        WorkflowReleaseValidationService validationService = mock(WorkflowReleaseValidationService.class);
        WorkflowAiCodingService service = newService(workflowService, validationService, mock(AgentWorkflowBindingService.class), null);

        WorkflowDefinitionEntity workflow = sampleWorkflow();
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(validationService.validateProposed(eq(workflow), any(GraphSpec.class)))
                .thenReturn(WorkflowReleaseValidationResult.ok());
        when(workflowService.update(eq("wf-1"), any(WorkflowDefinitionEntity.class)))
                .thenAnswer(invocation -> {
                    WorkflowDefinitionEntity update = invocation.getArgument(1);
                    workflow.setGraphSpecJson(update.getGraphSpecJson());
                    workflow.setCanvasJson(update.getCanvasJson());
                    return workflow;
                });

        WorkflowAiCodingPatchResponse response = service.patch("wf-1", WorkflowAiCodingPatchRequest.builder()
                .dryRun(false)
                .operations(List.of(
                        WorkflowGraphPatchOperation.builder()
                                .op(WorkflowGraphPatchOperation.OperationOp.ADD_NODE)
                                .node(GraphSpec.Node.builder().id("answer").type("ANSWER").build())
                                .build()
                ))
                .build());

        assertFalse(response.isDryRun());
        assertTrue(response.isSaved());
        ArgumentCaptor<WorkflowDefinitionEntity> captor = ArgumentCaptor.forClass(WorkflowDefinitionEntity.class);
        verify(workflowService).update(eq("wf-1"), captor.capture());
        assertTrue(captor.getValue().getGraphSpecJson().contains("answer"));
    }

    @Test
    void runWithoutBridgeContextReturnsSkippedForPageAssistantGraph() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        LangGraph4jRuntimeAdapter runtimeAdapter = mock(LangGraph4jRuntimeAdapter.class);
        WorkflowAiCodingService service = newService(
                workflowService,
                mock(WorkflowReleaseValidationService.class),
                mock(AgentWorkflowBindingService.class),
                runtimeAdapter);

        WorkflowDefinitionEntity workflow = pageAssistantWorkflow();
        when(workflowService.findById("wf-page")).thenReturn(Optional.of(workflow));

        WorkflowAiCodingRunResponse response = service.run("wf-page", WorkflowAiCodingRunRequest.builder()
                .message("open detail")
                .build());

        assertEquals("SKIPPED", response.getStatus());
        assertFalse(response.getWarnings().isEmpty());
        verify(runtimeAdapter, never()).debugRun(any(), any(), any(), any(), any());
    }

    @Test
    void runDryRunReturnsWarnWithoutExecutingRuntime() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        LangGraph4jRuntimeAdapter runtimeAdapter = mock(LangGraph4jRuntimeAdapter.class);
        WorkflowAiCodingService service = newService(
                workflowService,
                mock(WorkflowReleaseValidationService.class),
                mock(AgentWorkflowBindingService.class),
                runtimeAdapter);

        WorkflowDefinitionEntity workflow = pageAssistantWorkflow();
        when(workflowService.findById("wf-page")).thenReturn(Optional.of(workflow));

        WorkflowAiCodingRunResponse response = service.run("wf-page", WorkflowAiCodingRunRequest.builder()
                .dryRun(true)
                .message("test")
                .build());

        assertEquals("DRY_RUN", response.getStatus());
        assertTrue(response.getWarnings().stream().anyMatch(item -> item.contains("dryRun=true")));
        verify(runtimeAdapter, never()).debugRun(any(), any(), any(), any(), any());
    }

    @Test
    void runAuditUsesReturnedTraceIdForRunOpsLookup() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        LangGraph4jRuntimeAdapter runtimeAdapter = mock(LangGraph4jRuntimeAdapter.class);
        GuardDecisionLogService guardDecisionLogService = mock(GuardDecisionLogService.class);
        ScanProjectService scanProjectService = mock(ScanProjectService.class);
        when(scanProjectService.matchesAiCodingAccessKey(7L, TEST_AI_CODING_KEY)).thenReturn(true);
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        when(scanProjectService.getById(7L)).thenReturn(project);
        AiCodingKeyContext.set(TEST_AI_CODING_KEY);
        WorkflowAiCodingService service = new WorkflowAiCodingService(
                workflowService,
                mock(WorkflowVersionService.class),
                mock(WorkflowReleaseValidationService.class),
                new WorkflowGraphPatchService(objectMapper),
                new WorkflowRuntimeGraphAdapter(objectMapper),
                runtimeAdapter,
                mock(AgentWorkflowBindingService.class),
                mock(PageActionRegistryMapper.class),
                new WorkflowAiCodingAuthService(scanProjectService),
                mock(RunOpsService.class),
                mock(ModelServiceClient.class),
                mock(ToolDefinitionService.class),
                guardDecisionLogService,
                objectMapper);

        WorkflowDefinitionEntity workflow = sampleWorkflow();
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(runtimeAdapter.debugRun(any(), any(), any(), any(), any()))
                .thenReturn(LangGraph4jRuntimeAdapter.WorkflowDebugRunResult.builder()
                        .traceId("trace-run")
                        .runId("run-1")
                        .status("SUCCESS")
                        .success(true)
                        .answer("ok")
                        .steps(List.of())
                        .build());

        WorkflowAiCodingRunResponse response = service.run("wf-1", WorkflowAiCodingRunRequest.builder()
                .message("hello")
                .build());

        assertEquals("SUCCESS", response.getStatus());
        verify(guardDecisionLogService).record(
                eq("trace-run"),
                eq("WORKFLOW_AI_CODING"),
                eq("WORKFLOW"),
                eq("wf-1"),
                eq("DEBUG_RUN"),
                eq(null),
                any());
    }

    @Test
    void validateProposedGraphSpecUsesReleaseValidationService() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        WorkflowReleaseValidationService validationService = mock(WorkflowReleaseValidationService.class);
        WorkflowAiCodingService service = newService(workflowService, validationService, mock(AgentWorkflowBindingService.class), null);

        WorkflowDefinitionEntity workflow = sampleWorkflow();
        GraphSpec proposed = GraphSpec.builder()
                .entry("start")
                .node(GraphSpec.Node.builder().id("start").type("USER_INPUT").build())
                .build();
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(validationService.validateProposed(workflow, proposed)).thenReturn(WorkflowReleaseValidationResult.ok());

        WorkflowAiCodingValidateResponse response = service.validate("wf-1", WorkflowAiCodingValidateRequest.builder()
                .mode(WorkflowAiCodingValidateRequest.ValidateMode.PROPOSED)
                .graphSpec(proposed)
                .build());

        assertEquals(WorkflowAiCodingValidateResponse.ValidateMode.PROPOSED, response.getMode());
        assertTrue(response.getValidation().valid());
    }

    @Test
    void pageAssistantContextIncludesExtraJsonAndBindings() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        WorkflowReleaseValidationService validationService = mock(WorkflowReleaseValidationService.class);
        AgentWorkflowBindingService bindingService = mock(AgentWorkflowBindingService.class);
        PageActionRegistryMapper pageActionRegistryMapper = mock(PageActionRegistryMapper.class);
        ScanProjectService scanProjectService = mock(ScanProjectService.class);
        when(scanProjectService.matchesAiCodingAccessKey(7L, TEST_AI_CODING_KEY)).thenReturn(true);
        ScanProjectEntity scanProject = new ScanProjectEntity();
        scanProject.setId(7L);
        scanProject.setProjectCode("orders");
        when(scanProjectService.getById(7L)).thenReturn(scanProject);
        AiCodingKeyContext.set(TEST_AI_CODING_KEY);
        WorkflowAiCodingService service = new WorkflowAiCodingService(
                workflowService,
                mock(WorkflowVersionService.class),
                validationService,
                new WorkflowGraphPatchService(objectMapper),
                new WorkflowRuntimeGraphAdapter(objectMapper),
                mock(LangGraph4jRuntimeAdapter.class),
                bindingService,
                pageActionRegistryMapper,
                new WorkflowAiCodingAuthService(scanProjectService),
                mock(RunOpsService.class),
                mock(ModelServiceClient.class),
                mock(ToolDefinitionService.class),
                mock(GuardDecisionLogService.class),
                objectMapper);

        WorkflowDefinitionEntity workflow = pageAssistantWorkflow();
        when(workflowService.findById("wf-page")).thenReturn(Optional.of(workflow));
        when(validationService.validate(workflow)).thenReturn(WorkflowReleaseValidationResult.ok());
        AgentWorkflowBindingEntity binding = new AgentWorkflowBindingEntity();
        binding.setId(9L);
        binding.setAgentId("agent-1");
        binding.setBindingType("PAGE");
        binding.setPageKey("orders.list");
        binding.setRoutePattern("/orders");
        binding.setActionKey("openDetail");
        binding.setEnabled(true);
        when(bindingService.listByWorkflowId("wf-page")).thenReturn(List.of(binding));
        when(pageActionRegistryMapper.selectList(any())).thenReturn(List.of());

        WorkflowAiCodingContextResponse context = service.getContext("wf-page");

        assertNotNull(context.getPageAssistantContext());
        assertEquals("orders.list", context.getPageAssistantContext().getPageKey());
        assertEquals("/orders", context.getPageAssistantContext().getRoutePattern());
        assertTrue(context.getPageAssistantContext().getActionKeys().contains("openDetail"));
    }

    @Test
    void pageAssistantContextReadsNestedPageAssistantExtraFromAiCodingCreate() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        WorkflowReleaseValidationService validationService = mock(WorkflowReleaseValidationService.class);
        AgentWorkflowBindingService bindingService = mock(AgentWorkflowBindingService.class);
        WorkflowAiCodingService service = newService(workflowService, validationService, bindingService, null);

        WorkflowDefinitionEntity workflow = pageAssistantWorkflow();
        workflow.setExtraJson(objectMapper.writeValueAsString(Map.of(
                "pageAssistant", Map.of(
                        "pageKey", "orders.list",
                        "routePattern", "/orders",
                        "actionKeys", List.of("search", "reset")
                )
        )));
        when(workflowService.findById("wf-page")).thenReturn(Optional.of(workflow));
        when(validationService.validate(workflow)).thenReturn(WorkflowReleaseValidationResult.ok());
        when(bindingService.listByWorkflowId("wf-page")).thenReturn(List.of());

        WorkflowAiCodingContextResponse context = service.getContext("wf-page");

        assertNotNull(context.getPageAssistantContext());
        assertEquals("orders.list", context.getPageAssistantContext().getPageKey());
        assertEquals("/orders", context.getPageAssistantContext().getRoutePattern());
        assertEquals(List.of("search", "reset"), context.getPageAssistantContext().getActionKeys());
    }

    @Test
    void createRejectsMismatchedProjectCode() {
        ScanProjectService scanProjectService = mock(ScanProjectService.class);
        when(scanProjectService.matchesAiCodingAccessKey(7L, TEST_AI_CODING_KEY)).thenReturn(true);
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        when(scanProjectService.getById(7L)).thenReturn(project);
        AiCodingKeyContext.set(TEST_AI_CODING_KEY);

        WorkflowAiCodingService service = newService(
                mock(WorkflowDefinitionService.class),
                mock(WorkflowReleaseValidationService.class),
                mock(AgentWorkflowBindingService.class),
                null,
                mock(WorkflowVersionService.class),
                mock(RunOpsService.class),
                mock(ModelServiceClient.class),
                mock(ToolDefinitionService.class),
                scanProjectService);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.create(
                WorkflowAiCodingCreateRequest.builder()
                        .name("Demo")
                        .keySlug("demo-flow")
                        .projectId(7L)
                        .projectCode("other-project")
                        .build()));
        assertTrue(error.getMessage().contains("projectCode does not match projectId"));
    }

    @Test
    void createRequiresProjectIdAndProjectCode() {
        WorkflowAiCodingService service = newService(
                mock(WorkflowDefinitionService.class),
                mock(WorkflowReleaseValidationService.class),
                mock(AgentWorkflowBindingService.class),
                null);

        assertThrows(IllegalArgumentException.class, () -> service.create(WorkflowAiCodingCreateRequest.builder()
                .name("Demo")
                .keySlug("demo-flow")
                .projectCode("orders")
                .build()));
        assertThrows(IllegalArgumentException.class, () -> service.create(WorkflowAiCodingCreateRequest.builder()
                .name("Demo")
                .keySlug("demo-flow")
                .projectId(7L)
                .build()));
    }

    @Test
    void createPersistsWorkflowAndReturnsContext() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        WorkflowReleaseValidationService validationService = mock(WorkflowReleaseValidationService.class);
        AgentWorkflowBindingService bindingService = mock(AgentWorkflowBindingService.class);
        GuardDecisionLogService guardDecisionLogService = mock(GuardDecisionLogService.class);
        ScanProjectService scanProjectService = mock(ScanProjectService.class);
        when(scanProjectService.matchesAiCodingAccessKey(7L, TEST_AI_CODING_KEY)).thenReturn(true);
        ScanProjectEntity scanProject = new ScanProjectEntity();
        scanProject.setId(7L);
        scanProject.setProjectCode("orders");
        when(scanProjectService.getById(7L)).thenReturn(scanProject);
        AiCodingKeyContext.set(TEST_AI_CODING_KEY);
        WorkflowAiCodingService service = new WorkflowAiCodingService(
                workflowService,
                mock(WorkflowVersionService.class),
                validationService,
                new WorkflowGraphPatchService(objectMapper),
                new WorkflowRuntimeGraphAdapter(objectMapper),
                mock(LangGraph4jRuntimeAdapter.class),
                bindingService,
                mock(PageActionRegistryMapper.class),
                new WorkflowAiCodingAuthService(scanProjectService),
                mock(RunOpsService.class),
                mock(ModelServiceClient.class),
                mock(ToolDefinitionService.class),
                guardDecisionLogService,
                objectMapper);

        WorkflowDefinitionEntity created = sampleWorkflow();
        created.setId("wf-new");
        when(workflowService.create(any())).thenReturn(created);
        when(workflowService.findById("wf-new")).thenReturn(Optional.of(created));
        when(validationService.validate(created)).thenReturn(WorkflowReleaseValidationResult.ok());
        when(bindingService.listByWorkflowId("wf-new")).thenReturn(List.of());

        WorkflowAiCodingContextResponse response = service.create(WorkflowAiCodingCreateRequest.builder()
                .name("Orders Page")
                .keySlug("orders-page")
                .projectId(7L)
                .projectCode("orders")
                .build());

        assertEquals("wf-new", response.getWorkflow().getId());
        verify(guardDecisionLogService).record(
                eq(null),
                eq("WORKFLOW_AI_CODING"),
                eq("WORKFLOW"),
                eq("wf-new"),
                eq("CREATE"),
                eq(null),
                any());
    }

    @Test
    void getVersionsIncludesManualPublishWarning() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        WorkflowReleaseValidationService validationService = mock(WorkflowReleaseValidationService.class);
        WorkflowVersionService versionService = mock(WorkflowVersionService.class);
        WorkflowAiCodingService service = newService(workflowService, validationService, mock(AgentWorkflowBindingService.class), null, versionService, null);

        WorkflowDefinitionEntity workflow = sampleWorkflow();
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(versionService.listVersions("wf-1")).thenReturn(List.of());
        when(versionService.validateRelease("wf-1")).thenReturn(WorkflowReleaseValidationResult.ok());
        when(versionService.resolveActive("wf-1")).thenReturn(null);

        WorkflowAiCodingVersionsResponse response = service.getVersions("wf-1");

        assertEquals("wf-1", response.getWorkflowId());
        assertTrue(response.getWarnings().stream().anyMatch(w -> w.contains("manual")));
    }

    @Test
    void listRunsFiltersByWorkflowId() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        RunOpsService runOpsService = mock(RunOpsService.class);
        WorkflowAiCodingService service = newService(
                workflowService,
                mock(WorkflowReleaseValidationService.class),
                mock(AgentWorkflowBindingService.class),
                null,
                mock(WorkflowVersionService.class),
                runOpsService);

        WorkflowDefinitionEntity workflow = sampleWorkflow();
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(runOpsService.recent(null, 60, 7)).thenReturn(List.of(
                new RunOpsService.RunSummary(
                        "trace-1", "SUCCESS", null, null, null, null, null, null, null,
                        null, null, null, null, null, null, 0, 0, 0, 0, false,
                        null, null, "wf-1", "orders-page", null, null, null, null,
                        "WORKFLOW_DRAFT", "wf-1", Map.of()),
                new RunOpsService.RunSummary(
                        "trace-2", "SUCCESS", null, null, null, null, null, null, null,
                        null, null, null, null, null, null, 0, 0, 0, 0, false,
                        null, null, "wf-other", "other-page", null, null, null, null,
                        "WORKFLOW_DRAFT", "wf-other", Map.of())
        ));

        WorkflowAiCodingRunListResponse response = service.listRuns("wf-1", 20, 7);

        assertEquals(1, response.getRuns().size());
        assertEquals("trace-1", response.getRuns().get(0).traceId());
    }

    @Test
    void getRunDetailRejectsTraceFromOtherWorkflow() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        RunOpsService runOpsService = mock(RunOpsService.class);
        WorkflowAiCodingService service = newService(
                workflowService,
                mock(WorkflowReleaseValidationService.class),
                mock(AgentWorkflowBindingService.class),
                null,
                mock(WorkflowVersionService.class),
                runOpsService);

        WorkflowDefinitionEntity workflow = sampleWorkflow();
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(runOpsService.detail("trace-other")).thenReturn(new RunOpsService.RunDetail(
                new RunOpsService.RunSummary(
                        "trace-other", "SUCCESS", null, null, null, null, null, null, null,
                        null, null, null, null, null, null, 0, 0, 0, 0, false,
                        null, null, "wf-other", "other-page", null, null, null, null,
                        "WORKFLOW_DRAFT", "wf-other", Map.of()),
                List.of(), List.of(), List.of(), null, List.of(), List.of()));

        assertThrows(IllegalArgumentException.class, () -> service.getRunDetail("wf-1", "trace-other"));
    }

    private WorkflowAiCodingService newService(WorkflowDefinitionService workflowService,
                                               WorkflowReleaseValidationService validationService,
                                               AgentWorkflowBindingService bindingService,
                                               LangGraph4jRuntimeAdapter runtimeAdapter) {
        return newService(workflowService, validationService, bindingService, runtimeAdapter,
                mock(WorkflowVersionService.class), mock(RunOpsService.class),
                mock(ModelServiceClient.class), mock(ToolDefinitionService.class));
    }

    private WorkflowAiCodingService newService(WorkflowDefinitionService workflowService,
                                               WorkflowReleaseValidationService validationService,
                                               AgentWorkflowBindingService bindingService,
                                               LangGraph4jRuntimeAdapter runtimeAdapter,
                                               WorkflowVersionService versionService,
                                               RunOpsService runOpsService) {
        return newService(workflowService, validationService, bindingService, runtimeAdapter,
                versionService, runOpsService, mock(ModelServiceClient.class), mock(ToolDefinitionService.class));
    }

    private WorkflowAiCodingService newService(WorkflowDefinitionService workflowService,
                                               WorkflowReleaseValidationService validationService,
                                               AgentWorkflowBindingService bindingService,
                                               LangGraph4jRuntimeAdapter runtimeAdapter,
                                               WorkflowVersionService versionService,
                                               RunOpsService runOpsService,
                                               ModelServiceClient modelServiceClient,
                                               ToolDefinitionService toolDefinitionService,
                                               ScanProjectService scanProjectService) {
        AiCodingKeyContext.set(TEST_AI_CODING_KEY);
        ScanProjectService resolvedScanProjectService = scanProjectService == null
                ? mock(ScanProjectService.class)
                : scanProjectService;
        if (scanProjectService == null) {
            when(resolvedScanProjectService.matchesAiCodingAccessKey(eq(7L), eq(TEST_AI_CODING_KEY))).thenReturn(true);
            ScanProjectEntity project = new ScanProjectEntity();
            project.setId(7L);
            project.setProjectCode("orders");
            when(resolvedScanProjectService.getById(7L)).thenReturn(project);
        }
        return new WorkflowAiCodingService(
                workflowService,
                versionService,
                validationService,
                new WorkflowGraphPatchService(objectMapper),
                new WorkflowRuntimeGraphAdapter(objectMapper),
                runtimeAdapter == null ? mock(LangGraph4jRuntimeAdapter.class) : runtimeAdapter,
                bindingService,
                mock(PageActionRegistryMapper.class),
                new WorkflowAiCodingAuthService(resolvedScanProjectService),
                runOpsService == null ? mock(RunOpsService.class) : runOpsService,
                modelServiceClient == null ? mock(ModelServiceClient.class) : modelServiceClient,
                toolDefinitionService == null ? mock(ToolDefinitionService.class) : toolDefinitionService,
                mock(GuardDecisionLogService.class),
                objectMapper);
    }

    private WorkflowAiCodingService newService(WorkflowDefinitionService workflowService,
                                               WorkflowReleaseValidationService validationService,
                                               AgentWorkflowBindingService bindingService,
                                               LangGraph4jRuntimeAdapter runtimeAdapter,
                                               WorkflowVersionService versionService,
                                               RunOpsService runOpsService,
                                               ModelServiceClient modelServiceClient,
                                               ToolDefinitionService toolDefinitionService) {
        return newService(workflowService, validationService, bindingService, runtimeAdapter,
                versionService, runOpsService, modelServiceClient, toolDefinitionService, null);
    }

    private WorkflowDefinitionEntity sampleWorkflow() throws Exception {
        WorkflowDefinitionEntity workflow = new WorkflowDefinitionEntity();
        workflow.setId("wf-1");
        workflow.setProjectId(7L);
        workflow.setProjectCode("orders");
        workflow.setKeySlug("orders-page");
        workflow.setName("Orders Page");
        workflow.setWorkflowType("CHAT");
        workflow.setRuntimeType("LANGGRAPH4J");
        workflow.setStatus("DRAFT");
        workflow.setDefaultModelInstanceId("llm-main");
        workflow.setUpdatedAt(LocalDateTime.parse("2026-06-16T10:00:00"));
        workflow.setGraphSpecJson(objectMapper.writeValueAsString(GraphSpec.builder()
                .entry("start")
                .node(GraphSpec.Node.builder().id("start").type("USER_INPUT").name("Start").build())
                .build()));
        workflow.setCanvasJson("{\"version\":2,\"nodes\":[],\"edges\":[]}");
        return workflow;
    }

    private WorkflowDefinitionEntity pageAssistantWorkflow() throws Exception {
        WorkflowDefinitionEntity workflow = sampleWorkflow();
        workflow.setId("wf-page");
        workflow.setWorkflowType("PAGE_ASSISTANT");
        workflow.setExtraJson(objectMapper.writeValueAsString(Map.of(
                "pageKey", "orders.list",
                "routePattern", "/orders",
                "actionKeys", List.of("refresh")
        )));
        workflow.setGraphSpecJson(objectMapper.writeValueAsString(GraphSpec.builder()
                .entry("page-action")
                .node(GraphSpec.Node.builder()
                        .id("page-action")
                        .type("PAGE_ACTION")
                        .config(new LinkedHashMap<>(Map.of(
                                "pageKey", "orders.list",
                                "actionKey", "openDetail",
                                "projectCode", "orders"
                        )))
                        .build())
                .build()));
        return workflow;
    }
}
