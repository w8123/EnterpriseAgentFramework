package com.enterprise.ai.control.context;

import org.apache.ibatis.annotations.Delete;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextMemoryCandidateMapperContractTest {

    @Test
    void personalMemoryEraseDeletesApprovedAndConflictCandidates() throws Exception {
        Method method = ContextMemoryCandidateMapper.class.getMethod("deleteRelatedToItem", Long.class);
        String sql = String.join(" ", method.getAnnotation(Delete.class).value())
                .replaceAll("\\s+", " ")
                .trim();

        assertTrue(sql.contains("approved_item_id = #{itemId}"));
        assertTrue(sql.contains("conflict_item_id = #{itemId}"));
    }

    @Test
    void ownerEraseHasExplicitTenantUserLaneAndVisibilityBoundary() throws Exception {
        Method method = ContextMemoryCandidateMapper.class.getMethod(
                "deleteAllPrivateForOwner", String.class, String.class);
        String sql = String.join(" ", method.getAnnotation(Delete.class).value())
                .replaceAll("\\s+", " ")
                .trim();

        assertTrue(sql.contains("tenant_id = #{tenantId}"));
        assertTrue(sql.contains("user_id = #{runtimeUserId}"));
        assertTrue(sql.contains("memory_lane = 'RUNTIME_USER'"));
        assertTrue(sql.contains("visibility = 'PRIVATE'"));
    }
}
