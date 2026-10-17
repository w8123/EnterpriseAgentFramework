package com.enterprise.ai.runtime.contract.bizindex;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessIndexResolutionTest {

    @Test
    void representsOnlyCurrentResolverAuthorizedData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "PAID");
        data.put("optional", null);
        BusinessIndexResolution resolution = BusinessIndexResolution.create(
                "tenant-a", "mall", "mall-order", "order", "O-1", "v12",
                data, OffsetDateTime.now(ZoneOffset.UTC));

        assertTrue(resolution.authoritative());
        assertEquals(false, resolution.hydrationRequired());
        assertEquals("PAID", resolution.data().get("status"));
        assertTrue(resolution.data().containsKey("optional"));
        assertThrows(UnsupportedOperationException.class,
                () -> resolution.data().put("status", "CANCELLED"));
    }

    @Test
    void rejectsIncompleteOrWrongSchemaResolution() {
        assertThrows(IllegalArgumentException.class, () -> new BusinessIndexResolution(
                "wrong", "tenant-a", "mall", "mall-order", "order", "O-1", "v1",
                Map.of(), OffsetDateTime.now(ZoneOffset.UTC)));
        assertThrows(IllegalArgumentException.class, () -> BusinessIndexResolution.create(
                "tenant-a", "mall", "mall-order", "order", "O-1", "",
                Map.of(), OffsetDateTime.now(ZoneOffset.UTC)));
    }
}
