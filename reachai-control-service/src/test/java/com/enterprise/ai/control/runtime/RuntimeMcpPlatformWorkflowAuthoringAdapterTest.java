package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.control.identity.PlatformUserEntity;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeMcpPlatformWorkflowAuthoringAdapterTest {
    @AfterEach
    void resetRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void saveRequiresWritePermissionForTheExactWorkflowProject() {
        RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
        var authorization = new PlatformAuthorizationService(null, null, null, null);
        var access = new RuntimeManagementAccess(new PlatformRequestAuthorization(authorization),
                mock(CapabilityProjectOnboardingClient.class));
        var adapter = new RuntimeMcpPlatformWorkflowAuthoringAdapter(runtime, access, new RuntimeMcpActorScope());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        when(runtime.getWorkflow("orders-wf")).thenReturn(ResponseEntity.ok(Map.of("projectCode", "orders")));
        when(runtime.getWorkflow("billing-wf")).thenReturn(ResponseEntity.ok(Map.of("projectCode", "billing")));
        when(runtime.patchWorkflowAiCoding(eq("orders-wf"), any()))
                .thenReturn(ResponseEntity.ok(Map.of("saved", true)));
        Map<String, Object> body = Map.of("baseRevision", "2026-10-28T10:00", "dryRun", false);

        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> adapter.patch(actor("workflow:write"), "billing-wf", false, body));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        verify(runtime, never()).patchWorkflowAiCoding(eq("billing-wf"), any());
        assertEquals(HttpStatus.OK, adapter.patch(actor("workflow:write"), "orders-wf", false, body).getStatusCode());
        verify(runtime).patchWorkflowAiCoding("orders-wf", body);
    }

    @Test
    void readPermissionCanPreviewButCannotSave() {
        RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
        var authorization = new PlatformAuthorizationService(null, null, null, null);
        var adapter = new RuntimeMcpPlatformWorkflowAuthoringAdapter(runtime,
                new RuntimeManagementAccess(new PlatformRequestAuthorization(authorization),
                        mock(CapabilityProjectOnboardingClient.class)), new RuntimeMcpActorScope());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        when(runtime.getWorkflow("wf")).thenReturn(ResponseEntity.ok(Map.of("projectCode", "orders")));
        when(runtime.patchWorkflowAiCoding(eq("wf"), any())).thenReturn(ResponseEntity.ok(Map.of("saved", false)));
        Map<String, Object> preview = Map.of("baseRevision", "r1", "dryRun", true);
        assertEquals(HttpStatus.OK, adapter.patch(actor("workflow:read"), "wf", true, preview).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.patch(actor("workflow:read"), "wf", false, preview)).getStatusCode());
    }

    @Test
    void createDraftRequiresTheMatchingWriteScopeAndCanonicalProjectIdentity() {
        RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
        var authorization = new PlatformAuthorizationService(null, null, null, null);
        var projects = mock(CapabilityProjectOnboardingClient.class);
        var adapter = new RuntimeMcpPlatformWorkflowAuthoringAdapter(runtime,
                new RuntimeManagementAccess(new PlatformRequestAuthorization(authorization), projects),
                new RuntimeMcpActorScope());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        when(projects.getProjectById(12L)).thenReturn(Map.of("id", 12, "projectCode", "orders"));
        when(projects.getProjectById(13L)).thenReturn(Map.of("id", 13, "projectCode", "billing"));
        Map<String, Object> own = Map.of("projectId", 12L, "projectCode", "orders");
        Map<String, Object> other = Map.of("projectId", 13L, "projectCode", "billing");
        when(runtime.createWorkflow(own)).thenReturn(ResponseEntity.ok(Map.of("id", "wf")));

        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.createDraft(actor("workflow:read"), own)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.createDraft(actor("workflow:write"), other)).getStatusCode());
        assertThrows(IllegalArgumentException.class, () -> adapter.createDraft(actor("workflow:write"),
                Map.of("projectId", 13L, "projectCode", "orders")));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.createDraft(actor("workflow:write"), Map.of())).getStatusCode());
        verify(runtime, never()).createWorkflow(other);
        assertEquals(HttpStatus.OK, adapter.createDraft(actor("workflow:write"), own).getStatusCode());
        verify(runtime).createWorkflow(own);
    }

    private static PlatformAuthenticatedSession actor(String permission) {
        var user = new PlatformUserEntity();
        user.setId(7L);
        var grant = new PlatformPermissionGrant(permission, "PROJECT", "orders");
        return new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "pai_19",
                LocalDateTime.now().plusDays(1), List.of("OPERATOR"), List.of(permission), List.of(grant));
    }
}
