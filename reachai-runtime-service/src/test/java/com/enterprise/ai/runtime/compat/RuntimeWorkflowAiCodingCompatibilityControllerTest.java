package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeWorkflowAiCodingCompatibilityControllerTest {

    private final RuntimeWorkflowAiCodingService service = mock(RuntimeWorkflowAiCodingService.class);
    private final RuntimeWorkflowAiCodingCompatibilityController controller =
            new RuntimeWorkflowAiCodingCompatibilityController(service);

    @Test
    void exposesWorkflowAiCodingRoutesAsRuntimeOwnedEndpoints() throws Exception {
        assertArrayEquals(new String[] {"/api/workflows/ai-coding/workflows"},
                method("create", RuntimeWorkflowAiCodingService.CreateRequest.class)
                        .getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/ai-coding/context"},
                method("context", String.class).getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/ai-coding/patch"},
                method("patch", String.class, RuntimeWorkflowAiCodingService.PatchRequest.class)
                        .getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/ai-coding/run"},
                method("run", String.class, RuntimeWorkflowAiCodingService.RunRequest.class)
                        .getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/ai-coding/publish"},
                method("publish", String.class, RuntimeWorkflowAiCodingService.PublishRequest.class)
                        .getAnnotation(PostMapping.class).value());
    }

    @Test
    void createDelegatesToRuntimeAiCodingService() {
        RuntimeWorkflowAiCodingService.CreateRequest request = new RuntimeWorkflowAiCodingService.CreateRequest(
                "Order Assistant",
                "order-assistant",
                12L,
                "orders",
                null,
                "PAGE_ASSISTANT",
                "LANGGRAPH4J",
                null,
                new GraphSpec(),
                Map.of(),
                Map.of(),
                "create");
        RuntimeWorkflowAiCodingService.ContextView expected = contextView("wf-ai-1");
        when(service.createWorkflow(request)).thenReturn(expected);

        ResponseEntity<RuntimeWorkflowAiCodingService.ContextView> response = controller.create(request);

        assertEquals(expected, response.getBody());
        verify(service).createWorkflow(request);
    }

    @Test
    void patchDelegatesToRuntimeAiCodingService() {
        RuntimeWorkflowAiCodingService.PatchRequest request =
                new RuntimeWorkflowAiCodingService.PatchRequest(null, true, List.of(), null, "preview");
        RuntimeWorkflowAiCodingService.PatchView expected = new RuntimeWorkflowAiCodingService.PatchView(
                true,
                false,
                "0 operations",
                List.of(),
                List.of(),
                new GraphSpec(),
                Map.of(),
                new RuntimeWorkflowAiCodingService.ValidationView("wf-ai-1", "PROPOSED", true, List.of(), List.of()),
                null,
                List.of(),
                List.of());
        when(service.patchWorkflow("wf-ai-1", request)).thenReturn(expected);

        ResponseEntity<RuntimeWorkflowAiCodingService.PatchView> response = controller.patch("wf-ai-1", request);

        assertEquals(expected, response.getBody());
        verify(service).patchWorkflow("wf-ai-1", request);
    }

    @Test
    void publishDelegatesToRuntimeAiCodingService() {
        RuntimeWorkflowAiCodingService.PublishRequest request =
                new RuntimeWorkflowAiCodingService.PublishRequest("v1.0.0", 100, "first", "codex");
        RuntimeWorkflowAiCodingService.PublishView expected =
                new RuntimeWorkflowAiCodingService.PublishView("wf-ai-1", 1L, "v1.0.0", "ACTIVE", 100, "codex", null);
        when(service.publishWorkflow("wf-ai-1", request)).thenReturn(expected);

        ResponseEntity<RuntimeWorkflowAiCodingService.PublishView> response = controller.publish("wf-ai-1", request);

        assertEquals(expected, response.getBody());
        verify(service).publishWorkflow("wf-ai-1", request);
    }

    private Method method(String name, Class<?>... parameterTypes) throws Exception {
        return RuntimeWorkflowAiCodingCompatibilityController.class.getDeclaredMethod(name, parameterTypes);
    }

    private RuntimeWorkflowAiCodingService.ContextView contextView(String workflowId) {
        return new RuntimeWorkflowAiCodingService.ContextView(
                new RuntimeWorkflowAiCodingService.WorkflowSnapshot(
                        workflowId,
                        "order-assistant",
                        "Order Assistant",
                        null,
                        12L,
                        "orders",
                        "PAGE_ASSISTANT",
                        "LANGGRAPH4J",
                        "model-1",
                        "DRAFT",
                        "AI_CODING",
                        null),
                new GraphSpec(),
                Map.of(),
                new RuntimeWorkflowAiCodingService.ValidationView(workflowId, "CURRENT", true, List.of(), List.of()),
                List.of(),
                Map.of(),
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                List.of());
    }
}
