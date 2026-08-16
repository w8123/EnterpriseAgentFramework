package com.enterprise.ai.personalmemory;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersonalMemoryIndexIdentityTest {

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
