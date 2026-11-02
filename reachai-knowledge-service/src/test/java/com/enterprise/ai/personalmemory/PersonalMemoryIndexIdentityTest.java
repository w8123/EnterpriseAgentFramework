package com.enterprise.ai.personalmemory;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersonalMemoryIndexIdentityTest {

    @Test
    void businessDomainsAreIndependentAndLegacyHashIsUnchanged() {
        var identity = new PersonalMemoryIndexIdentity("test-personal-memory-secret");
        String legacy = identity.hash("tenant-a", "1");
        assertEquals(legacy, identity.hash("tenant-a", "1", null));
        assertEquals(legacy, identity.hash("tenant-a", "1", "LEGACY"));
        String a = identity.hash("tenant-a", "1", "BUSINESS:erp-a");
        assertNotEquals(a, legacy);
        assertNotEquals(a, identity.hash("tenant-a", "1", "BUSINESS:erp-b"));
        assertNotEquals(a, identity.hash("tenant-b", "1", "BUSINESS:erp-a"));
        assertThrows(IllegalArgumentException.class, () -> identity.hash("tenant-a", "1", "BUSINESS:BAD"));
    }

    @Test
    void productionRequiresIndependentStrongIdentitySecret() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("production");
        String internal = "knowledge-internal-service-secret-32bytes";

        assertThrows(IllegalStateException.class,
                () -> new PersonalMemoryIndexIdentity("short", internal, production));
        assertThrows(IllegalStateException.class,
                () -> new PersonalMemoryIndexIdentity(internal, internal, production));

        PersonalMemoryIndexIdentity identity = new PersonalMemoryIndexIdentity(
                "personal-memory-owner-hash-secret-32bytes", internal, production);
        String tenantA = identity.hash("tenant-a", "user-1");
        assertEquals(64, tenantA.length());
        assertNotEquals(tenantA, identity.hash("tenant-b", "user-1"));
    }
}
