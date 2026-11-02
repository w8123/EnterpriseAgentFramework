package com.enterprise.ai.control.internal;

import com.enterprise.ai.control.internalauth.ControlInternalServiceAuthVerifier;
import com.enterprise.ai.control.platform.PlatformPageBridgeCommandService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalPageBridgeCommandControllerTest {
    @Test void requiresRuntimeAuthenticationBeforeParsingOrDispatching() {
        var service = mock(PlatformPageBridgeCommandService.class);
        var verifier = mock(ControlInternalServiceAuthVerifier.class);
        var request = mock(HttpServletRequest.class);
        byte[] body = "{\"sessionId\":\"embed-1\"}".getBytes(StandardCharsets.UTF_8);
        doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED)).when(verifier).requireRuntime(request, body);
        var controller = new InternalPageBridgeCommandController(service, verifier, new ObjectMapper());
        assertThrows(ResponseStatusException.class, () -> controller.execute(request, body));
        assertThrows(ResponseStatusException.class, () -> controller.resolveContext(request, body));
        verifyNoInteractions(service);
    }

    @Test void authenticatedBodyIsParsedAfterVerification() {
        var service = mock(PlatformPageBridgeCommandService.class);
        var verifier = mock(ControlInternalServiceAuthVerifier.class);
        var request = mock(HttpServletRequest.class);
        byte[] body = "{\"sessionId\":\"embed-1\",\"projectCode\":\"erp\"}".getBytes(StandardCharsets.UTF_8);
        var controller = new InternalPageBridgeCommandController(service, verifier, new ObjectMapper());
        controller.resolveContext(request, body);
        verify(verifier).requireRuntime(request, body);
        verify(service).resolveContext(argThat(value -> "embed-1".equals(value.sessionId()) && "erp".equals(value.projectCode())));
    }
}
