package com.enterprise.ai.control.a2a.api.internal;

import com.enterprise.ai.control.a2a.application.outbound.A2aOutboundDelegationService;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.internalauth.ControlInternalServiceAuthVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class A2aOutboundDelegationInternalControllerTest {

    private final ControlInternalServiceAuthVerifier auth =
            mock(ControlInternalServiceAuthVerifier.class);
    private final A2aOutboundDelegationService service = mock(A2aOutboundDelegationService.class);
    private final A2aOutboundDelegationInternalController controller =
            new A2aOutboundDelegationInternalController(auth, service, new ObjectMapper());
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @Test
    void verifiesTheExactBodyBeforeAttemptingJsonParsing() {
        byte[] malformed = "{\"messageId\":\"m-1\",".getBytes(StandardCharsets.UTF_8);

        assertThrows(A2aDomainException.class, () -> controller.send(request, malformed));

        ArgumentCaptor<byte[]> exactBody = ArgumentCaptor.forClass(byte[].class);
        verify(auth).requireRuntime(eq(request), exactBody.capture());
        assertArrayEquals(malformed, exactBody.getValue());
        verify(service, never()).send(any());
    }

    @Test
    void authenticationFailureWinsEvenWhenTheBodyIsMalformed() {
        byte[] malformed = "{".getBytes(StandardCharsets.UTF_8);
        doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid signature"))
                .when(auth).requireRuntime(request, malformed);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> controller.send(request, malformed));

        assertEquals(HttpStatus.UNAUTHORIZED, failure.getStatusCode());
        verify(service, never()).send(any());
    }
}
