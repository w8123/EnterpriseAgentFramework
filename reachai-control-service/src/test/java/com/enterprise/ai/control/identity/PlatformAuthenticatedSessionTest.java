package com.enterprise.ai.control.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlatformAuthenticatedSessionTest {
    @Test
    void attestedSessionCopiesIdentityAndGrantsWithoutExposingStoredCredentials() {
        var user = new PlatformUserEntity(); user.setId(42L); user.setUsername("operator");
        user.setDisplayName("操作人"); user.setPasswordHash("stored-credential");
        var roles = new ArrayList<>(List.of("ADMIN"));
        var permissions = new ArrayList<>(List.of("platform:read"));
        var grants = new ArrayList<>(List.of(new PlatformPermissionGrant("platform:read", "GLOBAL", "*")));
        var session = new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "session-42", null,
                roles, permissions, grants);
        user.setId(99L); user.setUsername("changed"); roles.clear(); permissions.clear(); grants.clear();
        assertEquals(42L, session.user().getId());
        assertEquals("operator", session.user().getUsername());
        assertEquals(List.of("ADMIN"), session.roles());
        assertTrue(session.hasGlobalPermission("platform:read"));
        assertThrows(UnsupportedOperationException.class, () -> session.permissionGrants().clear());
        var serialized = new ObjectMapper().valueToTree(session.user());
        assertEquals(3, serialized.size());
        assertFalse(serialized.has("passwordHash"));
    }
}
