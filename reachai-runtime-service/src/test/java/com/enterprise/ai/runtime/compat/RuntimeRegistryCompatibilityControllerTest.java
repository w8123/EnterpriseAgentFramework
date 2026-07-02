package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.registry.RuntimeRegistryEntry;
import com.enterprise.ai.runtime.registry.RuntimeEmbeddedDispatchRequest;
import com.enterprise.ai.runtime.registry.RuntimeEmbeddedDispatchResult;
import com.enterprise.ai.runtime.registry.RuntimeEmbeddedDispatchService;
import com.enterprise.ai.runtime.registry.RuntimeRegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeRegistryCompatibilityControllerTest {

    @Test
    void keepsRuntimeRegistryPublicRouteShapeOnRuntimeService() throws Exception {
        Method list = RuntimeRegistryCompatibilityController.class.getDeclaredMethod("listRuntimes");
        Method dispatch = RuntimeRegistryCompatibilityController.class
                .getDeclaredMethod("dispatchEmbedded", RuntimeEmbeddedDispatchRequest.class);

        assertArrayEquals(new String[] {"/api/runtimes"}, list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtimes/embedded/dispatch"},
                dispatch.getAnnotation(PostMapping.class).value());
    }

    @Test
    void delegatesRuntimeRegistryListToRuntimeService() {
        RuntimeRegistryService service = mock(RuntimeRegistryService.class);
        RuntimeRegistryCompatibilityController controller = new RuntimeRegistryCompatibilityController(
                service,
                mock(RuntimeEmbeddedDispatchService.class));
        RuntimeRegistryEntry entry = RuntimeRegistryEntry.platformLangGraph4j();
        when(service.listRuntimes()).thenReturn(List.of(entry));

        ResponseEntity<List<RuntimeRegistryEntry>> response = controller.listRuntimes();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(entry), response.getBody());
        verify(service).listRuntimes();
    }

    @Test
    void dispatchEmbeddedUsesRuntimeDispatchService() {
        RuntimeRegistryService service = mock(RuntimeRegistryService.class);
        RuntimeEmbeddedDispatchService dispatchService = mock(RuntimeEmbeddedDispatchService.class);
        RuntimeRegistryCompatibilityController controller = new RuntimeRegistryCompatibilityController(
                service,
                dispatchService);
        RuntimeEmbeddedDispatchRequest request = new RuntimeEmbeddedDispatchRequest(
                "orders",
                "inst-1",
                "order-agent",
                "hello",
                "session-1",
                "user-1",
                Map.of("traceId", "trace-1"),
                Map.of("graphSpec", Map.of("entry", "start")));
        RuntimeEmbeddedDispatchResult expected = new RuntimeEmbeddedDispatchResult(
                true,
                "accepted",
                "orders",
                "inst-1",
                "http://orders/eaf/runtime/embedded/execute",
                List.of("remote accepted"),
                Map.of("remote", true),
                null,
                null);
        when(dispatchService.dispatch(request)).thenReturn(expected);

        ResponseEntity<RuntimeEmbeddedDispatchResult> response = controller.dispatchEmbedded(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
        verify(dispatchService).dispatch(request);
    }
}
