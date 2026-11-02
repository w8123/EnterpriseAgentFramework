package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RuntimeWorkflowPublicationGatewayTest {
    @Test void signsServerDerivedOwnerAndRequiresBothPermissionsInThatProject() throws Exception {
        var runtime = mock(RuntimeProxyClient.class);
        var projects = mock(CapabilityProjectOnboardingClient.class);
        when(projects.getProjectById(12L)).thenReturn(Map.of("id", 12L, "projectCode", "orders"));
        when(runtime.getWorkflow("wf-1")).thenReturn(ResponseEntity.ok(Map.of("id", "wf-1", "projectId", 12, "projectCode", "orders")));
        var access = new RuntimeManagementAccess(new PlatformRequestAuthorization(new PlatformAuthorizationService(null, null, null, null)), projects);
        var json = new ObjectMapper();
        var gateway = new RuntimeWorkflowPublicationGateway(runtime, access, new InternalServiceAuthSigner("fixture-signing-key-at-least-32-bytes"), json);
        var split = actor("billing");
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> gateway.context(split, "wf-1", "revision", "v1", null)).getStatusCode().value());
        var owner = actor("orders");
        when(runtime.workflowPublicationContext(any(), any())).thenAnswer(call -> {
            Map<String, String> headers = call.getArgument(0);
            Map<?, ?> command = json.readValue((byte[]) call.getArgument(1), Map.class);
            assertEquals("42", headers.get(InternalServiceAuthHeaders.IDENTITY_USER_ID));
            assertEquals("orders", headers.get(InternalServiceAuthHeaders.IDENTITY_TENANT_ID));
            assertEquals(12, command.get("projectId"));
            assertEquals(1, command.get("contractVersion"));
            assertFalse(command.containsKey("publishedBy"));
            return ResponseEntity.ok(Map.of("publicationDigest", "a".repeat(64)));
        });
        assertEquals(200, gateway.context(owner, "wf-1", "revision", "v1", null).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> gateway.publish(owner, "wf-1", "revision", "v1", null, 13L, "billing", "a".repeat(64))).getStatusCode().value());
        when(runtime.publishApprovedWorkflow(any(), any())).thenReturn(ResponseEntity.ok(Map.of("workflowId", "wf-1")));
        assertEquals(200, gateway.publish(owner, "wf-1", "revision", "v1", null, 12L, "orders", "a".repeat(64)).getStatusCode().value());
        verify(runtime, times(1)).publishApprovedWorkflow(any(), any());
    }

    private static PlatformAuthenticatedSession actor(String readProject) {
        return new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null,
                List.of(), List.of("workflow:read", "workflow:publish"), List.of(
                new PlatformPermissionGrant("workflow:read", "PROJECT", readProject),
                new PlatformPermissionGrant("workflow:publish", "PROJECT", "orders")));
    }
}
