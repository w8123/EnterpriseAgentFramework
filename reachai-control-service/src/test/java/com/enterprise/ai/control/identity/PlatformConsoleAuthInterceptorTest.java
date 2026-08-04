package com.enterprise.ai.control.identity;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformConsoleAuthInterceptorTest {

    @Test
    void rejectsMissingOrInvalidPlatformLogin() {
        PlatformBearerAuthService authService =
                mock(PlatformBearerAuthService.class);
        when(authService.resolveBearerUser(null))
                .thenReturn(Optional.empty());
        PlatformConsoleAuthInterceptor interceptor =
                new PlatformConsoleAuthInterceptor(authService);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> interceptor.preHandle(
                        new MockHttpServletRequest(
                                "GET",
                                "/api/ai-coding-console/tasks"),
                        new MockHttpServletResponse(),
                        new Object()));

        assertEquals(401, error.getStatusCode().value());
    }

    @Test
    void attachesAuthenticatedUserAndAllowsConsoleRequest() {
        PlatformBearerAuthService authService =
                mock(PlatformBearerAuthService.class);
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(7L);
        when(authService.resolveBearerUser("Bearer platform-token"))
                .thenReturn(Optional.of(user));
        PlatformConsoleAuthInterceptor interceptor =
                new PlatformConsoleAuthInterceptor(authService);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/ai-coding-console/tasks");
        request.addHeader(
                HttpHeaders.AUTHORIZATION,
                "Bearer platform-token");

        assertTrue(interceptor.preHandle(
                request,
                new MockHttpServletResponse(),
                new Object()));
        assertSame(
                user,
                request.getAttribute(
                        PlatformConsoleAuthInterceptor.USER_REQUEST_ATTRIBUTE));
    }

    @Test
    void allowsCorsPreflightWithoutConsumingLoginSession() {
        PlatformConsoleAuthInterceptor interceptor =
                new PlatformConsoleAuthInterceptor(
                        mock(PlatformBearerAuthService.class));
        assertTrue(interceptor.preHandle(
                new MockHttpServletRequest(
                        "OPTIONS",
                        "/api/ai-coding-console/tasks"),
                new MockHttpServletResponse(),
                new Object()));
    }
}
