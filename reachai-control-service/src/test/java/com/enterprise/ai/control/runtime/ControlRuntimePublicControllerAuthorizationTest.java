package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.identity.PlatformPrincipal;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlRuntimePublicControllerAuthorizationTest {

    private final RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
    private final PlatformRequestAuthorization requestAuthorization = mock(PlatformRequestAuthorization.class);
    private final CapabilityProjectOnboardingClient projectClient = mock(CapabilityProjectOnboardingClient.class);
    private final ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        controller.setRuntimeManagementAccess(
                new RuntimeManagementAccess(requestAuthorization, projectClient));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void agentListRejectsAnotherProjectBeforeCallingRuntime() {
        when(requestAuthorization.requirePermission(any(), any())).thenReturn(
                session(new PlatformPermissionGrant(
                        PlatformPermissions.AGENT_READ, "PROJECT", "sales")));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> controller.listAgents(null, "finance"));

        assertEquals(403, error.getStatusCode().value());
        verify(runtimeProxyClient, never()).listAgents(any(), any());
    }

    @Test
    void agentListAllowsItsGrantedProject() {
        when(requestAuthorization.requirePermission(any(), any())).thenReturn(
                session(new PlatformPermissionGrant(
                        PlatformPermissions.AGENT_READ, "PROJECT", "sales")));
        when(runtimeProxyClient.listAgents(null, "sales"))
                .thenReturn(ResponseEntity.<Object>ok(List.of()));

        ResponseEntity<Object> response = controller.listAgents(null, "sales");

        assertEquals(200, response.getStatusCode().value());
        verify(runtimeProxyClient).listAgents(null, "sales");
    }

    private PlatformAuthenticatedSession session(PlatformPermissionGrant grant) {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(7L);
        return new PlatformAuthenticatedSession(
                PlatformPrincipal.fromUser(user),
                "session",
                LocalDateTime.now().plusHours(1),
                List.of("PROJECT_OWNER"),
                List.of(grant.permissionCode()),
                List.of(grant));
    }
}
