package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.identity.ControlAiCodingAccessGuard;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ControlAiCodingAccessInterceptorTest {

    @Test
    void rejectsProjectAiCodingRouteWhenGuardRejects() throws Exception {
        ControlAiCodingAccessGuard guard = mock(ControlAiCodingAccessGuard.class);
        ControlAiCodingAccessInterceptor interceptor = new ControlAiCodingAccessInterceptor(guard);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/ai-coding/projects/7/manifest");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setAttribute(VerifiedAiCodingProject.REQUEST_ATTRIBUTE, VerifiedAiCodingProject.fromVerifiedKey(7L, "stale-key"));
        doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing aiCodingKey"))
                .when(guard).requireProjectAccess(7L, null);

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        assertTrue(response.getContentAsString().contains("AI_CODING_KEY_REQUIRED"));
        assertTrue(response.getContentAsString().contains("INDEPENDENT_PROTOCOL"));
        verify(guard).requireProjectAccess(7L, null);
        assertNull(request.getAttribute(VerifiedAiCodingProject.REQUEST_ATTRIBUTE));
    }

    @Test void verifiedProjectIsRequestScopedAndDoesNotRetainTheKey() throws Exception {
        var guard = mock(ControlAiCodingAccessGuard.class);
        var interceptor = new ControlAiCodingAccessInterceptor(guard);
        var request = new MockHttpServletRequest("POST", "/api/ai-coding/projects/7/agents");
        request.addHeader(ControlAiCodingAccessGuard.AI_CODING_HEADER, "rac_secret");
        var response = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(request, response, new Object()));
        var actor = assertInstanceOf(VerifiedAiCodingProject.class,
                request.getAttribute(VerifiedAiCodingProject.REQUEST_ATTRIBUTE));
        assertEquals(7L, actor.projectId());
        assertEquals(VerifiedAiCodingProject.fromVerifiedKey(7L, "rac_secret"), actor);
        assertFalse(actor.toString().contains("rac_secret"));
        verify(guard).requireProjectAccess(7L, "rac_secret");
        interceptor.afterCompletion(request, response, new Object(), null);
        assertNull(request.getAttribute(VerifiedAiCodingProject.REQUEST_ATTRIBUTE));
    }

    @Test
    void allowsWorkflowAiCodingRouteWhenGuardAccepts() throws Exception {
        ControlAiCodingAccessGuard guard = mock(ControlAiCodingAccessGuard.class);
        ControlAiCodingAccessInterceptor interceptor = new ControlAiCodingAccessInterceptor(guard);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/workflows/wf-1/ai-coding/validate");
        request.addHeader(ControlAiCodingAccessGuard.AI_CODING_HEADER, "rac_secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        verify(guard).requireWorkflowAccess("wf-1", "rac_secret");
    }
}
