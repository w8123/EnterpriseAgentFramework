package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.workflow.aicoding.AiCodingAttachmentException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimePageAssistantWorkflowControllerTest {

    @Test
    void attachDelegatesToService() {
        RuntimePageAssistantWorkflowAttachmentService service = mock(RuntimePageAssistantWorkflowAttachmentService.class);
        RuntimePageAssistantWorkflowController controller = new RuntimePageAssistantWorkflowController(service);
        RuntimePageAssistantWorkflowAttachRequest request =
                new RuntimePageAssistantWorkflowAttachRequest(7L, "orders", "agent-1", "model-1", "wizard");
        RuntimePageAssistantWorkflowAttachment attachment = new RuntimePageAssistantWorkflowAttachment(
                "agent-1", "orders-page-copilot", "wf-1", "orders-page-assistant",
                "orders_page_assistant", 21L, 2, "ACTIVE", true);
        when(service.attachPublishedPageWorkflow("wf-1", request)).thenReturn(attachment);

        ResponseEntity<?> response = controller.attachPageAssistantWorkflowTool("wf-1", request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(attachment, response.getBody());
        RuntimePageAssistantWorkflowAttachment body = (RuntimePageAssistantWorkflowAttachment) response.getBody();
        assertEquals("orders_page_assistant", body.toolName());
        assertEquals("wf-1", body.workflowId());
        assertEquals(21L, body.configVersionId());
        assertEquals("ACTIVE", body.configStatus());
        verify(service).attachPublishedPageWorkflow("wf-1", request);
    }

    @Test
    void returnsStableErrorEnvelopeForUnsupportedType() {
        RuntimePageAssistantWorkflowAttachmentService service = mock(RuntimePageAssistantWorkflowAttachmentService.class);
        RuntimePageAssistantWorkflowController controller = new RuntimePageAssistantWorkflowController(service);
        RuntimePageAssistantWorkflowAttachRequest request =
                new RuntimePageAssistantWorkflowAttachRequest(7L, "orders", "agent-1", "model-1", "wizard");
        when(service.attachPublishedPageWorkflow("wf-1", request))
                .thenThrow(new AiCodingAttachmentException(
                        "WORKFLOW_TYPE_NOT_SUPPORTED",
                        "Page Assistant attach endpoint only accepts PAGE_ASSISTANT, got: CHAT"));

        ResponseEntity<?> response = controller.attachPageAssistantWorkflowTool("wf-1", request);

        assertEquals(400, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("ai-coding-error.v1", body.get("schema"));
        assertEquals("WORKFLOW_TYPE_NOT_SUPPORTED", body.get("code"));
    }
}
