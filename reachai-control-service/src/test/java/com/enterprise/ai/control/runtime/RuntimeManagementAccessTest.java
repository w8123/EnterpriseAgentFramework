package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.identity.PlatformPrincipal;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeManagementAccessTest {

    private final PlatformRequestAuthorization requestAuthorization =
            mock(PlatformRequestAuthorization.class);
    private final CapabilityProjectOnboardingClient projectClient =
            mock(CapabilityProjectOnboardingClient.class);
    private final RuntimeManagementAccess access =
            new RuntimeManagementAccess(requestAuthorization, projectClient);

    @BeforeEach
    void bindRequest() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void projectGrantCannotCrossAgentProjects() {
        PlatformAuthenticatedSession session = session(new PlatformPermissionGrant(
                PlatformPermissions.AGENT_READ, "PROJECT", "sales"));
        when(requestAuthorization.requirePermission(any(), any())).thenReturn(session);

        assertDoesNotThrow(() -> access.requireProject(
                PlatformPermissions.AGENT_READ, null, "sales"));
        assertThrows(ResponseStatusException.class, () -> access.requireProject(
                PlatformPermissions.AGENT_READ, null, "finance"));
    }

    @Test
    void projectIdIsResolvedAndMustMatchTheSuppliedCode() {
        PlatformAuthenticatedSession session = session(new PlatformPermissionGrant(
                PlatformPermissions.WORKFLOW_WRITE, "PROJECT", "orders"));
        when(requestAuthorization.requirePermission(any(), any())).thenReturn(session);
        when(projectClient.getProjectById(9L)).thenReturn(Map.of(
                "id", 9L,
                "projectCode", "orders"));

        assertDoesNotThrow(() -> access.requireProject(
                PlatformPermissions.WORKFLOW_WRITE, 9L, "orders"));
        assertThrows(IllegalArgumentException.class, () -> access.requireProject(
                PlatformPermissions.WORKFLOW_WRITE, 9L, "another-project"));
    }

    @Test
    void runOpsDetailUsesItsSummaryProjectScope() {
        PlatformAuthenticatedSession session = session(new PlatformPermissionGrant(
                PlatformPermissions.RUNOPS_READ, "PROJECT", "sales"));

        assertDoesNotThrow(() -> access.requireResponseProject(
                session,
                PlatformPermissions.RUNOPS_READ,
                ResponseEntity.ok(Map.of("summary", Map.of("projectCode", "sales")))));
        assertThrows(ResponseStatusException.class, () -> access.requireResponseProject(
                session,
                PlatformPermissions.RUNOPS_READ,
                ResponseEntity.ok(Map.of("summary", Map.of("projectCode", "finance")))));
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
