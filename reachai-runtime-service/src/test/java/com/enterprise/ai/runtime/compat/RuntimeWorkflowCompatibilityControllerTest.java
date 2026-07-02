package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDebugService;
import com.enterprise.ai.runtime.workflow.draft.RuntimeWorkflowDraftEditRequest;
import com.enterprise.ai.runtime.workflow.draft.RuntimeWorkflowDraftEditService;
import com.enterprise.ai.runtime.workflow.draft.RuntimeWorkflowDraftEditView;
import com.enterprise.ai.runtime.workflow.draft.RuntimeWorkflowDraftGenerationRequest;
import com.enterprise.ai.runtime.workflow.draft.RuntimeWorkflowDraftGenerationService;
import com.enterprise.ai.runtime.workflow.draft.RuntimeWorkflowDraftGenerationView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowStudioService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeWorkflowCompatibilityControllerTest {

    @Test
    void keepsPublicWorkflowCrudRoutesOnRuntimeService() throws Exception {
        Method list = RuntimeWorkflowCompatibilityController.class
                .getDeclaredMethod("list", Long.class, String.class, String.class, String.class);
        Method create = RuntimeWorkflowCompatibilityController.class
                .getDeclaredMethod("create", RuntimeWorkflowDefinitionEntity.class);
        Method get = RuntimeWorkflowCompatibilityController.class.getDeclaredMethod("get", String.class);
        Method update = RuntimeWorkflowCompatibilityController.class
                .getDeclaredMethod("update", String.class, RuntimeWorkflowDefinitionEntity.class);
        Method delete = RuntimeWorkflowCompatibilityController.class.getDeclaredMethod("delete", String.class);
        Method graphNodeTypes = RuntimeWorkflowCompatibilityController.class.getDeclaredMethod("graphNodeTypes");
        Method validateRuntime = RuntimeWorkflowCompatibilityController.class
                .getDeclaredMethod("validateRuntime", RuntimeWorkflowRuntimeValidationRequest.class);
        Method studio = RuntimeWorkflowCompatibilityController.class.getDeclaredMethod("studio", String.class);
        Method saveStudio = RuntimeWorkflowCompatibilityController.class
                .getDeclaredMethod("saveStudio", String.class, RuntimeWorkflowStudioSaveRequest.class);
        Method debugNode = RuntimeWorkflowCompatibilityController.class
                .getDeclaredMethod("debugWorkflowNode", RuntimeWorkflowDebugService.NodeDebugRequest.class);
        Method debugRun = RuntimeWorkflowCompatibilityController.class
                .getDeclaredMethod("debugWorkflowRun", RuntimeWorkflowDebugService.DebugRunRequest.class);
        Method generateDraft = RuntimeWorkflowCompatibilityController.class
                .getDeclaredMethod("generateWorkflowStudioDraft", RuntimeWorkflowDraftGenerationRequest.class);
        Method editDraft = RuntimeWorkflowCompatibilityController.class
                .getDeclaredMethod("editWorkflowStudioDraft", RuntimeWorkflowDraftEditRequest.class);

        assertArrayEquals(new String[] {"/api/workflows"}, list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows"}, create.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{id}"}, get.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{id}"}, update.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{id}"}, delete.getAnnotation(DeleteMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/graph-node-types"},
                graphNodeTypes.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/runtime-validation"},
                validateRuntime.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{id}/studio"}, studio.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{id}/studio"}, saveStudio.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/studio/debug-node"},
                debugNode.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/studio/debug-run"},
                debugRun.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/studio/generate-draft"},
                generateDraft.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/studio/edit-draft"},
                editDraft.getAnnotation(PostMapping.class).value());
    }

    @Test
    void delegatesListToRuntimeWorkflowService() {
        RuntimeWorkflowDefinitionService service = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeWorkflowCompatibilityController controller = controller(service);
        List<RuntimeWorkflowDefinitionEntity> expected = List.of(workflow("wf-1"));
        when(service.list(7L, "orders", "CHAT", "DRAFT")).thenReturn(expected);

        ResponseEntity<List<RuntimeWorkflowDefinitionEntity>> response =
                controller.list(7L, "orders", "CHAT", "DRAFT");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
        verify(service).list(7L, "orders", "CHAT", "DRAFT");
    }

    @Test
    void returnsNotFoundWhenWorkflowIsMissing() {
        RuntimeWorkflowDefinitionService service = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeWorkflowCompatibilityController controller = controller(service);
        when(service.findById("missing")).thenReturn(Optional.empty());

        ResponseEntity<RuntimeWorkflowDefinitionEntity> response = controller.get("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void delegatesCreateAndUpdateToRuntimeWorkflowService() {
        RuntimeWorkflowDefinitionService service = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeWorkflowCompatibilityController controller = controller(service);
        RuntimeWorkflowDefinitionEntity request = workflow("wf-1");
        when(service.create(request)).thenReturn(request);
        when(service.update("wf-1", request)).thenReturn(request);

        assertEquals(request, controller.create(request).getBody());
        assertEquals(request, controller.update("wf-1", request).getBody());
        verify(service).create(request);
        verify(service).update("wf-1", request);
    }

    @Test
    void deleteReturnsNoContentAndMapsRejectedDeleteToBadRequest() {
        RuntimeWorkflowDefinitionService service = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeWorkflowCompatibilityController controller = controller(service);

        ResponseEntity<?> deleted = controller.delete("wf-1");

        assertEquals(HttpStatus.NO_CONTENT, deleted.getStatusCode());
        verify(service).delete("wf-1");
    }

    @Test
    void deleteMapsNotFoundAndValidationErrors() {
        RuntimeWorkflowDefinitionService service = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeWorkflowCompatibilityController controller = controller(service);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("workflow not found: missing"))
                .when(service).delete("missing");
        org.mockito.Mockito.doThrow(new IllegalArgumentException("仅草稿状态的 Workflow 可删除"))
                .when(service).delete("published");

        ResponseEntity<?> missing = controller.delete("missing");
        ResponseEntity<?> rejected = controller.delete("published");

        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
        assertEquals(Map.of("message", "仅草稿状态的 Workflow 可删除"), rejected.getBody());
    }

    @Test
    void returnsGraphNodeTypeCatalog() {
        RuntimeWorkflowCompatibilityController controller =
                controller(mock(RuntimeWorkflowDefinitionService.class));

        ResponseEntity<?> response = controller.graphNodeTypes();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<?> body = (List<?>) response.getBody();
        assertEquals(false, body == null || body.isEmpty());
    }

    @Test
    void delegatesRuntimeValidationToValidationService() {
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeWorkflowReleaseValidationService validationService = mock(RuntimeWorkflowReleaseValidationService.class);
        RuntimeWorkflowCompatibilityController controller =
                new RuntimeWorkflowCompatibilityController(
                        workflowService,
                        validationService,
                        mock(RuntimeWorkflowStudioService.class),
                        mock(RuntimeWorkflowDebugService.class),
                        mock(RuntimeWorkflowDraftGenerationService.class),
                        mock(RuntimeWorkflowDraftEditService.class));
        RuntimeWorkflowDefinitionEntity workflow = workflow("wf-1");
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));
        RuntimeWorkflowReleaseValidationResult result = RuntimeWorkflowReleaseValidationResult.builder()
                .error("GRAPH_ENTRY_MISSING", null, "GraphSpec entry is required")
                .build();
        when(validationService.validate(workflow)).thenReturn(result);

        ResponseEntity<RuntimeWorkflowRuntimeValidationView> response = controller.validateRuntime(
                new RuntimeWorkflowRuntimeValidationRequest("wf-1", null, null));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(false, response.getBody().valid());
        assertEquals("GRAPH_ENTRY_MISSING", response.getBody().errors().get(0).code());
    }

    @Test
    void delegatesStudioStateAndSaveToStudioService() {
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeWorkflowStudioService studioService = mock(RuntimeWorkflowStudioService.class);
        RuntimeWorkflowCompatibilityController controller = new RuntimeWorkflowCompatibilityController(
                workflowService,
                mock(RuntimeWorkflowReleaseValidationService.class),
                studioService,
                mock(RuntimeWorkflowDebugService.class),
                mock(RuntimeWorkflowDraftGenerationService.class),
                mock(RuntimeWorkflowDraftEditService.class));
        RuntimeWorkflowStudioService.WorkflowStudioState state = new RuntimeWorkflowStudioService.WorkflowStudioState(
                "wf-1", 7L, "demo", "orders", "Orders", "desc", "{}", "{}",
                "CHAT", "LANGGRAPH4J", "llm-1", null, "DRAFT", "MANUAL", null);
        RuntimeWorkflowDefinitionEntity updated = workflow("wf-1");
        RuntimeWorkflowStudioSaveRequest request = new RuntimeWorkflowStudioSaveRequest("{}", "{}", null);
        when(studioService.getStudioState("wf-1")).thenReturn(state);
        when(studioService.saveStudioDraft(org.mockito.Mockito.eq("wf-1"), org.mockito.Mockito.any()))
                .thenReturn(updated);

        assertEquals(state, controller.studio("wf-1").getBody());
        assertEquals(updated, controller.saveStudio("wf-1", request).getBody());
        verify(studioService).getStudioState("wf-1");
        verify(studioService).saveStudioDraft(org.mockito.Mockito.eq("wf-1"), org.mockito.Mockito.any());
    }

    @Test
    void delegatesWorkflowStudioDebugRoutesToRuntimeDebugService() {
        RuntimeWorkflowDebugService debugService = mock(RuntimeWorkflowDebugService.class);
        RuntimeWorkflowCompatibilityController controller = new RuntimeWorkflowCompatibilityController(
                mock(RuntimeWorkflowDefinitionService.class),
                mock(RuntimeWorkflowReleaseValidationService.class),
                mock(RuntimeWorkflowStudioService.class),
                debugService,
                mock(RuntimeWorkflowDraftGenerationService.class),
                mock(RuntimeWorkflowDraftEditService.class));
        RuntimeWorkflowDebugService.DebugRunRequest runRequest =
                new RuntimeWorkflowDebugService.DebugRunRequest(
                        "wf-1", null, null, null, null, null, null, null, null, "hello", Map.of(), Map.of());
        RuntimeWorkflowDebugService.DebugRunResult runResult =
                new RuntimeWorkflowDebugService.DebugRunResult(
                        "run-1", "trace-1", null, "WORKFLOW", true, "SUCCESS", "ok", "answer",
                        List.of(), null, List.of(), Map.of(), null, null);
        RuntimeWorkflowDebugService.NodeDebugRequest nodeRequest =
                new RuntimeWorkflowDebugService.NodeDebugRequest(
                        "wf-1", null, null, null, null, null, null, null, null, "answer", "hello", Map.of());
        RuntimeWorkflowDebugService.NodeDebugResult nodeResult =
                new RuntimeWorkflowDebugService.NodeDebugResult(
                        "answer", "ANSWER", true, 1L, Map.of(), Map.of("lastOutput", "ok"),
                        "ok", null, null, null, "trace-node");
        when(debugService.debugRun(runRequest)).thenReturn(runResult);
        when(debugService.debugNode(nodeRequest)).thenReturn(nodeResult);

        ResponseEntity<RuntimeWorkflowDebugService.DebugRunResult> runResponse = controller.debugWorkflowRun(runRequest);
        ResponseEntity<RuntimeWorkflowDebugService.NodeDebugResult> nodeResponse = controller.debugWorkflowNode(nodeRequest);

        assertEquals(HttpStatus.OK, runResponse.getStatusCode());
        assertEquals(runResult, runResponse.getBody());
        assertEquals(HttpStatus.OK, nodeResponse.getStatusCode());
        assertEquals(nodeResult, nodeResponse.getBody());
        verify(debugService).debugRun(runRequest);
        verify(debugService).debugNode(nodeRequest);
    }

    @Test
    void delegatesWorkflowStudioGenerateDraftToRuntimeService() {
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeWorkflowDraftGenerationService draftGenerationService = mock(RuntimeWorkflowDraftGenerationService.class);
        RuntimeWorkflowCompatibilityController controller = new RuntimeWorkflowCompatibilityController(
                workflowService,
                mock(RuntimeWorkflowReleaseValidationService.class),
                mock(RuntimeWorkflowStudioService.class),
                mock(RuntimeWorkflowDebugService.class),
                draftGenerationService,
                mock(RuntimeWorkflowDraftEditService.class));
        RuntimeWorkflowDraftGenerationRequest request = new RuntimeWorkflowDraftGenerationRequest(
                "agent-1",
                "Order Agent",
                "Generate order workflow",
                "orders",
                "model-1",
                "WORKFLOW",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        RuntimeWorkflowDraftGenerationView expected = new RuntimeWorkflowDraftGenerationView(
                "LLM_DRAFT",
                Map.of("nodes", List.of(), "edges", List.of()),
                null,
                List.of(),
                List.of(),
                List.of());
        when(draftGenerationService.generate(request)).thenReturn(expected);

        ResponseEntity<RuntimeWorkflowDraftGenerationView> response = controller.generateWorkflowStudioDraft(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
        verify(draftGenerationService).generate(request);
    }

    @Test
    void delegatesWorkflowStudioEditDraftToRuntimeService() {
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeWorkflowDraftEditService draftEditService = mock(RuntimeWorkflowDraftEditService.class);
        RuntimeWorkflowCompatibilityController controller = new RuntimeWorkflowCompatibilityController(
                workflowService,
                mock(RuntimeWorkflowReleaseValidationService.class),
                mock(RuntimeWorkflowStudioService.class),
                mock(RuntimeWorkflowDebugService.class),
                mock(RuntimeWorkflowDraftGenerationService.class),
                draftEditService);
        RuntimeWorkflowDraftEditRequest request = new RuntimeWorkflowDraftEditRequest(
                "agent-1",
                "Order Agent",
                "把回答节点文案改成完成",
                "orders",
                "model-1",
                Map.of("nodes", List.of(), "edges", List.of()),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        RuntimeWorkflowDraftEditView expected = new RuntimeWorkflowDraftEditView(
                "LLM_PATCH",
                "updated answer",
                List.of(),
                request.currentCanvas(),
                null,
                List.of(),
                List.of(),
                List.of());
        when(draftEditService.edit(request)).thenReturn(expected);

        ResponseEntity<RuntimeWorkflowDraftEditView> response = controller.editWorkflowStudioDraft(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
        verify(draftEditService).edit(request);
    }

    private RuntimeWorkflowCompatibilityController controller(RuntimeWorkflowDefinitionService service) {
        return new RuntimeWorkflowCompatibilityController(
                service,
                mock(RuntimeWorkflowReleaseValidationService.class),
                mock(RuntimeWorkflowStudioService.class),
                mock(RuntimeWorkflowDebugService.class),
                mock(RuntimeWorkflowDraftGenerationService.class),
                mock(RuntimeWorkflowDraftEditService.class));
    }

    private RuntimeWorkflowDefinitionEntity workflow(String id) {
        RuntimeWorkflowDefinitionEntity entity = new RuntimeWorkflowDefinitionEntity();
        entity.setId(id);
        entity.setKeySlug("orders");
        entity.setName("Orders");
        entity.setStatus("DRAFT");
        return entity;
    }
}
