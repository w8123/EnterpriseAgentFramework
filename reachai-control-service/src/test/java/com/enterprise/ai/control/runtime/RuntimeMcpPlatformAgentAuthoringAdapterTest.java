package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.agentskill.AgentSkillBindingSnapshotAssembler;
import com.enterprise.ai.control.identity.*;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RuntimeMcpPlatformAgentAuthoringAdapterTest {
    @AfterEach void resetRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test void createsOnlyWithinTheDelegatedCanonicalProjectAndRestoresTheRequest() {
        var runtime = mock(RuntimeProxyClient.class);
        var projects = mock(CapabilityProjectOnboardingClient.class);
        var controller = new ControlRuntimePublicController(runtime);
        controller.setRuntimeManagementAccess(new RuntimeManagementAccess(
                new PlatformRequestAuthorization(new PlatformAuthorizationService(null, null, null, null)), projects));
        var adapter = new RuntimeMcpPlatformAgentAuthoringAdapter(controller, new RuntimeMcpActorScope());
        var request = new MockHttpServletRequest("POST", "/mcp/platform");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var actor = actor("agent:write");
        when(projects.getProjectById(12L)).thenReturn(Map.of("id", 12L, "projectCode", "orders"));
        when(projects.getProjectById(13L)).thenReturn(Map.of("id", 13L, "projectCode", "billing"));
        var own = Map.<String, Object>of("id", "1234567890abcdef1234567890abcdef", "name", "订单助手",
                "keySlug", "orders-assistant", "projectId", 12L, "projectCode", "orders", "enabled", true);
        doAnswer(invocation -> {
            assertSame(actor, request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE));
            return ResponseEntity.ok(Map.of("id", own.get("id"), "projectCode", "orders"));
        }).when(runtime).createAgent(own);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.create(actor("agent:read"), own)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.create(actor, Map.of("projectId", 13L, "projectCode", "billing"))).getStatusCode());
        assertThrows(IllegalArgumentException.class,
                () -> adapter.create(actor, Map.of("projectId", 13L, "projectCode", "orders")));
        assertEquals(HttpStatus.OK, adapter.create(actor, own).getStatusCode());
        assertNull(request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE));
        verify(runtime, times(1)).createAgent(any());
    }

    @Test void draftAccessRechecksProjectAndRejectsAChangedOwnerResponse() {
        var runtime = mock(RuntimeProxyClient.class);
        var projects = mock(CapabilityProjectOnboardingClient.class);
        var gateway = mock(RuntimeAgentDraftGateway.class);
        var controller = new ControlRuntimePublicController(runtime);
        controller.setRuntimeManagementAccess(new RuntimeManagementAccess(
                new PlatformRequestAuthorization(new PlatformAuthorizationService(null, null, null, null)), projects));
        controller.setAgentDraftGateway(gateway);
        var adapter = new RuntimeMcpPlatformAgentAuthoringAdapter(controller, new RuntimeMcpActorScope());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        var reader = actor("agent:read"); var writer = actor("agent:write");
        when(projects.getProjectById(12L)).thenReturn(Map.of("id", 12L, "projectCode", "orders"));
        when(projects.getProjectById(13L)).thenReturn(Map.of("id", 13L, "projectCode", "billing"));
        when(runtime.getAgent("own")).thenReturn(ResponseEntity.ok(Map.of("id", "own", "projectId", 12L, "projectCode", "orders")));
        when(runtime.getAgent("other")).thenReturn(ResponseEntity.ok(Map.of("id", "other", "projectId", 13L, "projectCode", "billing")));
        when(gateway.context("own", 12L, "orders", reader)).thenReturn(ResponseEntity.ok(
                Map.of("agentId", "own", "projectId", 12L, "projectCode", "orders", "revision", "a".repeat(64))));
        when(gateway.save(eq("own"), eq(12L), eq("orders"), eq("a".repeat(64)), any(), eq(writer)))
                .thenReturn(ResponseEntity.ok(Map.of("agentId", "own", "projectId", 13L, "projectCode", "billing", "revision", "b".repeat(64))));
        assertEquals(HttpStatus.OK, adapter.readDraft(reader, "own").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> adapter.readDraft(reader, "other")).getStatusCode());
        assertEquals(HttpStatus.BAD_GATEWAY, adapter.saveDraft(writer, "own", "a".repeat(64), Map.of("systemPrompt", "new")).getStatusCode());
        verify(gateway, never()).context(eq("other"), any(), any(), any());
    }

    @Test void conditionalCopyRequiresProjectWritePermissionAndDoesNotCacheTheDraft() {
        var runtime = mock(RuntimeProxyClient.class);
        var projects = mock(CapabilityProjectOnboardingClient.class);
        var gateway = mock(RuntimeAgentDraftGateway.class);
        var controller = new ControlRuntimePublicController(runtime);
        controller.setRuntimeManagementAccess(new RuntimeManagementAccess(
                new PlatformRequestAuthorization(new PlatformAuthorizationService(null, null, null, null)), projects));
        controller.setAgentDraftGateway(gateway);
        var request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(projects.getProjectById(12L)).thenReturn(Map.of("id", 12L, "projectCode", "orders"));
        when(runtime.getAgent("own")).thenReturn(ResponseEntity.ok(Map.of("id", "own", "projectId", 12L, "projectCode", "orders")));
        request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, actor("agent:read"));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> controller.copyAgentDraftIfUnchanged(
                "own", 8L, Map.of("baseRevision", "a".repeat(64)))).getStatusCode());
        var writer = actor("agent:write");
        request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, writer);
        when(gateway.copy("own", 12L, "orders", "a".repeat(64), 8L, writer)).thenReturn(ResponseEntity.ok(
                Map.of("agentId", "own", "projectId", 12L, "projectCode", "orders", "revision", "b".repeat(64))));
        var response = controller.copyAgentDraftIfUnchanged("own", 8L, Map.of("baseRevision", "a".repeat(64)));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class, () -> controller.copyAgentDraftIfUnchanged(
                "own", 8L, Map.of("baseRevision", "a".repeat(64), "projectCode", "billing"))).getStatusCode());
        verify(gateway, times(1)).copy(any(), any(), any(), any(), any(), any());
    }

    @Test void conditionalPublishStillRevalidatesSkillSnapshotsBeforeDispatch() {
        var runtime = mock(RuntimeProxyClient.class);
        var projects = mock(CapabilityProjectOnboardingClient.class);
        var gateway = mock(RuntimeAgentDraftGateway.class);
        var assembler = mock(AgentSkillBindingSnapshotAssembler.class);
        var controller = new ControlRuntimePublicController(runtime, null, null, null, null, null, assembler);
        controller.setRuntimeManagementAccess(new RuntimeManagementAccess(
                new PlatformRequestAuthorization(new PlatformAuthorizationService(null, null, null, null)), projects));
        controller.setAgentDraftGateway(gateway);
        var request = new MockHttpServletRequest();
        request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, actor("agent:publish"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(projects.getProjectById(12L)).thenReturn(Map.of("id", 12L, "projectCode", "orders"));
        when(runtime.getAgent("own")).thenReturn(ResponseEntity.ok(Map.of("id", "own", "projectId", 12L, "projectCode", "orders")));
        when(runtime.listAgentConfigVersions("own")).thenReturn(ResponseEntity.ok(List.of(
                Map.of("id", 8L, "skills", List.of(Map.of("skillId", "before"))))));
        when(assembler.assemble(eq(request), any(), eq("orders"))).thenReturn(Map.of("skills", List.of(Map.of("skillId", "after"))));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> controller.publishAgentDraftIfUnchanged(
                request, "own", 8L, Map.of("baseRevision", "a".repeat(64)))).getStatusCode());
        verify(gateway, never()).publish(any(), any(), any(), any(), any(), any());
        request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, actor("agent:read", "agent:publish"));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> controller.platformAgentPublishApproved(
                request, "own", 8L, "a".repeat(64), 12L, "orders", Map.of())).getStatusCode());
        verify(gateway, never()).publishApproved(any(), any(), any(), any(), any(), any(), any());
    }

    @Test void publicationPreviewAndDispatchRequirePublishPermissionAndTheReviewedOwner() {
        var runtime = mock(RuntimeProxyClient.class);
        var projects = mock(CapabilityProjectOnboardingClient.class);
        var gateway = mock(RuntimeAgentDraftGateway.class);
        var controller = new ControlRuntimePublicController(runtime);
        controller.setRuntimeManagementAccess(new RuntimeManagementAccess(
                new PlatformRequestAuthorization(new PlatformAuthorizationService(null, null, null, null)), projects));
        controller.setAgentDraftGateway(gateway);
        var adapter = new RuntimeMcpPlatformAgentAuthoringAdapter(controller, new RuntimeMcpActorScope());
        var request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(projects.getProjectById(12L)).thenReturn(Map.of("id", 12L, "projectCode", "orders"));
        when(runtime.getAgent("own")).thenReturn(ResponseEntity.ok(Map.of("id", "own", "projectId", 12L, "projectCode", "orders")));
        var reader = actor("agent:read"); var publisher = actor("agent:read", "agent:publish");
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.publicationContext(reader, "own", 8L, "a".repeat(64))).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.publishApproved(reader, "own", 8L, "a".repeat(64), 12L, "orders", Map.of())).getStatusCode());
        var splitScopes = new PlatformAuthenticatedSession(publisher.user(), "pai_19", publisher.expiresAt(), publisher.roles(), publisher.permissions(),
                List.of(new PlatformPermissionGrant("agent:read", "PROJECT", "billing"),
                        new PlatformPermissionGrant("agent:publish", "PROJECT", "orders")));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.publicationContext(splitScopes, "own", 8L, "a".repeat(64))).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.publishApproved(splitScopes, "own", 8L, "a".repeat(64), 12L, "orders", Map.of())).getStatusCode());
        when(gateway.publicationContext("own", 12L, "orders", "a".repeat(64), 8L, publisher)).thenReturn(ResponseEntity.ok(
                Map.of("agentId", "own", "projectId", 12L, "projectCode", "orders", "revision", "a".repeat(64))));
        assertEquals(HttpStatus.OK, adapter.publicationContext(publisher, "own", 8L, "a".repeat(64)).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, adapter.publishApproved(publisher, "own", 8L, "a".repeat(64), 13L, "billing", Map.of()).getStatusCode());
        verify(gateway, never()).publishApproved(any(), any(), any(), any(), any(), any(), any());
        assertNull(request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE));
    }

    private static PlatformAuthenticatedSession actor(String... permissions) {
        var user = new PlatformUserEntity(); user.setId(7L);
        return new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "pai_19", LocalDateTime.now().plusDays(1),
                List.of("OPERATOR"), List.of(permissions), java.util.Arrays.stream(permissions)
                .map(permission -> new PlatformPermissionGrant(permission, "PROJECT", "orders")).toList());
    }
}
