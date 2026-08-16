package com.enterprise.ai.control.identity;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformConsoleAuthInterceptorTest {

    @Test
    void rejectsMissingOrInvalidPlatformLogin() throws Exception {
        PlatformBearerAuthService authService =
                mock(PlatformBearerAuthService.class);
        when(authService.resolveBearerSession(null))
                .thenReturn(Optional.empty());
        PlatformConsoleAuthInterceptor interceptor =
                new PlatformConsoleAuthInterceptor(authService);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(
                new MockHttpServletRequest(
                        "GET",
                        "/api/ai-coding-console/tasks"),
                response,
                new Object()));
        assertEquals(401, response.getStatus());
        assertEquals(
                PlatformConsoleAuthInterceptor.PLATFORM_SESSION_INVALID,
                response.getHeader(PlatformConsoleAuthInterceptor.AUTH_FAILURE_HEADER));
        assertTrue(response.getContentAsString().contains("PLATFORM_SESSION_INVALID"));
        assertTrue(response.getContentAsString().contains("LOGIN_REQUIRED"));
    }

    @Test
    void attachesAuthenticatedUserAndAllowsConsoleRequest() {
        PlatformBearerAuthService authService =
                mock(PlatformBearerAuthService.class);
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(7L);
        PlatformLoginSessionEntity loginSession = new PlatformLoginSessionEntity();
        loginSession.setSessionId("pls_7");
        PlatformAuthenticatedSession session = new PlatformAuthenticatedSession(
                user,
                "pls_7",
                null,
                java.util.List.of("PLATFORM_ADMIN"),
                java.util.List.of("platform:admin"),
                java.util.List.of(new PlatformPermissionGrant("platform:admin", "GLOBAL", "*")));
        when(authService.resolveBearerSession("Bearer platform-token"))
                .thenReturn(Optional.of(session));
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
        assertSame(
                session,
                request.getAttribute(
                        PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE));
    }

    @Test
    void returnsRealHttp401BeforeDispatchingAProtectedController() throws Exception {
        PlatformBearerAuthService authService = mock(PlatformBearerAuthService.class);
        when(authService.resolveBearerSession(null)).thenReturn(Optional.empty());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProtectedProbeController())
                .addInterceptors(new PlatformConsoleAuthInterceptor(authService))
                .build();

        mockMvc.perform(get("/api/workflows/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(
                        PlatformConsoleAuthInterceptor.AUTH_FAILURE_HEADER,
                        PlatformConsoleAuthInterceptor.PLATFORM_SESSION_INVALID));
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

    @RestController
    private static class ProtectedProbeController {

        @GetMapping("/api/workflows/probe")
        String probe() {
            return "unexpected";
        }
    }
}
