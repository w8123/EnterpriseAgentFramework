package com.enterprise.ai.runtime.memory;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeConversationSessionMapperContractTest {

    @Test
    void ownerEraseScanIsTenantUserBoundedAndStable() throws Exception {
        Method method = RuntimeConversationSessionMapper.class.getMethod(
                "selectOwnerEraseCandidates", String.class, String.class, int.class);
        String sql = normalized(method);

        assertTrue(sql.contains("s.tenant_id = #{tenantId}"));
        assertTrue(sql.contains("s.user_id = #{runtimeUserId}"));
        assertTrue(sql.contains("ORDER BY s.id"));
        assertTrue(sql.endsWith("LIMIT #{limit}"));
        assertFalse(sql.contains(" OR "));
    }

    @Test
    void ownerEraseRemainingCountUsesTheSameOwnerBoundary() throws Exception {
        Method method = RuntimeConversationSessionMapper.class.getMethod(
                "countOwnerSessions", String.class, String.class);
        String sql = normalized(method);

        assertTrue(sql.contains("s.tenant_id = #{tenantId}"));
        assertTrue(sql.contains("s.user_id = #{runtimeUserId}"));
        assertFalse(sql.contains("LIMIT"));
    }

    private static String normalized(Method method) {
        return String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ")
                .trim();
    }
}
