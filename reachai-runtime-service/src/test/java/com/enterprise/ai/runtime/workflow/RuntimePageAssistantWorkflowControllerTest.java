package com.enterprise.ai.runtime.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimePageAssistantWorkflowControllerTest {

    @Test
    void exposesOnlyWorkflowToolAttachmentRoute() throws Exception {
        Method method = RuntimePageAssistantWorkflowController.class
                .getDeclaredMethod("attachPageAssistantWorkflowTool", String.class,
                        RuntimePageAssistantWorkflowAttachRequest.class);

        assertArrayEquals(new String[] {"/api/workflows/{id}/page-assistant/attach-tool"},
                method.getAnnotation(PostMapping.class).value());
    }

    @Test
    void delegatesAttachmentRequestToRuntimeService() {
        RuntimePageAssistantWorkflowAttachmentService service =
                mock(RuntimePageAssistantWorkflowAttachmentService.class);
        RuntimePageAssistantWorkflowController controller =
                new RuntimePageAssistantWorkflowController(service);
        RuntimePageAssistantWorkflowAttachRequest request = new RuntimePageAssistantWorkflowAttachRequest(
                null, "orders", "agent-1", "model-1", "wizard");
        RuntimePageAssistantWorkflowAttachment attachment = new RuntimePageAssistantWorkflowAttachment(
                "agent-1", "orders-page-copilot", "wf-1", "orders-page-assistant",
                "orders_page_assistant", 21L, 2, "ACTIVE", true);
        when(service.attachPublishedPageWorkflow("wf-1", request)).thenReturn(attachment);

        ResponseEntity<?> response = controller.attachPageAssistantWorkflowTool("wf-1", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(attachment, response.getBody());
        verify(service).attachPublishedPageWorkflow("wf-1", request);
    }

    @Test
    void mapsRejectedAttachmentRequestToBadRequestBody() {
        RuntimePageAssistantWorkflowAttachmentService service =
                mock(RuntimePageAssistantWorkflowAttachmentService.class);
        RuntimePageAssistantWorkflowController controller =
                new RuntimePageAssistantWorkflowController(service);
        RuntimePageAssistantWorkflowAttachRequest request = new RuntimePageAssistantWorkflowAttachRequest(
                null, "orders", "agent-1", null, "wizard");
        when(service.attachPublishedPageWorkflow("wf-1", request))
                .thenThrow(new IllegalArgumentException("modelInstanceId is required"));

        ResponseEntity<?> response = controller.attachPageAssistantWorkflowTool("wf-1", request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(Map.of("message", "modelInstanceId is required"), response.getBody());
    }
}
