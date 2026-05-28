package com.enterprise.ai.spring.registry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EafIdentityClientContractTest {

    @Test
    void externalUserDefaultsCollectionsForSyncPayloads() {
        EafExternalUser user = new EafExternalUser(
                "emp-001",
                "u-001",
                "Zhang San",
                "zhangsan@example.com",
                "13800000000",
                "D001",
                "R&D",
                null,
                null);

        assertEquals(List.of(), user.roles());
        assertEquals(Map.of(), user.attributes());
    }

    @Test
    void batchSyncResultCountsAllItemStatuses() {
        EafBatchUserSyncResult result = new EafBatchUserSyncResult(List.of(
                new EafUserSyncResult("u-001", "UPSERTED", null),
                new EafUserSyncResult("u-002", "DISABLED", null)));

        assertEquals(2, result.total());
        assertEquals(2, result.success());
    }
}
