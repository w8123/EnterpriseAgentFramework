package com.enterprise.ai.control.managed;

import com.enterprise.ai.control.internalauth.ControlInternalServiceAuthVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ControlManagedExecutionInternalControllerTest {

    private final ControlInternalServiceAuthVerifier auth =
            mock(ControlInternalServiceAuthVerifier.class);
    private final ControlManagedExecutionInboxService service =
            mock(ControlManagedExecutionInboxService.class);
    private final ControlManagedExecutionInternalController controller =
            new ControlManagedExecutionInternalController(auth, service, new ObjectMapper());
    private final HttpServletRequest httpRequest = mock(HttpServletRequest.class);

    @Test
    void verifiesExactBytesBeforeParsing() {
        byte[] malformed = "{\"eventId\":\"mout_1\",".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> controller.accept(httpRequest, malformed))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(failure -> assertThat(((ResponseStatusException) failure).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        ArgumentCaptor<byte[]> exact = ArgumentCaptor.forClass(byte[].class);
        verify(auth).requireRuntime(eq(httpRequest), exact.capture());
        assertThat(exact.getValue()).isEqualTo(malformed);
        verify(service, never()).accept(any(), any());
    }

    @Test
    void authenticationFailureWinsForMalformedJson() {
        byte[] malformed = "{".getBytes(StandardCharsets.UTF_8);
        doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid signature"))
                .when(auth).requireRuntime(httpRequest, malformed);

        assertThatThrownBy(() -> controller.accept(httpRequest, malformed))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(failure -> assertThat(((ResponseStatusException) failure).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(service, never()).accept(any(), any());
    }
}
