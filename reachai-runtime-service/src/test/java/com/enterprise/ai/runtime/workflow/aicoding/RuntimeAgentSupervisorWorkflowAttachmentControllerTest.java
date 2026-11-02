package com.enterprise.ai.runtime.workflow.aicoding;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowAgentAttachmentPort;

import com.enterprise.ai.runtime.internal.RuntimeAgentSupervisorWorkflowAttachmentService;

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
        RuntimeWorkflowAgentAttachmentPort.AttachRequest request =
                new RuntimeWorkflowAgentAttachmentPort.AttachRequest(
                        "wf-1", null, "orders-page-copilot", "model-1", "Cursor");
        when(service.attachInKnownProject(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("orders"), org.mockito.ArgumentMatchers.any())).thenReturn(new RuntimeWorkflowAgentAttachmentPort.AttachmentResult(
                "workflow-tool-attachment.v1",
                7L,
                "orders",
                new RuntimeWorkflowAgentAttachmentPort.AgentRef("agent-1", "orders-page-copilot"),
                new RuntimeWorkflowAgentAttachmentPort.WorkflowRef("wf-1", "chat-flow", "GENERAL"),
                new RuntimeWorkflowAgentAttachmentPort.ActiveConfigRef(21L, 2, "ACTIVE"),
                "chat_flow",
                true,
                false));

        ResponseEntity<?> response = controller.attach(identity(), 7L, reviewed(request));

        assertEquals(200, response.getStatusCode().value());
        RuntimeWorkflowAgentAttachmentPort.AttachmentResult body =
                (RuntimeWorkflowAgentAttachmentPort.AttachmentResult) response.getBody();
        assertEquals("chat_flow", body.toolName());
        assertEquals("ACTIVE", body.activeConfig().status());
        var captured = org.mockito.ArgumentCaptor.forClass(RuntimeWorkflowAgentAttachmentPort.AttachRequest.class);
        org.mockito.Mockito.verify(service).attachInKnownProject(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("orders"), captured.capture());
        org.junit.jupiter.api.Assertions.assertTrue(captured.getValue().publishedBy().startsWith("project-key:"));
        org.junit.jupiter.api.Assertions.assertNotEquals("Cursor", captured.getValue().publishedBy());
        assertEquals("a".repeat(64), captured.getValue().baseRevision());
    }

    @Test
    void returnsStableAiCodingErrorEnvelope() {
        RuntimeAgentSupervisorWorkflowAttachmentService service =
                mock(RuntimeAgentSupervisorWorkflowAttachmentService.class);
        RuntimeAgentSupervisorWorkflowAttachmentController controller =
                new RuntimeAgentSupervisorWorkflowAttachmentController(service);
        RuntimeWorkflowAgentAttachmentPort.AttachRequest request =
                new RuntimeWorkflowAgentAttachmentPort.AttachRequest(
                        "missing", null, null, null, null);
        when(service.attachInKnownProject(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("orders"), org.mockito.ArgumentMatchers.any())).thenThrow(new AiCodingAttachmentException(
                "WORKFLOW_NOT_FOUND", "workflow not found: missing",
                org.springframework.http.HttpStatus.NOT_FOUND));

        ResponseEntity<?> response = controller.attach(identity(), 7L, reviewed(request));

        assertEquals(404, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("ai-coding-error.v1", body.get("schema"));
        assertEquals("WORKFLOW_NOT_FOUND", body.get("code"));
    }
    @Test void unsignedCallsCannotReachTheAttachmentService() {
        var service = mock(RuntimeAgentSupervisorWorkflowAttachmentService.class);
        var controller = new RuntimeAgentSupervisorWorkflowAttachmentController(service);
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.attach(new org.springframework.mock.web.MockHttpServletRequest(), 7L,
                        new RuntimeWorkflowAgentAttachmentPort.AttachRequest("wf", null, null, null, "forged")));
        org.mockito.Mockito.verifyNoInteractions(service);
    }
    @Test void signedIdentityCannotAttachWithoutReadRevisionAndExplicitTarget() {
        var service = mock(RuntimeAgentSupervisorWorkflowAttachmentService.class);
        var controller = new RuntimeAgentSupervisorWorkflowAttachmentController(service);
        var response = controller.attach(identity(), 7L, new RuntimeWorkflowAgentAttachmentPort.AttachRequest("wf", "agent-1", null, null, "forged"));
        assertEquals(400, response.getStatusCode().value());
        org.mockito.Mockito.verifyNoInteractions(service);
    }
    private RuntimeWorkflowAgentAttachmentPort.AttachRequest reviewed(RuntimeWorkflowAgentAttachmentPort.AttachRequest request) {
        return new RuntimeWorkflowAgentAttachmentPort.AttachRequest(request.workflowId(), "agent-1", null, request.modelInstanceId(), request.publishedBy(),
                null, null, null, null, null, null, null, null, null, "a".repeat(64));
    }
    private org.springframework.mock.web.MockHttpServletRequest identity() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth.REQUEST_ATTR,
                new com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth(
                        com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders.CALLER_CONTROL,
                        com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL,
                        "orders", "ai-coding-" + "a".repeat(64)));
        return request;
    }
}
