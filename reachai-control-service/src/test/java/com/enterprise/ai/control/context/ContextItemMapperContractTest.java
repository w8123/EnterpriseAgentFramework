package com.enterprise.ai.control.context;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextItemMapperContractTest {

    @Test
    void ownerEraseLocksOnlyPersonalNonDeletedItemsInTheResolvedNamespace() throws Exception {
        Method method = ContextItemMapper.class.getMethod(
                "selectPersonalByNamespaceForUpdate", Long.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ")
                .trim();

        assertTrue(sql.contains("namespace_id = #{namespaceId}"));
        assertTrue(sql.contains("memory_lane = 'RUNTIME_USER'"));
        assertTrue(sql.contains("status <> 'DELETED'"));
        assertTrue(sql.endsWith("FOR UPDATE"));
    }
}
