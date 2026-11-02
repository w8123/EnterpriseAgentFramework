package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class RuntimePageAssistantTrustedSmokeControllerTest {
    @Test void refusesAbsentOrNonEmbedIdentityBeforeCallingTheService() {
        var service = mock(RuntimeWorkflowAiCodingService.class);
        var controller = new RuntimePageAssistantTrustedSmokeController(service);
        var request = mock(HttpServletRequest.class);
        var command = new RuntimePageAssistantTrustedSmokeController.Command("wf", "erp", "session", null);
        assertEquals(HttpStatus.UNAUTHORIZED, controller.smoke(request, command).getStatusCode());
        when(request.getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR))
                .thenReturn(new VerifiedInternalServiceAuth("control", "AGENT", "default", "user"));
        assertEquals(HttpStatus.UNAUTHORIZED, controller.smoke(request, command).getStatusCode());
        verifyNoInteractions(service);
    }

    @Test void usesOnlyVerifiedHeaderIdentityForTrustedExecution() {
        var service = mock(RuntimeWorkflowAiCodingService.class);
        var controller = new RuntimePageAssistantTrustedSmokeController(service);
        var request = mock(HttpServletRequest.class);
        when(request.getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR))
                .thenReturn(new VerifiedInternalServiceAuth("control", "EMBED_SESSION", "tenant-a", "Alice"));
        var command = new RuntimePageAssistantTrustedSmokeController.Command("wf", "erp", "session", null);
        assertEquals(HttpStatus.OK, controller.smoke(request, command).getStatusCode());
        verify(service).smokeTestPageAssistantTrusted(eq("wf"), isNull(), eq("erp"), eq("session"),
                argThat(identity -> "tenant-a".equals(identity.tenantId()) && "Alice".equals(identity.userId())
                        && "erp".equals(identity.projectCode())));
    }
}
