package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.runtime.RuntimeAgentDraftGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControlProjectAgentProvisioningDraftGatewayTest {
    private final ControlAiCodingAgentDraftGateway projects = mock(ControlAiCodingAgentDraftGateway.class);
    private final RuntimeAgentDraftGateway platform = mock(RuntimeAgentDraftGateway.class);
    private final PlatformRequestAuthorization authorization = new PlatformRequestAuthorization(new PlatformAuthorizationService(null, null, null, null));
    private final ControlProjectAgentProvisioningDraftGateway gateway = new ControlProjectAgentProvisioningDraftGateway(projects, platform, authorization, new ObjectMapper());
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }

    @Test void platformUsesItsOriginalScopedProvisioningPermissionAndActor() {
        var actor = session("orders");
        var request = bind(); request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, actor);
        var context = new java.util.LinkedHashMap<String, Object>();
        context.put("agentId", "agent-1"); context.put("projectId", 7L); context.put("projectCode", "orders");
        context.put("revision", "a".repeat(64)); context.put("base", null);
        when(platform.context("agent-1", 7L, "orders", actor)).thenReturn(ResponseEntity.ok(context));
        assertEquals(context, gateway.open(7L, "orders").context("agent-1"));
        verify(platform).context("agent-1", 7L, "orders", actor);
        verifyNoInteractions(projects);
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> gateway.open(8L, "billing")).getStatusCode().value());
    }

    @Test void verifiedProjectUsesOnlyItsOwnChannelAndNeverFallsBack() {
        var request = bind();
        request.setAttribute(VerifiedAiCodingProject.REQUEST_ATTRIBUTE, VerifiedAiCodingProject.fromVerifiedKey(7L, "fixture-key"));
        when(projects.context(7L, "orders", "agent-1")).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT));
        var access = gateway.open(7L, "orders");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> access.context("agent-1")).getStatusCode().value());
        verify(projects, times(1)).context(7L, "orders", "agent-1");
        assertThrows(ResponseStatusException.class, () -> gateway.open(8L, "orders"));
        verifyNoInteractions(platform);
    }

    @Test void rawHeadersAndAmbiguousDomainsCannotSelectAnIdentity() {
        var request = bind(); request.addHeader("X-ReachAI-AiCoding-Key", "fixture-key");
        assertThrows(ResponseStatusException.class, () -> gateway.open(7L, "orders"));
        request.setAttribute(VerifiedAiCodingProject.REQUEST_ATTRIBUTE, VerifiedAiCodingProject.fromVerifiedKey(7L, "fixture-key"));
        request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session("orders"));
        assertThrows(ResponseStatusException.class, () -> gateway.open(7L, "orders"));
        verifyNoInteractions(platform, projects);
    }

    @Test void platformResponseFromAnotherProjectCannotBeUsedForInitialization() {
        var actor = session("orders"); var request = bind();
        request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, actor);
        when(platform.context("agent-1", 7L, "orders", actor)).thenReturn(ResponseEntity.ok(Map.of("projectCode", "billing")));
        assertEquals(502, assertThrows(ResponseStatusException.class, () -> gateway.open(7L, "orders").context("agent-1")).getStatusCode().value());
        verifyNoInteractions(projects);
    }
    private MockHttpServletRequest bind() {
        var request = new MockHttpServletRequest(); RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request)); return request;
    }
    private PlatformAuthenticatedSession session(String code) {
        return new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "Operator"), "session", null,
                List.of(), List.of(), List.of(new PlatformPermissionGrant(PlatformPermissions.PLATFORM_WRITE, "PROJECT", code)));
    }
}
