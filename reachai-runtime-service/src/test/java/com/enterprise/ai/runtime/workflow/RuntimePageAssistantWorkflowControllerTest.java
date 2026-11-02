package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowAgentAttachmentPort.PageAssistantAttachRequest;
import com.enterprise.ai.runtime.workflow.aicoding.AiCodingAttachmentException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RuntimePageAssistantWorkflowControllerTest {
    private final RuntimePageAssistantWorkflowAttachmentService service = mock(RuntimePageAssistantWorkflowAttachmentService.class);
    private final RuntimePageAssistantWorkflowController controller = new RuntimePageAssistantWorkflowController(service);

    @Test
    void attachRecordsTheSignedPlatformUserAsPublisher() {
        var command = new RuntimePageAssistantWorkflowController.AttachCommand(7L, "orders", null, "model-1", null, "a".repeat(64), false);
        RuntimePageAssistantWorkflowAttachment attachment = new RuntimePageAssistantWorkflowAttachment(
                "agent-1", "orders-page-copilot", "wf-1", "orders-page-assistant",
                "orders_page_assistant", 21L, 2, "ACTIVE", true);
        var expected = new PageAssistantAttachRequest(7L, "orders", null, "model-1", "platform-user:42", null, "a".repeat(64), false);
        when(service.attachReviewed("wf-1", expected)).thenReturn(attachment);

        ResponseEntity<?> response = controller.attach(signed("orders", "42"), "wf-1", command);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(attachment, response.getBody());
        verify(service).attachReviewed("wf-1", expected);
    }

    @Test
    void returnsStableErrorEnvelopeForConflicts() {
        var command = new RuntimePageAssistantWorkflowController.AttachCommand(7L, "orders", null, null, null, "a".repeat(64), false);
        when(service.attachReviewed(eq("wf-1"), any())).thenThrow(new AiCodingAttachmentException(
                "AGENT_DRAFT_CHANGED", "Agent changed", org.springframework.http.HttpStatus.CONFLICT));

        ResponseEntity<?> response = controller.attach(signed("orders", "42"), "wf-1", command);

        assertEquals(409, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("ai-coding-error.v1", body.get("schema"));
        assertEquals("AGENT_DRAFT_CHANGED", body.get("code"));
    }

    @Test
    void rejectsUnsignedProjectKeyOrMismatchedTenant() {
        var command = new RuntimePageAssistantWorkflowController.AttachCommand(7L, "orders", null, null, null, null, true);
        assertThrows(ResponseStatusException.class, () -> controller.attach(new MockHttpServletRequest(), "wf-1", command));
        assertThrows(ResponseStatusException.class, () -> controller.attach(signed("billing", "42"), "wf-1", command));
        var projectKey = new MockHttpServletRequest();
        projectKey.setAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR, new VerifiedInternalServiceAuth(
                InternalServiceAuthHeaders.CALLER_CONTROL, InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL,
                "orders", "ai-coding-" + "a".repeat(64)));
        assertThrows(ResponseStatusException.class, () -> controller.attach(projectKey, "wf-1", command));
        assertThrows(ResponseStatusException.class, () -> controller.context(signed("orders", "42"), "wf-1",
                new RuntimePageAssistantWorkflowController.ProjectCommand(7L, "billing")));
        verifyNoInteractions(service);
    }

    private static MockHttpServletRequest signed(String tenant, String user) {
        var request = new MockHttpServletRequest();
        request.setAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR, new VerifiedInternalServiceAuth(
                InternalServiceAuthHeaders.CALLER_CONTROL, InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, tenant, user));
        return request;
    }
}
