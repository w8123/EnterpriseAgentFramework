package com.enterprise.ai.control.context;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersonalMemoryErasureIdentityTest {

    @Test
    void hashIsStableTenantNormalizedAndOwnerSpecific() {
        PersonalMemoryErasureIdentity identity =
                new PersonalMemoryErasureIdentity("dedicated-erasure-key");

        String first = identity.hash("Tenant-A", "user-1");

        assertEquals(first, identity.hash("tenant-a", "user-1"));
        assertNotEquals(first, identity.hash("tenant-a", "user-2"));
        assertTrue(first.matches("[0-9a-f]{64}"));
    }

    @Test
    void productionRequiresDedicatedHighEntropySecret() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"production"});
        String internal = "i".repeat(32);

        assertThrows(IllegalStateException.class,
                () -> new PersonalMemoryErasureIdentity("short", internal, environment));
        assertThrows(IllegalStateException.class,
                () -> new PersonalMemoryErasureIdentity(internal, internal, environment));
    }
}
