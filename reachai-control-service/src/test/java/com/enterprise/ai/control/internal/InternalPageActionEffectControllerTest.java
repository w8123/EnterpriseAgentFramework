package com.enterprise.ai.control.internal;

import com.enterprise.ai.control.internalauth.ControlInternalServiceAuthVerifier;
import com.enterprise.ai.control.platform.PageActionEffectReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InternalPageActionEffectControllerTest {
    @Test void runtimeAuthCoversExactRequestBodyBeforeLookup() {
        var auth = mock(ControlInternalServiceAuthVerifier.class);
        var reviews = mock(PageActionEffectReviewService.class);
        var controller = new InternalPageActionEffectController(auth, reviews, new ObjectMapper());
        var request = mock(HttpServletRequest.class);
        byte[] body = "{\"projectCode\":\"mall\",\"pageKey\":\"profile\",\"actionKey\":\"fill\"}".getBytes(StandardCharsets.UTF_8);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED)).when(auth).requireRuntime(request, body);
        assertThrows(ResponseStatusException.class, () -> controller.resolve(request, body));
        verifyNoInteractions(reviews);
        doNothing().when(auth).requireRuntime(request, body);
        when(reviews.resolveApproved("mall", "profile", "fill")).thenReturn(Optional.empty());
        assertEquals(404, controller.resolve(request, body).getStatusCode().value());
        verify(auth, times(2)).requireRuntime(request, body);
    }
}
