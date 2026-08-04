package com.enterprise.ai.runtime.api;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeWorkflowAiCodingPublicControllerTest {

    private final RuntimeWorkflowAiCodingService service = mock(RuntimeWorkflowAiCodingService.class);
    private final RuntimeWorkflowAiCodingPublicController controller =
            new RuntimeWorkflowAiCodingPublicController(service);

    @Test
    void exposesCanonicalAiCodingRoutes() throws Exception {
        assertArrayEquals(new String[]{"/api/workflows/ai-coding/workflows"},
                method("create", RuntimeWorkflowAiCodingService.CreateRequest.class)
                        .getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/api/workflows/{workflowId}/ai-coding/context"},
                method("context", String.class).getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/api/workflows/{workflowId}/ai-coding/resource-bindings"},
                method("replaceResourceBindings", String.class,
                        RuntimeWorkflowAiCodingService.ResourceBindingsRequest.class)
                        .getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[]{"/api/workflows/{workflowId}/ai-coding/patch"},
                method("patch", String.class, RuntimeWorkflowAiCodingService.PatchRequest.class)
                        .getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/api/workflows/{workflowId}/ai-coding/publish"},
                method("publish", String.class, RuntimeWorkflowAiCodingService.PublishRequest.class)
                        .getAnnotation(PostMapping.class).value());
    }

    @Test
    void delegatesCreatePatchAndPublish() {
        RuntimeWorkflowAiCodingService.CreateRequest create = new RuntimeWorkflowAiCodingService.CreateRequest(
                "Order Assistant", "order-assistant", 12L, "orders", null,
                "PAGE_ASSISTANT", "GRAPH_SPEC", null, new GraphSpec(), Map.of(), Map.of(), "create");
        RuntimeWorkflowAiCodingService.ContextView context = contextView("wf-ai-1");
        when(service.createWorkflow(create)).thenReturn(context);
        assertEquals(context, controller.create(create).getBody());
        verify(service).createWorkflow(create);

        RuntimeWorkflowAiCodingService.ResourceBindingsRequest bindings =
                new RuntimeWorkflowAiCodingService.ResourceBindingsRequest(
                        "2026-07-01T09:00", List.of(), "clear draft bindings");
        when(service.replaceResourceBindings("wf-ai-1", bindings)).thenReturn(context);
        assertEquals(context, controller.replaceResourceBindings("wf-ai-1", bindings).getBody());
        verify(service).replaceResourceBindings("wf-ai-1", bindings);

        RuntimeWorkflowAiCodingService.PatchRequest patch =
                new RuntimeWorkflowAiCodingService.PatchRequest(null, true, List.of(), null, "preview");
        RuntimeWorkflowAiCodingService.PatchView patchView = new RuntimeWorkflowAiCodingService.PatchView(
                true, false, "0 operations", List.of(), List.of(), new GraphSpec(), Map.of(),
                new RuntimeWorkflowAiCodingService.ValidationView(
                        "wf-ai-1", "PROPOSED", true, List.of(), List.of()),
                null, List.of(), List.of());
        when(service.patchWorkflow("wf-ai-1", patch)).thenReturn(patchView);
        assertEquals(patchView, controller.patch("wf-ai-1", patch).getBody());
        verify(service).patchWorkflow("wf-ai-1", patch);

        RuntimeWorkflowAiCodingService.PublishRequest publish =
                new RuntimeWorkflowAiCodingService.PublishRequest("v1.0.0", 100, "first", "codex");
        RuntimeWorkflowAiCodingService.PublishView publishView =
                new RuntimeWorkflowAiCodingService.PublishView(
                        "wf-ai-1", 1L, "v1.0.0", "ACTIVE", 100, "codex", null);
        when(service.publishWorkflow("wf-ai-1", publish)).thenReturn(publishView);
        assertEquals(publishView, controller.publish("wf-ai-1", publish).getBody());
        verify(service).publishWorkflow("wf-ai-1", publish);
    }

    private Method method(String name, Class<?>... parameterTypes) throws Exception {
        return RuntimeWorkflowAiCodingPublicController.class.getDeclaredMethod(name, parameterTypes);
    }

    private RuntimeWorkflowAiCodingService.ContextView contextView(String workflowId) {
        return new RuntimeWorkflowAiCodingService.ContextView(
                new RuntimeWorkflowAiCodingService.WorkflowSnapshot(
                        workflowId, "order-assistant", "Order Assistant", null, 12L, "orders",
                        "PAGE_ASSISTANT", "GRAPH_SPEC", "USER", "AI_CODING", "model-1", "DRAFT", null),
                new GraphSpec(), Map.of(),
                new RuntimeWorkflowAiCodingService.ValidationView(
                        workflowId, "CURRENT", true, List.of(), List.of()),
                List.of(), Map.of(), Map.of(), List.of(), List.of(), List.of());
    }
}
