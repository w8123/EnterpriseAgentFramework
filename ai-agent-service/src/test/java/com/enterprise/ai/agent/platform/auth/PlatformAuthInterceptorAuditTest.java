package com.enterprise.ai.agent.platform.auth;

import com.enterprise.ai.agent.governance.GuardDecisionLogService;
import com.enterprise.ai.agent.platform.auth.AiCodingKeyContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void aiCodingAccessSessionReportWithKeyBypassesPlatformLogin() throws Exception {
        PlatformAuthInterceptor interceptor = new PlatformAuthInterceptor(
                mock(PlatformAuthService.class),
                new PlatformAuthorizationService(),
                mock(GuardDecisionLogService.class),
                new ObjectMapper());

        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/ai-assist/projects/1/access-sessions/rai_demo/steps/gateway-whitelist/report");
        request.setParameter("aiCodingKey", "rac_valid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void aiCodingAccessSessionChecksRunWithKeyBypassesPlatformLogin() throws Exception {
        PlatformAuthInterceptor interceptor = new PlatformAuthInterceptor(
                mock(PlatformAuthService.class),
                new PlatformAuthorizationService(),
                mock(GuardDecisionLogService.class),
                new ObjectMapper());

        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/ai-assist/projects/1/access-sessions/rai_demo/checks/run");
        request.setParameter("aiCodingKey", "rac_valid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals("rac_valid", AiCodingKeyContext.get());
        interceptor.afterCompletion(request, response, new Object(), null);
        assertNull(AiCodingKeyContext.get());
    }

    @Test
    void aiCodingPageAssistantRegisterPageWithKeyBypassesPlatformLogin() throws Exception {
        PlatformAuthInterceptor interceptor = new PlatformAuthInterceptor(
                mock(PlatformAuthService.class),
                new PlatformAuthorizationService(),
                mock(GuardDecisionLogService.class),
                new ObjectMapper());

        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/ai-assist/projects/1/page-assistant/pages/register");
        request.setParameter("aiCodingKey", "rac_valid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void aiCodingPageAssistantWorkflowAiCodingResultWithKeyBypassesPlatformLogin() throws Exception {
        PlatformAuthInterceptor interceptor = new PlatformAuthInterceptor(
                mock(PlatformAuthService.class),
                new PlatformAuthorizationService(),
                mock(GuardDecisionLogService.class),
                new ObjectMapper());

        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/ai-assist/projects/1/page-assistant/sessions/rai_page/workflow-ai-coding-result");
        request.setParameter("aiCodingKey", "rac_valid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals("rac_valid", AiCodingKeyContext.get());
        interceptor.afterCompletion(request, response, new Object(), null);
        assertNull(AiCodingKeyContext.get());
    }

    @Test
    void aiCodingAgentProvisionWithKeyBypassesPlatformLogin() throws Exception {
        PlatformAuthInterceptor interceptor = new PlatformAuthInterceptor(
                mock(PlatformAuthService.class),
                new PlatformAuthorizationService(),
                mock(GuardDecisionLogService.class),
                new ObjectMapper());

        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/ai-assist/projects/1/agents/provision");
        request.setParameter("aiCodingKey", "rac_valid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void workflowAiCodingContextBypassesPlatformLogin() throws Exception {
        PlatformAuthInterceptor interceptor = new PlatformAuthInterceptor(
                mock(PlatformAuthService.class),
                new PlatformAuthorizationService(),
                mock(GuardDecisionLogService.class),
                new ObjectMapper());

        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/workflows/wf-1/ai-coding/context");
        request.setParameter("aiCodingKey", "rac_valid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals("rac_valid", AiCodingKeyContext.get());
        interceptor.afterCompletion(request, response, new Object(), null);
        assertNull(AiCodingKeyContext.get());
    }

    @Test
    void workflowAiCodingCreateBypassesPlatformLogin() throws Exception {
        PlatformAuthInterceptor interceptor = new PlatformAuthInterceptor(
                mock(PlatformAuthService.class),
                new PlatformAuthorizationService(),
                mock(GuardDecisionLogService.class),
                new ObjectMapper());

        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/workflows/ai-coding/workflows");
        request.addHeader("X-ReachAI-AiCoding-Key", "rac_valid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals("rac_valid", AiCodingKeyContext.get());
    }

    @Test
    void workflowStudioPathRequiresPlatformLogin() throws Exception {
        PlatformAuthInterceptor interceptor = new PlatformAuthInterceptor(
                mock(PlatformAuthService.class),
                new PlatformAuthorizationService(),
                mock(GuardDecisionLogService.class),
                new ObjectMapper());

        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/workflows/studio/generate-draft");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void adjacentWorkflowPathWithAiCodingSuffixDoesNotBypassLogin() throws Exception {
        PlatformAuthInterceptor interceptor = new PlatformAuthInterceptor(
                mock(PlatformAuthService.class),
                new PlatformAuthorizationService(),
                mock(GuardDecisionLogService.class),
                new ObjectMapper());

        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/workflows/wf-1/ai-coding-extra/context");
        request.setParameter("aiCodingKey", "rac_valid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void pageAssistantRegisterPageWithoutAiCodingKeyRequiresPlatformLogin() throws Exception {
        PlatformAuthInterceptor interceptor = new PlatformAuthInterceptor(
                mock(PlatformAuthService.class),
                new PlatformAuthorizationService(),
                mock(GuardDecisionLogService.class),
                new ObjectMapper());

        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/ai-assist/projects/1/page-assistant/pages/register");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
    }
}
