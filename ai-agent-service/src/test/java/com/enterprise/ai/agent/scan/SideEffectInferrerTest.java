package com.enterprise.ai.agent.scan;

import com.enterprise.ai.skill.SideEffectLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SideEffectInferrerTest {

    @Test
    void getReturnsReadOnly() {
        assertEquals(SideEffectLevel.READ_ONLY,
                SideEffectInferrer.infer("GET", "/api/customer/query"));
    }

    @Test
    void deleteMethodReturnsIrreversible() {
        assertEquals(SideEffectLevel.IRREVERSIBLE,
                SideEffectInferrer.infer("DELETE", "/api/order/1"));
    }

    @Test
    void refundInPathReturnsIrreversible() {
        assertEquals(SideEffectLevel.IRREVERSIBLE,
                SideEffectInferrer.infer("POST", "/api/order/refund"));
    }

    @Test
    void putReturnsIdempotentWrite() {
        assertEquals(SideEffectLevel.IDEMPOTENT_WRITE,
                SideEffectInferrer.infer("PUT", "/api/profile"));
    }

    @Test
    void postReturnsWrite() {
        assertEquals(SideEffectLevel.WRITE,
                SideEffectInferrer.infer("POST", "/api/order/create"));
    }

    @Test
    void postWithSearchPrefixReturnsReadOnly() {
        // 语义提示：POST /search/customers 在实际接口里常用于复杂查询条件
        assertEquals(SideEffectLevel.READ_ONLY,
                SideEffectInferrer.infer("POST", "/api/customers/search"));
    }

    @Test
    void unknownMethodFallsBackToWrite() {
        assertEquals(SideEffectLevel.WRITE,
                SideEffectInferrer.infer(null, "/api/anything"));
    }

    @Test
    void inferAsStringReturnsUppercaseName() {
        assertEquals("READ_ONLY",
                SideEffectInferrer.inferAsString("GET", "/api/list"));
    }
}
