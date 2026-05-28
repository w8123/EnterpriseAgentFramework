package com.enterprise.ai.agent.platform.auth;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformAuthorizationServiceTest {

    private final PlatformAuthorizationService service = new PlatformAuthorizationService();

    @Test
    void wildcardPermissionAllowsEveryManagementRequest() {
        PlatformPrincipal principal = new PlatformPrincipal(
                1L,
                "admin",
                "Admin",
                Set.of("PLATFORM_ADMIN"),
                Set.of("*"));

        assertTrue(service.isAllowed(principal, "POST", "/api/agents"));
        assertTrue(service.isAllowed(principal, "DELETE", "/api/platform/users/2"));
    }

    @Test
    void readPermissionAllowsGetButRejectsMutation() {
        PlatformPrincipal principal = new PlatformPrincipal(
                2L,
                "auditor",
                "Auditor",
                Set.of("AUDITOR"),
                Set.of("platform:read"));

        assertTrue(service.isAllowed(principal, "GET", "/api/runops"));
        assertFalse(service.isAllowed(principal, "POST", "/api/agents"));
    }

    @Test
    void platformUserAndRoleApisRequireAdminPermission() {
        PlatformPrincipal operator = new PlatformPrincipal(
                3L,
                "operator",
                "Operator",
                Set.of("OPERATOR"),
                Set.of("platform:read", "platform:write"));
        PlatformPrincipal admin = new PlatformPrincipal(
                1L,
                "admin",
                "Admin",
                Set.of("PLATFORM_ADMIN"),
                Set.of("platform:admin"));

        assertFalse(service.isAllowed(operator, "GET", "/api/platform/users"));
        assertTrue(service.isAllowed(admin, "GET", "/api/platform/users"));
        assertTrue(service.isAllowed(admin, "POST", "/api/platform/roles"));
        assertFalse(service.isAllowed(operator, "GET", "/api/platform/auth-providers"));
        assertTrue(service.isAllowed(admin, "POST", "/api/platform/auth-providers"));
        assertFalse(service.isAllowed(operator, "GET", "/api/platform/business-users"));
        assertTrue(service.isAllowed(admin, "GET", "/api/platform/business-users"));
    }

    @Test
    void projectScopedOwnerCanOnlyManageMatchingProjectResources() {
        PlatformPrincipal owner = new PlatformPrincipal(
                4L,
                "owner",
                "Project Owner",
                Set.of("PROJECT_OWNER"),
                Set.of("platform:read", "platform:write"),
                Set.of(new PlatformPrincipal.RoleGrant("PROJECT_OWNER", "PROJECT", "42")));

        assertTrue(service.isAllowed(owner, "POST", "/api/scan-projects/42/scan-tools/7/test", null, "42"));
        assertFalse(service.isAllowed(owner, "POST", "/api/scan-projects/99/scan-tools/7/test", null, "99"));
        assertFalse(service.isAllowed(owner, "POST", "/api/agents", null, null));
    }
}
