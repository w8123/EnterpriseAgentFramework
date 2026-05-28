package com.enterprise.ai.agent.platform.auth;

import com.enterprise.ai.agent.governance.GuardDecisionLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformAuthInterceptorAuditTest {

    @Test
    void unauthenticatedManagementRequestIsWrittenToGuardAudit() throws Exception {
        PlatformAuthService authService = mock(PlatformAuthService.class);
        PlatformAuthorizationService authorizationService = new PlatformAuthorizationService();
        GuardDecisionLogService audit = mock(GuardDecisionLogService.class);
        PlatformAuthInterceptor interceptor = new PlatformAuthInterceptor(
                authService,
                authorizationService,
                audit,
                new ObjectMapper());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(audit).record(
                eq(null),
                eq("PLATFORM_RBAC"),
                eq("API"),
                eq("/api/agents"),
                eq("DENY"),
                eq("platform login required"),
                any(Map.class));
    }

    @Test
    void forbiddenManagementRequestIsWrittenToGuardAudit() throws Exception {
        PlatformAuthService authService = mock(PlatformAuthService.class);
        PlatformAuthorizationService authorizationService = new PlatformAuthorizationService();
        GuardDecisionLogService audit = mock(GuardDecisionLogService.class);
        PlatformAuthInterceptor interceptor = new PlatformAuthInterceptor(
                authService,
                authorizationService,
                audit,
                new ObjectMapper());
        PlatformPrincipal principal = new PlatformPrincipal(2L, "auditor", "Auditor", Set.of("AUDITOR"), Set.of("platform:read"));
        when(authService.authenticate("token")).thenReturn(Optional.of(principal));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agents");
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(audit).record(
                eq(null),
                eq("PLATFORM_RBAC"),
                eq("API"),
                eq("/api/agents"),
                eq("DENY"),
                eq("platform permission denied"),
                any(Map.class));
    }
}
