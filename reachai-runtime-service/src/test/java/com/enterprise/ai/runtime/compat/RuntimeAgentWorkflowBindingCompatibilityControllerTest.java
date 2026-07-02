package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.workflow.RuntimeAgentWorkflowBindingResolveRequest;
import com.enterprise.ai.runtime.workflow.RuntimeAgentWorkflowBindingService;
import com.enterprise.ai.runtime.workflow.RuntimeAgentWorkflowBindingView;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeAgentWorkflowBindingCompatibilityControllerTest {

    @Test
    void keepsPublicAgentWorkflowBindingRoutesOnRuntimeService() throws Exception {
        Method list = RuntimeAgentWorkflowBindingCompatibilityController.class.getDeclaredMethod("list", String.class);
        Method create = RuntimeAgentWorkflowBindingCompatibilityController.class
                .getDeclaredMethod("create", String.class, RuntimeAgentWorkflowBindingView.class);
        Method update = RuntimeAgentWorkflowBindingCompatibilityController.class
                .getDeclaredMethod("update", String.class, Long.class, RuntimeAgentWorkflowBindingView.class);
        Method delete = RuntimeAgentWorkflowBindingCompatibilityController.class
                .getDeclaredMethod("delete", String.class, Long.class);
        Method resolvePreview = RuntimeAgentWorkflowBindingCompatibilityController.class
                .getDeclaredMethod("resolvePreview", String.class, RuntimeAgentWorkflowBindingResolveRequest.class);

        assertArrayEquals(new String[] {"/api/agents/{agentId}/workflow-bindings"},
                list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/agents/{agentId}/workflow-bindings"},
                create.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/agents/{agentId}/workflow-bindings/{bindingId}"},
                update.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[] {"/api/agents/{agentId}/workflow-bindings/{bindingId}"},
                delete.getAnnotation(DeleteMapping.class).value());
        assertArrayEquals(new String[] {"/api/agents/{agentId}/workflow-bindings/resolve-preview"},
                resolvePreview.getAnnotation(PostMapping.class).value());
    }

    @Test
    void delegatesBindingCrudAndResolvePreviewToRuntimeService() {
        RuntimeAgentWorkflowBindingService service = mock(RuntimeAgentWorkflowBindingService.class);
        RuntimeAgentWorkflowBindingCompatibilityController controller =
                new RuntimeAgentWorkflowBindingCompatibilityController(service);
        RuntimeAgentWorkflowBindingView view = view("agent-1");
        RuntimeAgentWorkflowBindingResolveRequest request =
                new RuntimeAgentWorkflowBindingResolveRequest(null, "orders", "orders.list", null, null, null);
        RuntimeAgentWorkflowBindingResolveRequest effective =
                new RuntimeAgentWorkflowBindingResolveRequest("agent-1", "orders", "orders.list", null, null, null);
        when(service.list("agent-1")).thenReturn(List.of(view));
        when(service.create("agent-1", view)).thenReturn(view);
        when(service.update("agent-1", 9L, view)).thenReturn(Optional.of(view));
        when(service.delete("agent-1", 9L)).thenReturn(true);
        when(service.resolvePreview(effective)).thenReturn(Optional.of(view));

        assertEquals(List.of(view), controller.list("agent-1").getBody());
        assertEquals(view, controller.create("agent-1", view).getBody());
        assertEquals(view, controller.update("agent-1", 9L, view).getBody());
        assertEquals(HttpStatus.NO_CONTENT, controller.delete("agent-1", 9L).getStatusCode());
        assertEquals(view, controller.resolvePreview("agent-1", request).getBody());
        verify(service).list("agent-1");
        verify(service).create("agent-1", view);
        verify(service).update("agent-1", 9L, view);
        verify(service).delete("agent-1", 9L);
        verify(service).resolvePreview(effective);
    }

    @Test
    void updateReturnsNotFoundForMismatchedBinding() {
        RuntimeAgentWorkflowBindingService service = mock(RuntimeAgentWorkflowBindingService.class);
        RuntimeAgentWorkflowBindingCompatibilityController controller =
                new RuntimeAgentWorkflowBindingCompatibilityController(service);
        RuntimeAgentWorkflowBindingView view = view("agent-1");
        when(service.update("agent-1", 9L, view)).thenReturn(Optional.empty());

        ResponseEntity<RuntimeAgentWorkflowBindingView> response = controller.update("agent-1", 9L, view);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    private RuntimeAgentWorkflowBindingView view(String agentId) {
        return new RuntimeAgentWorkflowBindingView(
                9L,
                agentId,
                "wf-1",
                "orders",
                "PAGE",
                "orders.list",
                null,
                null,
                null,
                0,
                true,
                null,
                null,
                null,
                null);
    }
}
