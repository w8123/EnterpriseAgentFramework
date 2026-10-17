package com.enterprise.ai.runtime.contract.bizindex;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessIndexReferenceTest {

    @Test
    void exposesOnlyStableResolverArgumentsAndIsNeverAuthoritative() {
        BusinessIndexReference reference = BusinessIndexReference.create(
                "tenant-a", "project-a", "crm", "customer", "C-100", "v42",
                "crm.customer.get", OffsetDateTime.of(2026, 8, 14, 10, 0, 0, 0, ZoneOffset.UTC));

        assertEquals(Map.of(
                "resourceType", "customer",
                "resourceId", "C-100",
                "expectedSourceVersion", "v42"), reference.resolverArguments());
        assertFalse(reference.authoritative());
        assertTrue(reference.hydrationRequired());
    }

    @Test
    void rejectsReferencesThatCannotBeSafelyHydrated() {
        assertThrows(IllegalArgumentException.class, () -> BusinessIndexReference.create(
                "tenant-a", "project-a", "crm", "customer", "C-100", "",
                "crm.customer.get", OffsetDateTime.now()));
        assertThrows(IllegalArgumentException.class, () -> BusinessIndexReference.create(
                "tenant-a", "project-a", "crm", "customer", "C-100", "v42",
                "unsafe capability", OffsetDateTime.now()));
    }
}
