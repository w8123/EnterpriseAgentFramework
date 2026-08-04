package com.enterprise.ai.common.response;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessResponseEnvelopeTest {

    @Test
    void acceptsNumericAndStringBusinessCode200() {
        assertTrue(BusinessResponseEnvelope.failure(Map.of("code", 200, "success", true)).isEmpty());
        assertTrue(BusinessResponseEnvelope.failure(Map.of("code", "200", "success", true)).isEmpty());
    }

    @Test
    void rejectsNon200BusinessCodeWithClearQueryFailureMessage() {
        BusinessResponseEnvelope.Failure failure = BusinessResponseEnvelope.failure(Map.of(
                        "code", "500",
                        "success", false,
                        "message", "无权查看班组"))
                .orElseThrow();

        assertEquals("500", failure.businessCode());
        assertEquals("查询失败：无权查看班组", failure.message());
    }

    @Test
    void rejectsExplicitSuccessFalseEvenWithoutCode() {
        BusinessResponseEnvelope.Failure failure = BusinessResponseEnvelope.failure(Map.of("success", false))
                .orElseThrow();

        assertEquals("查询失败", failure.message());
    }

    @Test
    void leavesOrdinaryPayloadWithoutEnvelopeUntouched() {
        assertTrue(BusinessResponseEnvelope.failure(Map.of("orderStatus", "PAID")).isEmpty());
    }
}
