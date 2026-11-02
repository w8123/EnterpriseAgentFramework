package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import com.enterprise.ai.control.internalauth.InternalServiceAuthRequestAttributes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RuntimeMcpPlatformReadAdapterTest {
    @AfterEach
    void resetRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void readsThroughConsoleProjectChecksAndRestoresTheOriginalRequestContext() {
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        var controller = new ControlRuntimePublicController(runtimeClient);
        var auth = new PlatformAuthorizationService(null, null, null, null);
        controller.setRuntimeManagementAccess(new RuntimeManagementAccess(new PlatformRequestAuthorization(auth),
                mock(CapabilityProjectOnboardingClient.class)));
        var adapter = new RuntimeMcpPlatformReadAdapter(controller, new RuntimeMcpActorScope());
        var request = new MockHttpServletRequest("POST", "/mcp/platform");
        Object previousUser = new Object();
        request.setAttribute(PlatformConsoleAuthInterceptor.USER_REQUEST_ATTRIBUTE, previousUser);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        PlatformAuthenticatedSession actor = actor();
        doAnswer(invocation -> {
            assertSame(actor, request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE));
            assertEquals("7", request.getAttribute(InternalServiceAuthRequestAttributes.PLATFORM_SESSION_ACTOR_ID));
            return ResponseEntity.ok(Map.of("id", "wf-1", "projectCode", "orders"));
        }).when(runtimeClient).getWorkflow("wf-1");
        when(runtimeClient.getWorkflow("other"))
                .thenReturn(ResponseEntity.ok(Map.of("id", "other", "projectCode", "billing")));

        assertEquals(HttpStatus.OK, adapter.getWorkflow(actor, "wf-1").getStatusCode());
        assertSame(previousUser, request.getAttribute(PlatformConsoleAuthInterceptor.USER_REQUEST_ATTRIBUTE));
        assertNull(request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE));
        assertNull(request.getAttribute(InternalServiceAuthRequestAttributes.PLATFORM_SESSION_ACTOR_ID));

        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> adapter.getWorkflow(actor, "other"));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertSame(previousUser, request.getAttribute(PlatformConsoleAuthInterceptor.USER_REQUEST_ATTRIBUTE));
        assertNull(request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE));
    }

    @Test
    void aProjectScopedUserCannotRequestAnUnscopedList() {
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        var controller = new ControlRuntimePublicController(runtimeClient);
        var auth = new PlatformAuthorizationService(null, null, null, null);
        controller.setRuntimeManagementAccess(new RuntimeManagementAccess(new PlatformRequestAuthorization(auth),
                mock(CapabilityProjectOnboardingClient.class)));
        var adapter = new RuntimeMcpPlatformReadAdapter(controller, new RuntimeMcpActorScope());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        when(runtimeClient.listWorkflows(any(), eq("orders"), any(), any(), any()))
                .thenReturn(ResponseEntity.ok(List.of()));

        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.listWorkflows(actor(), null, null, null, null)).getStatusCode());
        assertEquals(HttpStatus.OK, adapter.listWorkflows(actor(), null, "orders", null, null).getStatusCode());
    }

    @Test
    void agentConfigVersionsRequireAnAccessibleExistingAgent() {
        RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
        var gateway = mock(RuntimeAgentDraftGateway.class);
        var controller = new ControlRuntimePublicController(runtime);
        controller.setAgentDraftGateway(gateway);
        controller.setRuntimeManagementAccess(new RuntimeManagementAccess(
                new PlatformRequestAuthorization(new PlatformAuthorizationService(null, null, null, null)),
                mock(CapabilityProjectOnboardingClient.class)));
        var adapter = new RuntimeMcpPlatformReadAdapter(controller, new RuntimeMcpActorScope());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        var user = new PlatformUserEntity(); user.setId(7L);
        var actor = new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "pai_19", LocalDateTime.now().plusDays(1),
                List.of("OPERATOR"), List.of("agent:read"), List.of(new PlatformPermissionGrant("agent:read", "PROJECT", "orders")));
        var own = new java.util.LinkedHashMap<String, Object>();
        own.put("id", "own"); own.put("projectId", null); own.put("projectCode", "orders");
        var other = new java.util.LinkedHashMap<String, Object>();
        other.put("id", "other"); other.put("projectId", null); other.put("projectCode", "billing");
        when(runtime.getAgent("own")).thenReturn(ResponseEntity.ok(own));
        when(runtime.getAgent("other")).thenReturn(ResponseEntity.ok(other));
        when(runtime.getAgent("missing")).thenReturn(ResponseEntity.notFound().build());
        var versions = new java.util.LinkedHashMap<String, Object>();
        versions.put("agentId", "own"); versions.put("projectId", null); versions.put("projectCode", "orders");
        versions.put("versions", List.of(Map.of("status", "DRAFT")));
        when(gateway.versions("own", null, "orders", actor)).thenReturn(ResponseEntity.ok(versions));
        var freshness = new java.util.LinkedHashMap<String, Object>();
        freshness.put("agentId", "own"); freshness.put("projectId", null);
        freshness.put("projectCode", "orders"); freshness.put("staleCount", 1);
        when(runtime.getAgentWorkflowFreshness("own")).thenReturn(ResponseEntity.ok(freshness));
        assertEquals(HttpStatus.OK, adapter.listAgentConfigVersions(actor, "own").getStatusCode());
        assertEquals(HttpStatus.OK, adapter.getAgentWorkflowFreshness(actor, "own").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.listAgentConfigVersions(actor, "other")).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> adapter.listAgentConfigVersions(actor, "missing")).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.getAgentWorkflowFreshness(actor, "other")).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, adapter.getAgentWorkflowFreshness(actor, "missing").getStatusCode());
        verify(gateway, never()).versions(eq("other"), any(), any(), any());
        verify(gateway, never()).versions(eq("missing"), any(), any(), any());
        verify(runtime, never()).getAgentWorkflowFreshness("other");
        verify(runtime, never()).getAgentWorkflowFreshness("missing");
        freshness.put("projectCode", "billing");
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.getAgentWorkflowFreshness(actor, "own")).getStatusCode());
        versions.put("projectCode", "billing");
        assertEquals(HttpStatus.BAD_GATEWAY, adapter.listAgentConfigVersions(actor, "own").getStatusCode());
    }

    private static PlatformAuthenticatedSession actor() {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(7L);
        PlatformPermissionGrant grant = new PlatformPermissionGrant("workflow:read", "PROJECT", "orders");
        return new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "pai_19",
                LocalDateTime.now().plusDays(1), List.of("OPERATOR"), List.of("workflow:read"), List.of(grant));
    }
}
