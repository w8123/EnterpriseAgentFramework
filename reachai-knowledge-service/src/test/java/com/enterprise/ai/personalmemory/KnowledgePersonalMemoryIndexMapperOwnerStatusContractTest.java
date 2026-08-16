package com.enterprise.ai.personalmemory;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgePersonalMemoryIndexMapperOwnerStatusContractTest {

    @Test
    void ownerStatusIsTenantAndHashedUserScopedAndChecksDeletedVectorResidue() throws Exception {
        Method method = KnowledgePersonalMemoryIndexMapper.class.getMethod(
                "selectOwnerProjectionStatus", String.class, String.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ")
                .trim();

        assertTrue(sql.contains("tenant_id = #{tenantId}"));
        assertTrue(sql.contains("runtime_user_hash = #{runtimeUserHash}"));
        assertTrue(sql.contains("unsafe_deleted_vector_count"));
        assertTrue(sql.contains("embedding_vector IS NOT NULL"));
        assertTrue(sql.contains("embedding_claim_token IS NOT NULL"));
        assertFalse(sql.contains("runtime_user_id"));
    }
}
