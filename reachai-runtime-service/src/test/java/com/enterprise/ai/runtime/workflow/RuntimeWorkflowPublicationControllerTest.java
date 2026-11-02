package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeWorkflowPublicationControllerTest {
    @Test void requiresVerifiedControlActorAndMatchingProjectAndDerivesThePublisher() {
        var service = mock(RuntimeWorkflowVersionService.class);
        var controller = new RuntimeWorkflowPublicationController(service);
        var command = new RuntimeWorkflowVersionService.PublicationRequest(1, "wf-1", 12L, "orders", "revision", "v1", null, "a".repeat(64));
        var http = new MockHttpServletRequest();
        assertEquals(401, controller.context(http, command).getStatusCode().value());
        for (var auth : List.of(new VerifiedInternalServiceAuth("runtime", "PLATFORM_SESSION", "orders", "42"),
                new VerifiedInternalServiceAuth(InternalServiceAuthHeaders.CALLER_CONTROL, "A2A_REMOTE_AGENT", "orders", "42"),
                new VerifiedInternalServiceAuth(InternalServiceAuthHeaders.CALLER_CONTROL, "PLATFORM_SESSION", "billing", "42"),
                new VerifiedInternalServiceAuth(InternalServiceAuthHeaders.CALLER_CONTROL, "PLATFORM_SESSION", "orders", "forged"))) {
            http.setAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR, auth);
            assertEquals(401, controller.context(http, command).getStatusCode().value());
            assertEquals(401, controller.publish(http, command).getStatusCode().value());
        }
        verifyNoInteractions(service);
        http.setAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR,
                new VerifiedInternalServiceAuth(InternalServiceAuthHeaders.CALLER_CONTROL, "PLATFORM_SESSION", "orders", "42"));
        assertEquals(200, controller.context(http, command).getStatusCode().value());
        when(service.publishApproved(command, "platform:42")).thenReturn(new RuntimeWorkflowVersionEntity());
        assertEquals(200, controller.publish(http, command).getStatusCode().value());
        verify(service).previewPublication(command);
        verify(service).publishApproved(command, "platform:42");
    }
}
