package com.enterprise.ai.runtime.workflow.aicoding;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeAgentSupervisorWorkflowAttachmentControllerTest {

    @Test
    void returnsAttachmentResultOnSuccess() {
        RuntimeAgentSupervisorWorkflowAttachmentService service =
                mock(RuntimeAgentSupervisorWorkflowAttachmentService.class);
        RuntimeAgentSupervisorWorkflowAttachmentController controller =
                new RuntimeAgentSupervisorWorkflowAttachmentController(service);
        RuntimeAgentSupervisorWorkflowAttachmentService.AttachRequest request =
                new RuntimeAgentSupervisorWorkflowAttachmentService.AttachRequest(
                        "wf-1", null, "orders-page-copilot", "model-1", "Cursor");
        when(service.attach(7L, request)).thenReturn(new RuntimeAgentSupervisorWorkflowAttachmentService.AttachmentResult(
                "workflow-tool-attachment.v1",
                7L,
                "orders",
                new RuntimeAgentSupervisorWorkflowAttachmentService.AgentRef("agent-1", "orders-page-copilot"),
                new RuntimeAgentSupervisorWorkflowAttachmentService.WorkflowRef("wf-1", "chat-flow", "GENERAL"),
                new RuntimeAgentSupervisorWorkflowAttachmentService.ActiveConfigRef(21L, 2, "ACTIVE"),
                "chat_flow",
                true,
                false));

        ResponseEntity<?> response = controller.attach(7L, request);

        assertEquals(200, response.getStatusCode().value());
        RuntimeAgentSupervisorWorkflowAttachmentService.AttachmentResult body =
                (RuntimeAgentSupervisorWorkflowAttachmentService.AttachmentResult) response.getBody();
        assertEquals("chat_flow", body.toolName());
        assertEquals("ACTIVE", body.activeConfig().status());
    }

    @Test
    void returnsStableAiCodingErrorEnvelope() {
        RuntimeAgentSupervisorWorkflowAttachmentService service =
                mock(RuntimeAgentSupervisorWorkflowAttachmentService.class);
        RuntimeAgentSupervisorWorkflowAttachmentController controller =
                new RuntimeAgentSupervisorWorkflowAttachmentController(service);
        RuntimeAgentSupervisorWorkflowAttachmentService.AttachRequest request =
                new RuntimeAgentSupervisorWorkflowAttachmentService.AttachRequest(
                        "missing", null, null, null, null);
        when(service.attach(7L, request)).thenThrow(new AiCodingAttachmentException(
                "WORKFLOW_NOT_FOUND", "workflow not found: missing",
                org.springframework.http.HttpStatus.NOT_FOUND));

        ResponseEntity<?> response = controller.attach(7L, request);

        assertEquals(404, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("ai-coding-error.v1", body.get("schema"));
        assertEquals("WORKFLOW_NOT_FOUND", body.get("code"));
    }
}
