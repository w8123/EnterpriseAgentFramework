package com.enterprise.ai.runtime.supervisor;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SupervisorPageQueryPolicyTest {
    @Test void separatesReadIntentFromUnnegatedWrites() {
        assertTrue(SupervisorPageQueryPolicy.looksLikePageReadRequest("查询订单，不要修改"));
        assertFalse(SupervisorPageQueryPolicy.hasUnnegatedWriteIntent("查询订单，不要修改"));
        assertTrue(SupervisorPageQueryPolicy.hasUnnegatedWriteIntent("查询订单，然后删除"));
        assertFalse(SupervisorPageQueryPolicy.hasUnnegatedWriteIntent(null));
    }

    @Test void invalidExplicitPaginationCannotSilentlyFallBackToDefaults() {
        var schema = Map.<String, Object>of("properties", Map.of(
                "pageNum", Map.of("type", "integer", "default", 1),
                "pageSize", Map.of("type", "integer", "default", 10)));
        var invalid = SupervisorPageQueryPolicy.ruleFirstArgs(schema, "查询第0页，每页20条");
        assertFalse(invalid.containsKey("pageNum")); assertEquals(20, invalid.get("pageSize"));
        assertTrue(SupervisorPageQueryPolicy.hasUnresolvedSpecificFilter(schema, invalid, "查询第0页，每页20条"));
        assertEquals(Map.of("pageNum", 3, "pageSize", 20), SupervisorPageQueryPolicy.ruleFirstArgs(schema, "查询第3页，每页20条"));
    }

    @Test void unmappedOrUnsupportedSpecificFiltersRequireNormalPlanning() {
        var schema = Map.<String, Object>of("properties", Map.of("orderSn", Map.of("type", "string", "description", "订单号")));
        var args = SupervisorPageQueryPolicy.ruleFirstArgs(schema, "查询订单号1234");
        assertTrue(SupervisorPageQueryPolicy.hasUnresolvedSpecificFilter(schema, args, "查询订单号1234"));
        assertFalse(SupervisorPageQueryPolicy.hasSafePageActionMapping(Map.of("orderSn", "context.secret"), schema, args));
        assertTrue(SupervisorPageQueryPolicy.hasSafePageActionMapping(Map.of("orderSn", "params.orderSn"), schema, args));
    }
}
