package com.enterprise.ai.runtime.supervisor;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupervisorDirectFlowRouteMemoryTest {

    @Test
    void matchesOnlyTheSameQuestion() {
        var memory = new SupervisorDirectFlowRouteMemory();
        memory.remember("agent:7", "查一下最近的销售订单", "order_flow");
        assertEquals("order_flow", memory.match("agent:7", "查一下最近的销售订单", Set.of("order_flow")));
        assertNull(memory.match("agent:7", "请帮我查一下最近的销售订单？", Set.of("order_flow")));
        assertNull(memory.match("agent:7", "查一下最近的销售订单呢", Set.of("order_flow")));
        assertNull(memory.match("agent:7", "华东科技有哪些销售订单", Set.of("order_flow")));
        assertNull(memory.match("agent:7", "查一下最近的采购订单", Set.of("order_flow")));
        memory.remember("agent:7", "能不能查询销售订单", "order_flow");
        assertNull(memory.match("agent:7", "不能查询销售订单", Set.of("order_flow")));
        assertNull(memory.match("agent:7", "查询销售订单", Set.of("order_flow")));
    }

    @Test
    void neverRoutesToAToolThatIsNoLongerAnAdmittedDirectFlowOrToAnotherVersion() {
        var memory = new SupervisorDirectFlowRouteMemory();
        memory.remember("agent:7", "查一下最近的销售订单", "order_flow");
        assertNull(memory.match("agent:7", "查一下最近的销售订单", Set.of("stock_flow")));
        assertNull(memory.match("agent:8", "查一下最近的销售订单", Set.of("order_flow")));
        assertNull(memory.match(null, "查一下最近的销售订单", Set.of("order_flow")));
    }

    @Test
    void distinctQuestionsWithDifferentIntentFallBackToTheModel() {
        var memory = new SupervisorDirectFlowRouteMemory();
        memory.remember("agent:7", "查一下最近的销售订单", "order_flow");
        memory.remember("agent:7", "查一下最近的销售订单。", "other_flow");
        assertEquals("order_flow", memory.match("agent:7", "查一下最近的销售订单", Set.of("order_flow", "other_flow")));
        memory.remember("agent:7", "最近 7 天的销售订单", "order_flow");
        memory.remember("agent:7", "最近 7 天的销售订单列表", "list_flow");
        assertEquals("order_flow", memory.match("agent:7", "最近 7 天的销售订单", Set.of("order_flow", "list_flow")));
        memory.remember("agent:7", "查询华东分公司今年的销售订单总金额和最新所有订单明细及其客户名称", "order_flow");
        assertNull(memory.match("agent:7", "查询华东分公司今年的采购订单总金额和最新所有订单明细及其客户名称",
                Set.of("order_flow", "list_flow")));
        assertTrue(SupervisorDirectFlowRouteMemory.normalize("x".repeat(201)).isEmpty());
    }
}
