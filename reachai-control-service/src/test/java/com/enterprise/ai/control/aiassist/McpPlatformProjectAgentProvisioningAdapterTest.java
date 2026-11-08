package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class McpPlatformProjectAgentProvisioningAdapterTest {
    @Test
    @SuppressWarnings("unchecked")
    void delegatesToTheExistingProjectAuthorizedConsoleEntry() {
        var controller = mock(ControlAiAssistProjectProvisioningController.class);
        var scope = mock(PlatformDelegatedRequestScope.class);
        var actor = mock(PlatformAuthenticatedSession.class);
        var request = mock(HttpServletRequest.class);
        when(scope.run(eq(actor), any())).thenAnswer(call ->
                ((Function<HttpServletRequest, ResponseEntity<?>>) call.getArgument(1)).apply(request));
        when(controller.provision(12L, Map.of(), request)).thenReturn(ResponseEntity.ok(Map.of("createdAgent", true)));
        var response = new McpPlatformProjectAgentProvisioningAdapter(controller, scope).provision(actor, 12L);
        assertEquals(200, response.getStatusCode().value());
        verify(controller).provision(12L, Map.of(), request);
    }
}
