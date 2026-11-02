package com.enterprise.ai.control.identity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformAiDelegationGrantsTest {
    @Test
    void aConnectionCannotGainPermissionsOrProjectsAfterItWasCreated() {
        List<PlatformPermissionGrant> grantedAtCreation = List.of(
                grant("workflow:read", "PROJECT", "orders"),
                grant("workflow:write", "PROJECT", "orders"));
        List<PlatformPermissionGrant> now = List.of(
                grant("workflow:read", "GLOBAL", "*"),
                grant("workflow:write", "PROJECT", "orders"),
                grant("workflow:publish", "PROJECT", "orders"));

        List<PlatformPermissionGrant> effective = PlatformAiDelegationGrants.intersect(grantedAtCreation, now);

        assertEquals(grantedAtCreation, effective);
        assertFalse(effective.stream().anyMatch(g -> g.grantsResource("workflow:read", "PROJECT", null, "billing")));
        assertFalse(effective.stream().anyMatch(g -> g.grantsResource("workflow:publish", "PROJECT", null, "orders")));
    }

    @Test
    void revocationAndScopeNarrowingImmediatelyReduceTheDelegation() {
        List<PlatformPermissionGrant> effective = PlatformAiDelegationGrants.intersect(
                List.of(grant("*", "GLOBAL", "*")),
                List.of(grant("workflow:read", "PROJECT", "orders")));

        assertEquals(List.of(grant("workflow:read", "PROJECT", "orders")), effective);
        assertTrue(effective.get(0).grantsResource("workflow:read", "PROJECT", null, "orders"));
        assertFalse(effective.get(0).grantsGlobal("workflow:read"));
        assertTrue(PlatformAiDelegationGrants.intersect(effective, List.of()).isEmpty());
    }

    @Test
    void unrelatedScopesAndMalformedGrantsDoNotBecomePermissions() {
        assertTrue(PlatformAiDelegationGrants.intersect(
                List.of(grant("workflow:read", "WORKSPACE", "one")),
                List.of(grant("workflow:read", "PROJECT", "orders"))).isEmpty());
        assertTrue(PlatformAiDelegationGrants.intersect(
                List.of(grant("*", "GLOBAL", "*")),
                List.of(grant("workflow:read", "UNKNOWN", "orders"))).isEmpty());
        assertTrue(PlatformAiDelegationGrants.intersect(
                List.of(grant("*", "GLOBAL", "*")),
                List.of(grant("workflow:read", "PROJECT", "*"))).isEmpty());
    }

    @Test
    void directRoleChecksCannotRetainAdminAfterTheGlobalGrantIsRemoved() {
        List<String> original = List.of("PLATFORM_ADMIN", "OPERATOR");
        List<String> current = List.of("PLATFORM_ADMIN", "OPERATOR", "DEVELOPER");
        assertEquals(List.of("OPERATOR"), PlatformAiDelegationGrants.roles(original, current,
                List.of(grant("workflow:read", "PROJECT", "orders"))));
        assertEquals(original, PlatformAiDelegationGrants.roles(original, current,
                List.of(grant("*", "GLOBAL", "*"))));
    }

    private static PlatformPermissionGrant grant(String permission, String type, String value) {
        return new PlatformPermissionGrant(permission, type, value);
    }
}
