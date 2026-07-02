package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.debug.RuntimeExecutableDebugSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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

class RuntimeDebugSessionCompatibilityControllerTest {

    @Test
    void keepsPublicDebugSessionRoutesOnRuntimeService() throws Exception {
        Method create = RuntimeDebugSessionCompatibilityController.class
                .getDeclaredMethod("create", RuntimeExecutableDebugSessionService.CreateRequest.class);
        Method get = RuntimeDebugSessionCompatibilityController.class.getDeclaredMethod("get", String.class);
        Method submit = RuntimeDebugSessionCompatibilityController.class
                .getDeclaredMethod("submit", String.class, RuntimeExecutableDebugSessionService.SubmitRequest.class);
        Method cancel = RuntimeDebugSessionCompatibilityController.class.getDeclaredMethod("cancel", String.class);

        assertArrayEquals(new String[] {"/api/runtime/debug-sessions"}, create.getAnnotation(PostMapping.class).path());
        assertArrayEquals(new String[] {"/api/runtime/debug-sessions/{sessionId}"}, get.getAnnotation(GetMapping.class).path());
        assertArrayEquals(new String[] {"/api/runtime/debug-sessions/{sessionId}/submit"},
                submit.getAnnotation(PostMapping.class).path());
        assertArrayEquals(new String[] {"/api/runtime/debug-sessions/{sessionId}/cancel"},
                cancel.getAnnotation(PostMapping.class).path());
    }

    @Test
    void delegatesDebugSessionRoutesToRuntimeService() {
        RuntimeExecutableDebugSessionService service = mock(RuntimeExecutableDebugSessionService.class);
        RuntimeDebugSessionCompatibilityController controller = new RuntimeDebugSessionCompatibilityController(service);
        RuntimeExecutableDebugSessionService.CreateRequest createRequest =
                new RuntimeExecutableDebugSessionService.CreateRequest("WORKFLOW_DRAFT", Map.of(), "hello", Map.of(), Map.of());
        RuntimeExecutableDebugSessionService.SubmitRequest submitRequest =
                new RuntimeExecutableDebugSessionService.SubmitRequest("submit", Map.of("approved", true), null);
        RuntimeExecutableDebugSessionService.SessionView expected =
                new RuntimeExecutableDebugSessionService.SessionView(
                        "session-1", "run-1", "trace-1", "WORKFLOW_DRAFT", true, "SUCCESS", "answer",
                        "ok", List.of(), List.of(), Map.of(), null, null, null, null);
        when(service.create(createRequest)).thenReturn(expected);
        when(service.get("session-1")).thenReturn(expected);
        when(service.submit("session-1", submitRequest)).thenReturn(expected);
        when(service.cancel("session-1")).thenReturn(expected);

        ResponseEntity<RuntimeExecutableDebugSessionService.SessionView> createResponse = controller.create(createRequest);
        ResponseEntity<RuntimeExecutableDebugSessionService.SessionView> getResponse = controller.get("session-1");
        ResponseEntity<RuntimeExecutableDebugSessionService.SessionView> submitResponse =
                controller.submit("session-1", submitRequest);
        ResponseEntity<RuntimeExecutableDebugSessionService.SessionView> cancelResponse = controller.cancel("session-1");

        assertEquals(HttpStatus.OK, createResponse.getStatusCode());
        assertEquals(expected, createResponse.getBody());
        assertEquals(expected, getResponse.getBody());
        assertEquals(expected, submitResponse.getBody());
        assertEquals(expected, cancelResponse.getBody());
        verify(service).create(createRequest);
        verify(service).get("session-1");
        verify(service).submit("session-1", submitRequest);
        verify(service).cancel("session-1");
    }
}
