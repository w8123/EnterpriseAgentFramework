package com.enterprise.ai.control.memory;

import org.apache.ibatis.annotations.Delete;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalMemoryCandidateMapperContractTest {

    @Test
    void personalMemoryEraseDeletesApprovedAndConflictCandidates() throws Exception {
        Method method = PersonalMemoryCandidateMapper.class.getMethod("deleteRelatedToMemory", Long.class);
        String sql = String.join(" ", method.getAnnotation(Delete.class).value())
                .replaceAll("\\s+", " ")
                .trim();

        assertTrue(sql.contains("approved_memory_id = #{memoryId}"));
        assertTrue(sql.contains("conflict_memory_id = #{memoryId}"));
    }

    @Test
    void ownerEraseHasExplicitTenantAndOwnerBoundary() throws Exception {
        Method method = PersonalMemoryCandidateMapper.class.getMethod(
                "deleteAllForOwner", String.class, String.class);
        String sql = String.join(" ", method.getAnnotation(Delete.class).value())
                .replaceAll("\\s+", " ")
                .trim();

        assertTrue(sql.contains("tenant_id = #{tenantId}"));
        assertTrue(sql.contains("owner_id = #{ownerId}"));
    }
}
