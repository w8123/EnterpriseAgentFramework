package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.workflow.RuntimePageAssistantWorkflowBindingService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimePageAssistantWorkflowCompatibilityControllerTest {

    @Test
    void keepsPublicPageAssistantBindRouteOnRuntimeService() throws Exception {
        Method method = RuntimePageAssistantWorkflowCompatibilityController.class
                .getDeclaredMethod("bindPageAssistantWorkflow", String.class, RuntimePageAssistantWorkflowBindRequest.class);

        assertArrayEquals(new String[] {"/api/workflows/{id}/page-assistant/bind"},
                method.getAnnotation(PostMapping.class).value());
    }

    @Test
    void delegatesBindRequestToRuntimeService() {
        RuntimePageAssistantWorkflowBindingService service = mock(RuntimePageAssistantWorkflowBindingService.class);
        RuntimePageAssistantWorkflowCompatibilityController controller =
                new RuntimePageAssistantWorkflowCompatibilityController(service);
        RuntimePageAssistantWorkflowBindRequest request = new RuntimePageAssistantWorkflowBindRequest(
                null,
                "orders",
                "agent-1",
                "orders.list",
                "/orders",
                List.of("open"));
        RuntimePageAssistantWorkflowBinding binding = new RuntimePageAssistantWorkflowBinding(
                "agent-1",
                "orders-page-copilot",
                "wf-1",
                "orders-page-assistant",
                11L);
        when(service.bindExistingPageWorkflow("wf-1", request)).thenReturn(binding);

        ResponseEntity<?> response = controller.bindPageAssistantWorkflow("wf-1", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(binding, response.getBody());
        verify(service).bindExistingPageWorkflow("wf-1", request);
    }

    @Test
    void mapsRejectedBindRequestToBadRequestBody() {
        RuntimePageAssistantWorkflowBindingService service = mock(RuntimePageAssistantWorkflowBindingService.class);
        RuntimePageAssistantWorkflowCompatibilityController controller =
                new RuntimePageAssistantWorkflowCompatibilityController(service);
        RuntimePageAssistantWorkflowBindRequest request = new RuntimePageAssistantWorkflowBindRequest(
                null,
                "orders",
                "agent-1",
                "",
                null,
                List.of());
        when(service.bindExistingPageWorkflow("wf-1", request))
                .thenThrow(new IllegalArgumentException("page key is required"));

        ResponseEntity<?> response = controller.bindPageAssistantWorkflow("wf-1", request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(Map.of("message", "page key is required"), response.getBody());
    }
}
