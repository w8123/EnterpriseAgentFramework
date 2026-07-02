package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.agent.RuntimeAgentEntryService;
import com.enterprise.ai.runtime.agent.RuntimeAgentEntryView;
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

class RuntimeAgentEntryCompatibilityControllerTest {

    @Test
    void keepsPublicAgentEntryRoutesOnRuntimeService() throws Exception {
        Method list = RuntimeAgentEntryCompatibilityController.class
                .getDeclaredMethod("list", Long.class, String.class, String.class);
        Method create = RuntimeAgentEntryCompatibilityController.class
                .getDeclaredMethod("create", RuntimeAgentEntryView.class);
        Method get = RuntimeAgentEntryCompatibilityController.class.getDeclaredMethod("get", String.class);
        Method update = RuntimeAgentEntryCompatibilityController.class
                .getDeclaredMethod("update", String.class, RuntimeAgentEntryView.class);
        Method delete = RuntimeAgentEntryCompatibilityController.class.getDeclaredMethod("delete", String.class);

        assertArrayEquals(new String[] {"/api/agents"}, list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/agents"}, create.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/agents/{id}"}, get.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/agents/{id}"}, update.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[] {"/api/agents/{id}"}, delete.getAnnotation(DeleteMapping.class).value());
    }

    @Test
    void delegatesAgentEntryCrudToRuntimeService() {
        RuntimeAgentEntryService service = mock(RuntimeAgentEntryService.class);
        RuntimeAgentEntryCompatibilityController controller = new RuntimeAgentEntryCompatibilityController(service);
        RuntimeAgentEntryView view = view("agent-1");
        when(service.list(7L, "orders", "PAGE_COPILOT")).thenReturn(List.of(view));
        when(service.create(view)).thenReturn(view);
        when(service.findById("agent-1")).thenReturn(Optional.of(view));
        when(service.update("agent-1", view)).thenReturn(view);
        when(service.delete("agent-1")).thenReturn(true);

        assertEquals(List.of(view), controller.list(7L, "orders", "PAGE_COPILOT").getBody());
        assertEquals(view, controller.create(view).getBody());
        assertEquals(view, controller.get("agent-1").getBody());
        assertEquals(view, controller.update("agent-1", view).getBody());
        ResponseEntity<Void> deleted = controller.delete("agent-1");

        assertEquals(HttpStatus.NO_CONTENT, deleted.getStatusCode());
        verify(service).list(7L, "orders", "PAGE_COPILOT");
        verify(service).create(view);
        verify(service).findById("agent-1");
        verify(service).update("agent-1", view);
        verify(service).delete("agent-1");
    }

    @Test
    void deleteReturnsNotFoundWhenAgentDoesNotExist() {
        RuntimeAgentEntryService service = mock(RuntimeAgentEntryService.class);
        RuntimeAgentEntryCompatibilityController controller = new RuntimeAgentEntryCompatibilityController(service);
        when(service.delete("missing")).thenReturn(false);

        ResponseEntity<Void> response = controller.delete("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    private RuntimeAgentEntryView view(String id) {
        return new RuntimeAgentEntryView(
                id,
                7L,
                "orders",
                "orders-agent",
                "Orders Agent",
                null,
                "PAGE_COPILOT",
                "PROJECT",
                null,
                null,
                null,
                null,
                true,
                null,
                null);
    }
}
